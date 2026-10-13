# Backend Plan 010 — the Data Plane data model, and the `dp-remote` flow end to end

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking. **Read [Dispatch map](#dispatch-map--what-may-run-in-parallel) before starting any task** — it says
> which tasks may run in separate worktrees at the same time and which may not.

| | |
|---|---|
| **Component** | backend (`cp-api`, `cp-core`, `dp-api`, `dp-core`, `mesh-events`, `console-gateway`, `launcher`, `arch-tests`) + `contracts/` |
| **Status** | **DRAFT, reviewed twice** — written 2026-09-28 against `integration/dp-remote-demo` @ `3cd0407`; first independent adversarial review applied (4 BLOCKER, 13 MAJOR, 10 MINOR — see [Plan review — dispositions](#plan-review--dispositions)); **revised 2026-09-29** to fold in the owner's rulings on the three gating questions and every S0 design point ([Owner rulings](#owner-rulings-2026-09-29--the-former-gating-questions)), and to record two early slices already landed (`9ec6797`, part of T0.1; `0718603`, the Java `project()` port of T1.1/T1.2); **second independent adversarial review of the amendment applied 2026-09-29** (2 BLOCKER, 9 MAJOR, 17 MINOR, re-verified against `683d802` — see [Amendment review — dispositions](#amendment-review--dispositions)). **Not approved.** **The T0.1 Step 7 owner sitting was held 2026-09-30 and closed 2026-10-05; every question the second review raised is answered** — see [Owner questions raised by the second review](#owner-questions-raised-by-the-second-review--answered-at-t01-step-7), registered in `DECISIONS.md` as **OR-60 to OR-68**, with the monitoring rulings (M-Q1 to M-Q24, `OR-69` to `OR-81`) and the execution model (`OR-70`) recorded by task S0-records on 2026-10-05. The 2026-09-29 rulings are cited as "owner ruling 2026-09-29 (<label>)" and registered in `DECISIONS.md` as OR-48 to OR-59. |
| **PROPOSED decisions relied on** (checklist item 2, knowingly failed) | `SM-D11a` (no shell composition from data — T6.1), `SM-D22` (effective-verified is per package — T6.5), `SM-D23` (target/placement identity — S0 point 22), `BE-D11` (consumer-side validation — T3.0), `FE-D17` (`hashInput` bytes — T1.1; OR-34 moves the projection server-side and so amends where `FE-D17` runs, not what it hashes). Each is cited because the **as-built code** already follows it; this plan relies on it under OR-1's owner authority and changes none of them. `docs/checklists/plan-review.md` item 2 therefore fails **on the record**, not silently. |
| **Requirements** | [`REQ-013`](../../requirements/REQ-013-fleet-apply.md) (fleet apply) · [`REQ-012`](../../requirements/REQ-012-event-driven-live-console.md) · [`REQ-006`](../../requirements/REQ-006-rbac.md) ("no endpoint reachable without it") · [`REQ-001`](../../requirements/REQ-001-unified-config-layer.md) · [`REQ-005`](../../requirements/REQ-005-three-runtimes.md) |
| **Binding inputs** | Owner rulings **OR-26 … OR-47** ([`DECISIONS.md`](../../design/DECISIONS.md), committed in `3cd0407`) — these settle the data model and win over every paper. Plus OR-1…OR-25 as already binding. |
| **Flagged, built against, not resolved** | **OR-31** (amends LOCKED `D2`), **OR-32** (names invariant `I6`), **OR-40** (implements LOCKED `B/D4` partially), **OR-42** (names LOCKED `B/D12`) — each "for architect-group ratification". This plan builds against them and **keeps every flag**; no task may describe them as ratified. |
| **Decisions this plan records** | None pre-allocated. Every new decision takes the **next free id from the merged `DECISIONS.md` at the moment it is written** (OR-11, OR-17). |

**Goal:** Every remaining contracted console operation, plus OR-45's five, served by the **production** launcher
over a real Data Plane data model — instance inventory, targets and placements, an encrypted credential store,
config-of-record, and the already-built durable apply worker running against a production SSH-Docker adapter —
delivered as dependency-ordered, independently mergeable slices.

**Architecture:** The CP keeps only governance rows (scope nodes, target governance, the package catalog,
entitlement) in `sm_control`; the DP owns every instance detail in `sm_data` (OR-26, OR-27, OR-28) and host
credentials, envelope-encrypted, in a separate `sm_secret` schema (OR-30). `console-gateway` stays the only HTTP
layer, calls `cp-api`/`dp-api` interfaces only (`gatewayUsesApisOnly`, `PlaneBoundaryTest.java:63-70`), and composes
the two-phase operations (declare, stage) as ordered calls across the two planes — never one transaction, never a
cross-schema read (`I7`). The Designer projection runs in Java in `cp-core` (OR-34). `stateVersion` comes from one
`sm_data` sequence stamped by trigger (OR-35). The mutating path stays `gateway → dp-api → durable worker →
ApplyExecutor → RuntimeAdapter` (plan 007 global constraint, `007-…md:59-61`).

**Tech Stack:** Java 17 · Spring Boot **3.5.16** (`backend/pom.xml:25`) · PostgreSQL 14 · Flyway (reactor-managed) ·
Testcontainers 1.21.4 · ArchUnit 1.5.0 (`backend/pom.xml:38`) · sshj **0.40.0** (`dp-core/pom.xml:31`) ·
JCSMP 10.30.1 (`dp-core/pom.xml:27`) · Jackson (+ YAML, already in `dp-core`) · JDK `javax.crypto` · one
JSON-Schema-2020-12 library **not yet chosen** (measured fact M1).

**Spec (read with this plan):** OR-26…OR-47 · [`REQ-013`](../../requirements/REQ-013-fleet-apply.md) ·
[`console-integration.md`](../../design/common/contracts/console-integration.md) §5, §6, §6.1, §9, §13.3 ·
[`contracts/openapi/console.yaml`](../../../contracts/openapi/console.yaml) ·
[`ENDPOINT-INVENTORY.md`](../../../contracts/openapi/ENDPOINT-INVENTORY.md) · [`contracts/events/`](../../../contracts/events/) ·
[plan 007](007-production-api-and-event-driven-walking-skeleton.md) Tasks 4 (controller half), 5, 5a, 6, 6a, 6b, 9, 11–14 and its
Results · [plan 008](008-placement-registry-and-apply-journal-identity.md) · [plan 009](009-console-api-and-monitoring-replacement.md) (superseded in part, `OR-78` and `OR-80`) ·
[plan 011](011-monitoring-backend.md).
The decision papers `dp-data-model-proposal.md` (v1 + critic) and `-v2.md` are **background only**: where a paper
and a ruling differ, **the ruling wins**. Measured divergences this plan follows: OR-35 chose **one `sm_data`
sequence** (the v2 paper recommended a per-instance counter); OR-30 chose **envelope encryption in PostgreSQL**
(the paper recommended mounted files); OR-43 chose **live log levels first** (the paper recommended config-only);
OR-46 puts the **apply-outcome event in the first E2E slice** (the paper recommended REST-only first); OR-40 chose
a **minimal entitlement record** (the paper recommended excluding entitlement).

---

## Global Constraints

Every task's requirements implicitly include this section.

- **One mutating apply path:** `console-gateway` → `dp-api` → durable worker → `ApplyExecutor` → `RuntimeAdapter`.
  A controller that calls a `RuntimeAdapter` or performs a mutating remote operation is a defect (plan 007 Task 12,
  built here as T6.7). Bounded **reads** of a host (logs, a live log-level read) are legal on the request thread.
- **`I5`:** the tenant is `Session.organizationId()` (`cp-api/.../session/Session.java`), never a request field.
  Every new query is bind-parameterised and tenant-scoped. There are exactly **two** cross-tenant reads, both
  one-column tenant-id lists in the persistence package that feed a per-organization scoped loop: OR-44's worker
  enumeration (`TenantEnumerationRepository.java:75`), and — **owner ruling 2026-09-29 (Q-T), `OR-49`** — a
  second, `SELECT DISTINCT tenant_id FROM sm_data.instance_state WHERE lifecycle = 'active'`, written **once** in
  `TenantEnumerationRepository` (T2.4 Step 3b) and used only by the health poller (T7.2), fleet batch advancement
  (T11.2) and the key re-wrap command (T2.4 Step 3b) — **plus, owner ruling M-Q3 2026-09-29 (`OR-72`, which amends
  `OR-49`), plan 011's `MetricsCollector` as the named fourth caller** (it is built in plan 011, not here; nothing in
  this plan calls the list for it). No task adds a **third** list, or a fifth caller, without an owner ruling, and no
  task re-spells the second. Everything that *can* run tenant-scoped still does (the declare sweep and retention run
  inside tenant-scoped requests — T4.2, T5.2; the indeterminate reconciler rides OR-44's existing enumeration, T6.8).
- **SM is a generic manager for every MI (owner direction, 2026-09-29, recorded with the S0-10 ruling).** Behaviour
  that differs per MI comes from a **package declaration** (`contracts/solution-package.schema.json`), never from a
  branch on the solution name. A task that finds itself writing `if (solution == "cache")` — or a Cache-only
  constant on a production path — stops and asks whether a declaration can carry it; if none exists, the schema
  change is routed, not worked around. Cache remains the **v1 integration fixture** (the E2E target and the measured
  facts about it), not a code path.
- **`I5` across planes:** a request's DP tenant is `Session.organizationId()`, and the CP resource it names resolves
  to the same organization — measured, not assumed: the session's organization is the principal's own
  (`JdbcSessionRepository.java:62-69` returns `p.organization_id`), and `ScopeEvaluatingAuthorizationService.require`
  refuses `404` when the resolved resource's organization differs from the principal's
  (`ScopeEvaluatingAuthorizationService.java:36`). Every two-plane composition calls `require` on the path resource
  **before** it writes either plane, so the two can never disagree; T4.4 pins it with a test.
- **Host paths and container names never come from the browser unbounded (B2).** `SshDockerPlacement`'s premise is
  that its fields come from trusted deployment configuration (`SshDockerPlacement.java:12-13`). A declare body may
  name them only **inside the envelope the target's registrant fixed** — `allowed_config_root` and
  `allowed_container_prefix` on `target_connection`, set by the operator command that registers the target (T2.7) and
  immutable afterwards — enforced by the service, a database trigger, and the adapter's canonical-path preflight.
- **Setup commands are standalone, plane-local `main`s** (`SmControlLockoutRecoveryMain.java:19-40` is the
  precedent): each lives in its plane's persistence package (JDBC confinement), connects with that plane's runtime
  credential from the environment, constructs its collaborators by hand, and **never boots a Spring context** — so
  it can never start a second `ApplyWorker` or recovery sweep against the live database (`WorkerSingletonGuard`,
  `SecondWorkerPoolRefusedBeforeSweepIT`). None lives in `launcher` (`SolutionManagerConfiguration.java:14-16`).
- **Lock order is `apply_scope → instance_state → (row)` everywhere** (T0.3). A copy-forward (rollback, restore,
  accept, fleet member generation) and the submit that follows it run as **two transactions**: the stage commits
  (`instance_state` lock only), then admission takes `apply_scope` then `instance_state`. One transaction doing both
  would invert admission's order and can deadlock (m3). A crash between them leaves a staged generation with no apply —
  harmless, and a re-run is idempotent by OR-33 guard 1.
- **`I6`:** no secret value in any `sm_data`/`sm_control` config table, history, diff, export, log line, exception
  message, event payload or HTTP response. Secret values exist only as ciphertext in `sm_secret` and, transiently, in
  rendered bytes written to the host. Every config-describing hash is keyed (OR-32).
- **`I7`:** CP code never imports DP code and vice versa (`PlaneBoundaryTest.java:53-61`); no cross-schema FK or
  join; `sm_secret` is DP-owned and CP never touches it.
- **`I1`:** SM may write deployment configuration (including Spring `management.*`/`logging.*` properties in the file
  it owns); it may not require an MI code, image or build change.
- **JDBC lives only in `com.solace.sm.dp.apply.persistence..` and `com.solace.sm.cp.persistence..`**
  (`jdbcIsConfinedToPersistencePackages`, `PlaneBoundaryTest.java:128-134`). New DP repositories go in
  `dp.apply.persistence` even when their domain is not "apply"; ⛔ **do not widen the rule** to make a package name
  prettier.
- **The launcher composes, it constructs nothing** (`SolutionManagerConfiguration.java:14-28`;
  `CompositionBoundaryTest`): it may not hold a `DataSource`, JDBC type, `ApplyExecutor` or `RuntimeAdapter`. New
  beans are declared in the owning module's `@Configuration` and reach the launcher through an aggregator import.
- **`sm_control` migrations are frozen once published (OR-22).** Every new one is a **new** file, pinned in
  `backend/cp-core/src/main/resources/db/migration/sm_control/CHECKSUMS.sha256` in the same commit
  (`MigrationChecksumsTest`), and its SHA is appended to `backend/scripts/frozen-migration-anchors.txt` in a
  **follow-up** commit after it reaches origin (a commit cannot anchor itself).
- **OR-25 is the rule for every new `sm_control` runtime write:** complete audit context (known operation,
  correlation id, IP-literal client address, actor) or the trigger aborts the business write
  (`V012__audit_fail_closed.sql:91-125`). A new table needs its `row_key` branch (`V012:127-176`), its
  `change_audit_trigger`, its `truncate_refused` trigger, the widened `audit_operation` CHECK (`V011:39-42`) and the
  widened readiness lists (`ControlPlanePersistenceConfiguration.java:68-145`). Every CP write IT runs **as
  `sm_cp_runtime`**, or it proves nothing.
- **Contracts are single-writer.** `console.yaml`, `ENDPOINT-INVENTORY.md` and `contracts/events/**` change only in
  their owning task, as one reviewed change, with `./contracts/run-checks.sh --strict` green before and after.
- **No route is "served" until it is reachable in the production boot** — a launcher-level IT that boots the real
  `SolutionManagerApplication` against PostgreSQL over real HTTP, as `HierarchyEndpointsIT` does. A controller that
  only passes a gateway unit test is "built", not "served", and `ENDPOINT-INVENTORY.md` must say which.
- **`demo-launcher` is never a production authority.** No production class imports `com.solace.sm.demo..`; no
  production route falls back to it (plan 007 Task 12/13).
- **Browser mesh notify stays gated by open items 32/33/35.** DP/CP events are emitted after commit and verified
  against a local broker; nothing here enables a browser subscription or a mesh ticket.
- **Decisions:** no id is reserved. A task that must record a decision allocates the next free id from the merged
  `DECISIONS.md` **when it merges** (parallel tasks draft in their design section with the literal placeholder
  `«next free BE-D»`; the integrator allocates in merge order).
- **Every guard carries a planted-violation proof (`L7`); a mutation that does not fire is a finding about the tests
  (`L22`).** Each task's "Prove they fire" step lists its mutations; run each, see red, revert, see green, record it.
- **`I2`:** no external fact from memory. The [Measured facts](#measured-facts--fill-each-before-the-task-that-needs-it)
  table names every one this plan needs and the task that must fill it first. A task whose fact is unfilled stops.
- **Docker/Colima is shared state.** Before container work: `colima status`, `docker context show`, `docker ps`.
  Never prune, switch context, stop or inspect foreign containers. **Testcontainers runs are serialized across
  parallel agents** (the integrator hands out one run at a time); code may be written in parallel, containers may not
  be started in parallel.
- **Commit attribution:** every commit ends with the session's attribution trailer.

### Verification commands (used by every task)

```bash
# Process-local Testcontainers override, as used by every measured run on this worktree (does not touch
# ~/.testcontainers.properties or colima). Equivalent to sourcing the session scratchpad's tc-env.sh.
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
VERIFY() { mvn -o -f backend/pom.xml -pl "$1" -am verify; }

VERIFY <module>                 # the task's own module(s)
VERIFY arch-tests               # the boundary rules + every module arch-tests depends on
VERIFY demo-launcher            # ⚠️ NOT covered by the line above (see below)
./contracts/run-checks.sh --strict
git diff --check
```

⚠️ **`VERIFY arch-tests` does not build `demo-launcher`** — measured: `arch-tests/pom.xml` depends on the seven
production modules only, and `demo-launcher` is a separate reactor module (`backend/pom.xml:85`). `demo-launcher`
compiles against dp-core types this plan changes (`SshDockerPlacement` — `DemoInstanceService.java:195`;
`SshDockerRuntimeAdapter`/`SshDockerConfigEditor`/`SshDockerMetrics` construction — `DemoConfiguration.java:28-46`).
So **every task that changes a dp-core type `demo-launcher` names runs `VERIFY demo-launcher` and keeps it
compiling in the same commit**, listing the demo files in its Files block (T5.1, T6.1). `demo-launcher` is deleted
by plan 011 (P11-9.1), not here; until then a red demo build is this plan's defect, not a later task's surprise.

⚠️ `-o` (offline) fails on any dependency not already in `~/.m2`. **Two tasks add a new external artifact:** T0.4
(the licence-reporting Maven plugin and its own dependencies) and T3.0 (the JSON-Schema library). Each needs one
online resolution, which requires the user's permission — the executing agent asks, it does not drop `-o` on its
own — and after it the reactor builds offline again. Every new artifact passes `I8`'s licence check first (T0.4;
`REQ-014`).

---

## Review Focus

The five input classes most likely to bite a real operator that no single task's happy path exercises. Each line
names the owning task where its pinning test is written.

1. **A client that missed events backfills with `sinceStateVersion` while two applies commit out of stamp order.**
   A single global sequence (OR-35) hands out `11` before `10` commits; a backfill after seeing `11` must still
   receive `10`. Expected: per-instance commit order equals stamp order, so nothing is skipped. → T2.1
   (`stampsWithinOneInstanceAreMonotonicInCommitOrder`) and T6.3 (`backfillNeverSkipsARowCommittedAfterAHigherStamp`).
2. **Two instances on one large VM collide on a container name or a config path.** Expected: the second declare is
   refused `409 CONFLICT` naming the collision class (not the other instance), and nothing is written on either
   plane. → T2.1 (`aSecondPlacementOnOneTargetMayNotReuseContainerNameConfigDirOrHealthPort`, `aSecondLiveTargetForTheSameHostKeyIsRefused` — per organization, OR-89) and T4.4.
3. **The host is unreachable** during a health poll, an apply, a log read or a live log-level read. Expected:
   health reads the NOTFOUND `unreachable` state (OR-39's "UNKNOWN, not DOWN" — S0 point 11, ruled by owner ruling
   2026-09-29 (S0-11)), an apply that could not start lands `FAILED_NO_EFFECT` with a classifier, an apply cut off
   after its write lands `APPLY_INDETERMINATE` and the reconciler retries the inspection with bounded backoff and
   never overwrites content it did not write (owner ruling 2026-09-29 (verify-then-retry)), a read answers with a
   declared degraded `availability` — never a `500`, never a hang past its deadline. → T7.2, T6.3, T6.8, T9.1.
4. **Two operators stage from the same `baseStateVersion`.** Expected: the second receives `409 CONFLICT`; the first
   operator's generation is not overwritten; re-staging content identical to the latest generation returns that
   generation with `unchanged: true` and writes no row (OR-33 guard 1). → T5.2, T5.3.
5. **The master key is missing, malformed or wrong at boot, or rotated mid-flight.** Expected: the application
   refuses to start (fail closed) rather than failing on the first SSH connection; a wrong key fails decryption with
   a classifier that carries neither ciphertext nor plaintext; rotation re-wraps without a window in which a resumed
   apply cannot decrypt. → T2.4.

---

## Prerequisites

1. ✅ **Plan 007 Tasks 4 (engine), 4a, 4b are merged** (`aed3d09`): `ApplyExecutor.admit()`/`runAdmitted()`
   (`ApplyExecutor.java:133,186`), the durable worker and startup recovery, both inert in production because no
   `ApplyExecutor` bean exists (`ApplyWorkerConfiguration.java:50,58` take `ObjectProvider<ApplyExecutor>`).
2. ✅ **The hierarchy reads are served** (`b1847a5`), with `stateVersion = 1` (`HierarchyController.java`
   `STATIC_STATE_VERSION`) that S0 removes (OR-38).
3. ✅ **OR-26…OR-47 are committed** (`3cd0407`). ⚠️ Four are flagged for architect ratification (header). That flag
   does not block building (OR-1 makes the owner the final authority); it blocks describing them as ratified.
4. **The worktree is clean** apart from `.playwright-cli/evidence/` (measured at `3cd0407`). Run
   `git status --porcelain` before each task and act on what it says.
5. **S0's contract changes must be reviewed by the owner before merge** — they are the reviewed single-writer
   changes the rulings themselves call for (OR-36/37/38 "one reviewed `console.yaml` change"; OR-4 "its normative
   contract lands later as one reviewed single-writer change"; owner ruling 2026-09-29 (S0-10): the package-schema
   field for probe strength). Nothing downstream of S0 starts before S0 merges, **except** the two early slices the
   integrator has already landed (T0.1's partial contract change; T1.1/T1.2's `project()` port — both recorded in
   their tasks with exactly what remains). **Every S0 design point is now ruled** (2026-09-29), so the owner's review
   at T0.1 Step 7 is a review of the *remaining contract diff* against those rulings — **plus** the decisions the
   second review raised ([Owner questions raised by the second review](#owner-questions-raised-by-the-second-review--answered-at-t01-step-7)),
   which are put in the same sitting so the plan stops once, not five times.
   **The fleet design (T0.0) is merged before T0.1's owner review**, because OR-47 requires it *before the apply
   slice* and its authorization shape (ruled: owner ruling 2026-09-29 (fleet authorization)) constrains T0.1 points
   10 and 24.
6. **Plan 008 is amended, not executed, by this plan** (OR-29): its Task 1 registry moves here with `target_id`;
   plan 008 keeps multi-placement and journal identity (Tasks 2–7), renumbered after this plan's `sm_data`
   allocation (T0.3 edits it).

---

## Owner rulings 2026-09-29 — the former gating questions

*Formerly "Owner questions that gate tasks".* Each row was an owner decision this plan **may not make**; each is now
**ruled** (owner ruling 2026-09-29, recorded verbatim in the session's rulings file under "Plan-010 gating
questions"). The integrator registers each in `DECISIONS.md` (OR-48 to OR-59); this plan cites the label. **No task is
gated on any of them any longer.** The original question, status quo and options are kept below the rulings table
**for the record**, so a reader can see what was chosen against what — ⛔ they are not reopened by this plan.

| Id | Ruling (owner ruling 2026-09-29) | Tasks un-gated, and what each now does |
|---|---|---|
| **Q-E** | **(a)**, `OR-48`: an operator command on the host authorized by the runtime credential (OR-20's lockout-recovery model); audit actor `NULL`, operation `ENTITLEMENT_SET`; V013 widens V012's actor exemption to `ENTITLEMENT_SET` **on the `entitlement` table only**. No verb added or bent. | **T3.2** builds `EntitlementMain` on (a) unconditionally; **T2.0**'s fourth edit (the table-scoped exemption) is **unconditional**. Off the critical path now: nothing on it waits for an answer. |
| **Q-T** | **(a)**, `OR-49`: a second `I5` exception beside OR-44 — the one-column read `SELECT DISTINCT tenant_id FROM sm_data.instance_state WHERE lifecycle = 'active'`, persistence package only, feeding a per-organization scoped loop; used by the health poller, fleet batch advancement and the key re-wrap command. | **T2.4 Step 3b** writes it (once, in `TenantEnumerationRepository`) and builds `KeyRotationMain`; **T7.2** builds `HealthPoller` over it; **T11.2** builds batch advancement over it. No other caller in this plan (Global Constraints); `OR-72` (M-Q3) later names plan 011's `MetricsCollector` as a fourth. |
| **Q-R** | **(a), literal**, `OR-50`: keep the last 10 (configurable) plus every generation any journal row names; the bound is N + one generation per apply ever submitted (~2–5 KB each). Journal pruning is a later decision. | **T5.2**'s delete-trigger predicate is fixed: a generation named by **any** `apply_request` row of the instance is never deleted. (b) was not chosen. |

**The questions as they were put (record only, 2026-09-28):**

| Id | Question | Status quo (measured) | Options — delta | Recommendation | Gated (before the ruling) |
|---|---|---|---|---|---|
| **Q-T** | **A tenant source for DP background loops** (B4). | One cross-tenant read exists: `tenantsWithNonTerminalWork()` — `SELECT DISTINCT tenant_id FROM sm_data.apply_request WHERE state IN (…blocksScope…)` (`TenantEnumerationRepository.java:75-82`), OR-44's exception. A tenant with no in-flight apply is never enumerated. | **(a)** widen OR-44's exception by one more one-column read, `SELECT DISTINCT tenant_id FROM sm_data.instance_state WHERE lifecycle = 'active'`, same package, same "feeds a per-tenant scoped loop" condition — the health poller, fleet advancement and the re-wrap command iterate it. **(b)** no DP enumeration: the poller runs only for tenants OR-44 already yields — an idle tenant's health is never polled, so OR-39's detection bound holds only during applies (fails OR-39). **(c)** a CP-supplied tenant list through a port — still a cross-tenant read, now in `sm_control`, plus a cross-plane call on every tick. | **(a)** — the shape and conditions OR-44 already accepted, over one more table. | **T7.2** (poller), **T11.2** (fleet batch advancement), **T2.4 Step 3b** (the KEK re-wrap command reads every tenant's DEKs). **Not** T4.2 or T5.2: both now run inside tenant-scoped requests. |
| **Q-E** | **Who authorizes an entitlement change** (M3). OR-40: "managed by the install administrator in v1". | No catalog verb names install administration (`Permission.java`; 38 verbs, closed); OR-8 forbids role checks in code; the one operator-command precedent (`SmControlLockoutRecoveryMain`, OR-20) acts with **no actor**, and V012 exempts `LOCKOUT_CLEARED` from the actor requirement on `login_throttle` only (`V012__audit_fail_closed.sql:109-124`). | **(a)** an operator command whose authority is the `sm_cp_runtime` credential on the host (OR-20's model): audit actor `NULL`, operation `ENTITLEMENT_SET`, and V013 widens V012's actor exemption to `ENTITLEMENT_SET` **on `entitlement` only**, table-scoped exactly as V012 scopes its own. **(b)** a principal-authenticated command checking an existing verb at install scope — no verb fits; `role.edit` would overload a verb that means something else. **(c)** a new verb — reopens the closed catalog (OR-9's route). | **(a)** — it reads "install administrator" as the installing operator, as OR-20 reads "operator"; no verb is bent. | **T3.2** Step "entitlement command"; **T2.0**'s exemption lands only on **(a)**. |
| **Q-R** | **What bounds `config_generation`** (M12). OR-33: "the last 10 … (configurable) plus every generation still referenced by a journal entry, audit record or rollback target". | `apply_request` is never pruned — the runtime role has no `DELETE` on it (`V002__apply_request.sql:148-153`) — so "referenced by a journal entry" keeps **every generation ever admitted**. The effective bound is *N + one generation per apply ever submitted*: proportional to the journal, not constant. | **(a)** the literal reading: state the bound as "N + journal-referenced"; journal pruning is a later owner question. **(b)** narrow "referenced" to generations named by a **non-terminal** request or **rollback-eligible** per OR-42 (`STAGED`/`APPLIED`/`SUPERSEDED`-after-staging, per placement): a hard bound of N + live work + rollback targets, but a `FAILED_*`/`CONFIG_DIVERGED` request's generation can then be pruned while its journal row still names it. | **(a)** for v1 — it is what the ruling says and never breaks a journal row's meaning; **(b)** is written down so it can be chosen knowingly. | **T5.2**'s delete-trigger predicate only. |

**The S0 design points** (T0.0's authorization shape; T0.1 points 8, 10, 11, 15, 22, 24; T0.2's two and the stop
exclusion) were owner yes/no items inside S0's review. **All are ruled** (owner ruling 2026-09-29, "Plan-010 S0
design points"): S0-24, S0-8, S0-11, S0-15, S0-22, fleet authorization and T0.2 were accepted as recommended;
**S0-10 changed the plan** (restart strategies are generic and package-driven — T0.1 point 10, the new T0.1a, T6.3,
T6.9); and the verify-then-retry ruling added an engine task (T6.8). Each point's own text below says "ruled" and
cites its label.

### Owner questions raised by the second review — answered at T0.1 Step 7

✅ **ANSWERED.** The T0.1 Step 7 sitting (owner, on behalf of the architect group — `OR-69`) took each question below
on 2026-09-30, and closed the licence batch on 2026-10-05. **Every question was answered as recommended** (for Q-L2,
the recommendation was refined to (a′)); the tasks each one gated are written for the ruling. The rulings are recorded
**verbatim** in the session's rulings file and registered in `DECISIONS.md`; this plan cites them by `OR-*` id. The
table below keeps the questions **as they were put**, for the record — ⛔ they are not reopened by this plan.

| Id | Answer | Registered as |
|---|---|---|
| **Q-L1** | **(a)**: each licence approved by exact coordinate and version in `backend/licence-exceptions.txt`, with its scope; the allow-list is unchanged. Groups A and B (2026-09-30), groups C and E and the test images (2026-10-05), two explicit waivers (rows 19 and 20) | `OR-66`, `OR-67` |
| **Q-L2** | **(a′) Elect the stated choice**: where the licence text states a choice (SPDX `OR`, or "at your option") elect the allowed option and record the election; with no stated connective, fail closed and ask the owner. This **replaces** (a) "every declared licence must be allowed" | `OR-66` |
| **Q-R1** | **(a)**: "no file and the absence marker present" counts as *prior*, so re-apply, then verify | `OR-63` |
| **Q-R2** | **(a)**: one read-only gate observation; `HEALTHY` confirms and records `gateProved`, anything else stays `APPLY_INDETERMINATE` with `reconcile.gate.unproven` | `OR-63` |
| **Q-R3** | **(a)**: `max-redrives` 1 and `max-redrive-age` 1 h; past the age the reconciler only confirms, and gives up with `reconcile.redrive.too.late` | `OR-63` |
| **Q-R4** | **(a)**: under `manual`, a matching file is the whole proof; confirm, rung `persisted` | `OR-63` |
| **Q-G1** | **(a)**: fail closed before any write, `strategy.health.signal.absent` → `FAILED_NO_EFFECT` | `OR-64` |
| **Q-G2** | **(a)**: `CONFIG_DIVERGED`, `verify.hash.mismatch`, no automatic rollback | `OR-64` |
| **Q-C1** | **(b), as corrected**: `instanceCount` filtered per instance by `instance.view`; `byHealth` counts only instances where `health.view` is also held; the nine-key `byHealth` vocabulary; `healthRollupOf` literally at placement scope with the four NOTFOUND sub-states inserted | `OR-61` |
| **Q-C2** | **(a)**: each attention reason filtered by the verb that guards its source | `OR-61` |
| **Q-C3** | **(a)**: keep `kind` (the MI runtime) and `transport` (how SM reaches it) | `OR-62` |
| **Q-F1 to Q-F9** (raised by T0.0, [`fleet-apply.md`](../../design/backend/fleet-apply.md) §10, not this section) | all nine **(a)**, with Q-F4's three defaults | `OR-65` |
| Landed-slice divergences, points 12, 16, 17, 18, 19 | all accepted as recommended; the `Strategy` enum widened to all five tokens | `OR-60` |

**The questions as they were put:**

| Id | Question | Status quo (measured) | Options — delta | Recommendation | Gates |
|---|---|---|---|---|---|
| **Q-L1** | **Licences outside `REQ-014`'s allow-list that the tree already uses** (M-8). | The POMs in `~/.m2` declare these licences (read locally, 2026-09-29): `logback-parent` 1.5.34 → `EPL-2.0` + `LGPL-2.1-only`; `junit-bom` 5.9.3 → "Eclipse Public License v2.0"; `jakarta.annotation-api` 2.1.1 → "EPL 2.0" + "GPL2 w/ CPE"; `bcprov-jdk18on` 1.86 → "Bouncy Castle Licence"; `HdrHistogram` 2.2.2 → "Public Domain, per Creative Commons CC0" + `BSD-2-Clause`. **Not yet measured:** which versions the reactor actually resolves, and what else it resolves. That is T0.4a's report, and it is complete before the sitting. `REQ-014` sends each of these to "an explicit, recorded owner decision". | **(a)** approve each entry by exact coordinates in `backend/licence-exceptions.txt`, with its reason and scope (JUnit is test-only, for example). The allow-list stays as confirmed. **(b)** add a licence such as `EPL-2.0` to the allow-list. That also admits every future `EPL-2.0` dependency without review. **(c)** replace the dependency. Logback is Spring Boot's default logging backend, BouncyCastle arrives through sshj (`dp-core`), and JUnit is the test framework, so replacing any of them is a rewrite. | **(a)**, decided entry by entry from T0.4a's measured report | **T0.4b** (the gate), then T3.0 → T3.1 → T3.2 → T4.3 → T4.4 (on the critical path) |
| **Q-L2** | **A POM that declares several licences** (M-9). | A POM's `<licenses>` list does not say whether its licences are alternatives ("or") or cumulative ("and"). Logback, jakarta and HdrHistogram each declare two. | **(a)** fail closed: the dependency passes only if **every** declared licence is on the allow-list; otherwise it needs a Q-L1-style approval by coordinates. HdrHistogram would then need one. **(b)** pass when **any** declared licence is on the allow-list. That is the usual reading of dual licensing, but the POM cannot confirm it. | **(a)**. It follows `REQ-014`'s "anything not on the allow-list needs an explicit, recorded owner decision" | **T0.4b** |
| **Q-R1** | **The reconciler's "prior" when the prior state was *no file*** (B-2). | What the file held before the write is retained **on the host** by the adapter. The pre-image is at `preimagePath(p, generation)`, and an absence is recorded as a marker file at `absencePath(p, generation)` (`SshDockerRuntimeAdapter.java:153-163`). `BE-D22` decided that a **restore to** absence cannot be journalled (`decisions.md:1160-1163`): there is no hash for `ROLLBACK`'s `config_hash`. A reconciler re-apply is different. It writes the **intended** bytes under the `MATERIALIZE` intent's own bound hash, so `BE-D22`'s obstacle does not arise. OR-59 says "prior **content** → re-apply". | **(a)** "no file, and the absence marker present" counts as *prior*: re-apply, then verify. **(b)** it counts as *other*: no write, stays `APPLY_INDETERMINATE`, alert. | **(a)**. Writing where there is no file overwrites nothing, and the re-apply is fully journalled | **T6.8** |
| **Q-R2** | **What a reconciler confirmation proves under `rolling-health-gated`** (M-5). | OR-59: "intended file and restarted after the write → confirmed, no repeat action". OR-53: "the API and UI report which strength the gate proved". The original run may have gone indeterminate **inside** the gate (`ApplyExecutor.java:486-495`), so no gate verdict exists for it. | **(a)** take one read-only gate observation (`status()`, which repeats no action). `HEALTHY` on the declared probe → confirm, recording `gateProved`. Anything else → stays `APPLY_INDETERMINATE`, classifier `reconcile.gate.unproven`, alert. **(b)** confirm with no health read. The resolution evidence says "gate not re-observed" and no `gateProved` is reported. | **(a)** | **T6.8** |
| **Q-R3** | **Bounds on re-drives, and on how late one may happen** (M-3, MINOR 9). | OR-59 bounds only retries of an **unreachable** host. A re-drive that is cut off again lands `APPLY_INDETERMINATE`, and the next inspection may restart again, so a flapping host could be restarted without limit. A DP that is down for hours resumes its reconciliations when it returns, so a restart can come hours after the operator asked for one. | **(a)** `sm.dp.apply.reconcile.max-redrives` (default **1**). After it, give up (stays `APPLY_INDETERMINATE`, alert: OR-59's own exit). Also `sm.dp.apply.reconcile.max-redrive-age` (default **1 h** from the request's first `APPLY_INDETERMINATE` landing). Past that age the reconciler may still **confirm** (a read) but never restarts or re-applies; it gives up with `reconcile.redrive.too.late`. **(b)** a re-drive bound only, with the age stated in the runbook. **(c)** no new bound; state the risk in the runbook. | **(a)** | **T6.8** |
| **Q-R4** | **Confirming under `manual` without a start-time check** (MINOR 5). | OR-59's row "intended, not restarted → restart only" names no strategy. Under `manual` the core never calls the lifecycle verb (`RuntimeAdapter.startOrUpdate` javadoc: *"under the default `manual` the core never calls this"*), so restarting would be an action the operator did not choose. | **(a)** under `manual`, a matching file is the whole proof: confirm, and the rung is `persisted`. **(b)** under `manual`, leave it for the human. | **(a)**. It is the only reading consistent with what `manual` means | **T6.8** |
| **Q-G1** | **`rolling-health-gated` on a placement whose package declares no probe SM executes** (M-6). This covers a package with no probe, or with only `broker-heartbeat`, which v1 does not run: `T6.9` probes HTTP kinds only. | T0.1 point 26 said the gate fails closed. T6.9 point 2 kept `docker inspect`'s signal, which is `HEALTHY` whenever the image or launch spec has a `HEALTHCHECK` (`SshDockerRuntimeAdapter.java:182-193`), so the gate would pass with no declared strength to report. | **(a)** fail closed: before any write, `strategy.health.signal.absent` → `FAILED_NO_EFFECT`. The package is the only authority on strength (`I1`, `I2`). **(b)** gate on `docker inspect` and report `gateProved` as absent: a passed gate with no proved strength. | **(a)** | **T6.9** |
| **Q-G2** | **What an apply lands on when the post-restart read-back does not match** (M-7). | Today the gated path lands `APPLIED` without comparing the file (`ApplyExecutor.java:504-507`). OR-53 requires the read-back but does not name the landing when it fails. When the gate itself fails, the engine already lands `CONFIG_DIVERGED` and does not roll back, because rolling back a restarted replica is a second outage (`ApplyExecutor.java:497-502`, `SM-D15`). | **(a)** `CONFIG_DIVERGED`, classifier `verify.hash.mismatch`: no automatic rollback, the same as a failed gate. **(b)** enter `BE-D22`'s exhaustion path (assess, then restore): an automatic rollback over a restarted replica. | **(a)** | **T6.9** |
| **Q-C1** | **Home summary's `byHealth` counts** (T0.1 row 15, MINOR 12). | The operation requires `solution.view` + `instance.view`, not `health.view`, but it returns health counts. | **(a)** add `health.view` to `x-sm-permission-also`. **(b)** show `byHealth` only where `health.view` is held at the item's scope, and omit it elsewhere (per-item filter). **(c)** keep as is: `instance.view` then implies seeing health counts. | **(b)**. The reach classification already filters per item | **T0.1** (text), T10.5 |
| **Q-C2** | **Home attention's apply/config reasons** (T0.1 row 16, MINOR 12). | Attention requires `health.view`, but its `apply-indeterminate`/`config-diverged` reasons are apply state, which `observeApply` guards with `config.view`. | **(a)** filter each reason by the verb that guards its source: `health` needs `health.view`; `apply-indeterminate` and `config-diverged` need `config.view`. **(b)** keep everything under `health.view`. | **(a)** | **T0.1** (text), T10.5 |
| **Q-C3** | **`Target.kind` beside the new `transport`** (T0.1 row 22, MINOR 12). | `Target.kind` is `[docker, kubernetes, vm]` (`console.yaml:1379-1386`). Point 22 adds `transport: ssh-docker`, which overlaps with it. | **(a)** keep both: `kind` = the MI runtime (`SM-D*`'s VM, Docker, Kubernetes), `transport` = how SM reaches it. The description states the v1 pair (`transport: ssh-docker` ⇒ `kind: docker`). **(b)** drop `transport`, keeping `kind` as today. **(c)** replace `kind` with `transport`. | **(a)** | **T0.1** (text), T2.7 |

⛔ **Not a question: whether `test` scope and build plugins are covered by `I8`.** `REQ-014` answers it: *"every
third-party dependency Solution Manager ships **or builds with** — direct **and** transitive"*. Test-scope
dependencies and build plugins, with their own dependencies, are in scope. Whether the chosen plugin can **see**
them is a measured fact (M17), not a policy choice.

---

## What each slice may and may not claim

| Slice | May claim when its tasks are merged | Must not claim |
|---|---|---|
| **S0** contracts | The fleet design (before the apply slice, OR-47); the reviewed contract for every operation this plan serves, fleet included; the approved `instance.apply-outcome` event contract; the package-schema field declaring a health probe's strength (T0.1a) | That any new operation is served; that the early partial `console.yaml` slice is the whole S0 contract (T0.1 lists what remains) |
| **S1** projection | A Java `project()` **structurally equal** to the TS engine on all 33 shared fixtures (landed early — `DesignerProjectorFixtureTest`), and byte-identical only once T1.2's remaining byte-level check lands | That the Designer is served; byte identity before its check exists |
| **S2** DP inventory | Tables, sequence, triggers, credential store and target governance exist and are tested as `sm_*_runtime`; `listTargets` **served** | That any instance can be declared |
| **S3** catalog | `loadPackage` and `listSolutions` **served**, validator parity on every fixture and negative, entitlement at load, `catalog.package` emitted after CP commit to a local broker | Signing (deferred to phase 2, OR-40); that load is "signed" — it is recorded **unsigned** |
| **S4** declare | `declareInstance`, `listInstances`, `getInstance` **served**; the two-sided `bindTarget`/`target.use` check; quota at declare | Agent auto-registration (AGW-D\*, unbuilt); undeclare (no verb, open item 39d) |
| **S5** config of record | `getDesigner`, `stageConfig`, `getConfigHistory` **served**; ladder rungs `desired`/`rendered` | That staging writes to a host (it never does); secret-field *values* (S10) |
| **S6** production wiring | **The first E2E slice** (OR-46): declare → stage → apply → observe on one SSH-Docker Cache instance, `submitApply`/`observeApply`/`stopInstance` **served**, the worker live in production, `instance.apply-outcome` (+ rollout, config-state) emitted after commit and **observed on a local broker** | Browser live notify (open items 32/33/35 — the console observes by REST and says so in `availability`); multi-placement (plan 008); B/D5's walking skeleton (no metrics/graph); that a `rolling-health-gated` apply proved **readiness** for a package whose probe is declared liveness-only (Cache's is — T0.1a; the API says which strength the gate proved, T6.9) |
| **S7** health | `getHealth` **served** from persisted, change-only DP polling of every tenant with a live instance (through the second tenant list, owner ruling 2026-09-29 (Q-T)); `instance.health` emitted | Push health from the MI (`I1` forbids new emission) — the honest detection bound is interval + timeout |
| **S8** rollback | `rollbackApply` **served** as copy-forward (OR-42) | That `B/D12`'s reading is ratified (flagged) |
| **S9** logs | `readLogs`, `getLogLevel`, `setLogLevel` **served** after D-LOG; P1 live via SSH only | Any network exposure of the loggers endpoint; P1 for direct-HTTP-only placements (P2 only, OR-43) |
| **S10** OR-45 | secrets, restore-runtime, accept-runtime (after D-ACCEPT), home summary/attention **served** | That `accept-runtime` reads back secrets (it must refuse or drop them, `I6`) |
| **S11** fleet | Fleet apply **served** after D-FLEET, meeting REQ-013's three acceptance bullets | Anything beyond REQ-013 (scheduling, canaries) |
| **S12** close | The plan's Acceptance section | Frontend cutover, deployability with the current UI (plan 007's release gate stands) |

---

## Measured facts — fill each before the task that needs it

`I2`: WebFetch a specific URL, or measure locally, and write the result (URL or command + the verbatim line that
settles it + date) into this table in the task's own commit. Web search is unavailable. **A task whose row is empty
stops at that row.**

| # | Fact | How to settle it | Needed by | Status |
|---|---|---|---|---|
| **M1** | A Java JSON-Schema library that validates **draft 2020-12** (incl. `$ref` to `$id`, `unevaluatedProperties` if the schema uses it), its exact version, licence, and whether it is in `~/.m2` | WebFetch the candidate's README/release page at a pinned tag (e.g. `https://github.com/networknt/json-schema-validator/blob/<tag>/README.md`); grep `contracts/solution-package.schema.json` for the keywords it must support; `ls ~/.m2/repository/<group>` | T3.0 | ✅ **`com.networknt:json-schema-validator:2.0.8`**, Apache-2.0 (published name `Apache License Version 2.0`), plus `com.ethlo.time:itu:1.14.0` (Apache-2.0); all **24** keywords the schema uses are supported at 2020-12; every `$ref` is a local `#/$defs/…`; not in `~/.m2` before `OR-86`'s one online resolution. Source: `docs/tasks/t3-0-json-schema-library.md`; decision draft `BE-D«next»` in `docs/design/backend/decisions.md`. |
| **M2** | Spring Boot, **at Cache's own Boot version**: `management.server.address`, `management.server.port`, `management.endpoints.web.exposure.include` default, and the loggers endpoint's `GET`/`POST` request/response shape | First measure Cache's version locally (`grep -n 'spring.boot.version' /Users/parikshit/Work/Development/solace-cache-engine/pom.xml`; CLAUDE.md says 3.3.5 — **verify, do not copy**). Then WebFetch `https://docs.spring.io/spring-boot/<that version>/reference/actuator/…` (monitoring + endpoints pages) and the Actuator API doc for `loggers` | T9.2 | ❌ |
| **M3** | Spring Boot actuator **health**: HTTP status for `DOWN`/`OUT_OF_SERVICE`, the JSON `status` field, and the default of `management.endpoint.health.show-details` | WebFetch the Actuator API `health` page at Cache's version. ⚠️ Also settles, for the fixture, whether `/actuator/health` UP means *serving* or only *process up* — the input to Cache's declared `strength` (T0.1a declares `liveness` per the ruling; M3 is what the declaration's comment cites, and it never upgrades the declaration on its own) | T0.1a (comment only), T6.9, T7.2 (and T9.2) | ✅ 2026-10-05 (T6.9) for the fixture — `docs/tasks/t6-9-measured-facts.md` |
| **M4** | Does Cache re-read `application.yml` without a restart? (an **observation about the E2E fixture**, recorded in T6.6's evidence — the ladder reports only what the journal proves, `persisted` vs `restart-observed`, for every MI alike; no code branches on the answer, and a hot-reloading MI would need a *package declaration*, routed, not a Cache branch) | Local read of `/Users/parikshit/Work/Development/solace-cache-engine/src/main/java/com/solace/solutions/cache/config/CacheEngineProperties.java` (`@ConfigurationProperties(prefix = "cache-engine")` measured at line 10) and a grep for `@RefreshScope`/`ContextRefresher`/file watchers across its `src/main` | T6.2 | ❌ (partial: prefix measured) |
| **M5** | PostgreSQL 14: does a `BEFORE INSERT/UPDATE` trigger that assigns `NEW.state_version` succeed when the invoking role has **no** column privilege on `state_version`? Does `nextval()` inside a non-`SECURITY DEFINER` trigger need `USAGE` on the sequence for the invoking role? | **Measure in the migration IT** as `sm_dp_runtime` (the tree's own discipline, `V003:37-43`), then WebFetch `https://www.postgresql.org/docs/14/sql-grant.html` and `https://www.postgresql.org/docs/14/functions-sequence.html` to confirm the reading | T2.1 | ❌ |
| **M6** | PostgreSQL 14: `ALTER TABLE … ADD CONSTRAINT … FOREIGN KEY … NOT VALID` enforces new rows and skips existing ones; lock level taken | WebFetch `https://www.postgresql.org/docs/14/sql-altertable.html`; measure the refusal in the IT | T2.2 | ❌ |
| **M7** | JDK 17: standard names `AES/GCM/NoPadding`, `HmacSHA256`; GCM IV length and the uniqueness requirement per key; `GCMParameterSpec` tag length | WebFetch `https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html` and the `javax.crypto.Cipher` / `GCMParameterSpec` javadoc for 17 | T2.4 | ❌ |
| **M8** | sshj **0.40.0**: loading a private key from in-memory text (no file), host-key pinning, keep-alive configuration, whether several `Session`s may be opened concurrently on one connected `SSHClient`, and whether `SFTPClient.canonicalize(String)` resolves symbolic links (B2's root-containment preflight depends on it) | WebFetch sshj source at tag `v0.40.0` on GitHub (`SSHClient.java` `loadKeys` overloads; `KeepAlive`/`KeepAliveProvider`; `SessionChannel`; `SFTPClient.canonicalize` → the `SSH_FXP_REALPATH` request) — the tree measured only `loadKeys(String path)` (`SshjDockerTransport.java:304`) | T6.1, T7.1 | ✅ 2026-10-05 (T6.1, T7.1) — `docs/tasks/t7-health-measured-facts.md` |
| **M6a** | PostgreSQL 14: a table owner may `ALTER TABLE … DISABLE/ENABLE TRIGGER <name>` inside a migration transaction | **Not needed** — V008 no longer backfills (T2.2: it refuses a non-empty `apply_request`), so no trigger is disabled. Listed so a later edit that reintroduces a backfill knows it must measure this first | — | n/a |
| **M9** | Flyway: behaviour when a migration with a **lower** version appears after a higher one was applied (`outOfOrder` default) | WebFetch the Flyway configuration reference for `outOfOrder` at the reactor's managed Flyway version (`mvn -o -f backend/pom.xml help:evaluate -Dexpression=flyway.version -q -DforceStdout`) | [Allocation](#migration-and-id-allocation--fixed-up-front-so-parallel-tasks-cannot-collide) (every DP migration task) | ❌ |
| **M10** | Kubernetes `Deployment` `spec.strategy.type: Recreate` semantics (OR-44's single-replica runbook rule) | WebFetch `https://kubernetes.io/docs/concepts/workloads/controllers/deployment/` | T0.3 | ✅ 2026-10-05 (T0.3). Page body truncated on the rendered URL, so read from its source `https://raw.githubusercontent.com/kubernetes/website/main/content/en/docs/concepts/workloads/controllers/deployment.md`: *"All existing Pods are killed before new ones are created when `.spec.strategy.type==Recreate`."*; *"This will only guarantee Pod termination previous to creation for upgrades."*; *"`.spec.strategy.type` can be \"Recreate\" or \"RollingUpdate\". \"RollingUpdate\" is the default value."* Recorded in `install-prerequisites.md` |
| **M11** | `docker logs` `--timestamps --since --until --tail` semantics (the existing transport's `logs(...)` builds them, `SshjDockerTransport.java:203-207`) | WebFetch `https://docs.docker.com/reference/cli/docker/container/logs/` | T9.1 | ✅ 2026-10-06 (T9.1; documented and measured against Docker Engine 28.5.1, see `docs/tasks/t9-1-measured-facts.md`; it overturned the premise that the existing `logs(p, tail, after, before)` could be used as is). |
| **M12** | Where the Cache container's management port is reachable from the **host** (a loopback bind *inside* a container is not the host's loopback) — the published-port shape of the customer's launch spec, and whether `curl` exists on the host | Measure on the demo target (`docker inspect <container> --format '{{json .HostConfig.PortBindings}}'`, `command -v curl`) and WebFetch `https://docs.docker.com/engine/network/port-publishing/` | T7.1, T6.9, T9.2 | ⚠️ 2026-10-05 partly (T6.9, T7.1): local half measured; the demo-host `PortBindings`/`curl` check is open for W9 |
| **M13** | Spring Boot `logging.level.<logger>` property semantics at Cache's version (P2) | WebFetch the Boot "Logging" reference page at Cache's version | T9.4 | ❌ |
| **M14** | PostgreSQL TOAST/`jsonb` compression | **Not relied on.** OR-33's bound is enforced by pruning, not by storage behaviour — and that bound is *N + every journal-referenced generation*, which grows with the journal (as ruled — owner ruling 2026-09-29 ([Q-R](#owner-rulings-2026-09-29--the-former-gating-questions))). A task that starts relying on storage behaviour must add the row | — | n/a |
| **M16** | Spring Boot **3.5.16**: (i) relaxed binding of the environment variable `SM_DP_MASTER_KEY` to the property `sm.dp.master-key`; (ii) `@ConditionalOnProperty(name = "sm.dp.apply.production.enabled", havingValue = "true", matchIfMissing = true)` on a plain `@Configuration` reached through `@Import` (not auto-configuration) — evaluated at registration, before the test's own `@TestConfiguration` beans | WebFetch `https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html` (relaxed binding, environment variables) and the `ConditionalOnProperty` javadoc at 3.5.x; then **measure** (ii) in T6.2's launcher IT (a context with the property `false` has exactly the test's executor) | T2.6, T6.2 | ❌ |
| **M17** | `REQ-014`/`I8` licence gate. First, the Maven plugin that reports every resolved dependency's licence (candidate `org.codehaus.mojo:license-maven-plugin`): (i) its **own** licence and that of its own runtime dependencies, its version, and the goal that fails the build; (ii) what it does with a dependency whose POM declares **no** licence, and with a name it cannot map (does it fail, or pass silently?); (iii) how it handles a POM that declares **several** licences; (iv) whether its exclusion and mapping entries match **exact** coordinates and names, or regular expressions. An unanchored regex is the substring allow-listing T0.4 forbids. (v) whether it covers **test**-scope dependencies and **build plugins** with their own dependencies, all of which `REQ-014` puts in scope ("ships **or builds with**"). Then, the licence of **every** artifact the reactor already resolves. | WebFetch the plugin's repository `LICENSE`, usage page and goal reference at a pinned tag, and the source of the licence-check goal for (ii)–(iv). Then **measure** the reactor's licence report (one online resolution, as for T3.0). Where the plugin cannot cover (v), T0.4b adds a second check for the part it misses and records it | T0.4a | ⚠️ partially filled 2026-09-29 (downgraded in round 3 — (i)'s wider 72-artifact class realm is a stated residual gap); see [`docs/tasks/licence-audit-2026-09.md`](../../tasks/licence-audit-2026-09.md) |
| **M18** | Docker `State.StartedAt`: does it change on `docker restart`, and on a stop followed by a start? What is it on a recreated container (a new container id)? What does `docker inspect` report while a restart is in progress (`State.Restarting`, `State.Running`, `StartedAt`)? Which clock sets it (the Docker daemon's host clock)? | WebFetch `https://docs.docker.com/reference/cli/docker/container/inspect/` and the Engine API `ContainerInspect` response schema at the daemon's API version. Then **measure** on the demo target: `docker inspect -f '{{.State.StartedAt}} {{.State.Restarting}}'` before a restart, during it and after it | T6.8, T6.9 | ✅ 2026-10-05 locally (T6.8, T6.9) — `docs/tasks/t6-9-measured-facts.md`; the demo-host repeat and the in-progress-restart state are open for W9 |
| **M19** | `curl` on the target host. With `--fail`, what is the exit code for HTTP ≥ 400 (a Boot `DOWN` is `503`, per M3), and is the body still printed? Without `--fail`, does `--write-out '%{http_code}'` report the status, and what are the exit codes for connection refused and for a `--max-time` timeout? These decide whether a `DOWN` actuator can be told apart from a transport failure | WebFetch `https://curl.se/docs/manpage.html` (sections `--fail`, `--write-out`, "EXIT CODES"). Then **measure** once on the demo target against a closed port and against a stub that answers `503` | T6.1, T6.9, T7.2 | ✅ 2026-10-05 locally (T6.9) — `docs/tasks/t6-9-measured-facts.md`; the demo-host repeat is open for W9 |
| **M15** | Spring Framework `@Bean(defaultCandidate = false)` + qualifier injection | ✅ **Already measured in-tree**: `MeshBridgeConfiguration.java:36-45` records the measured behaviour. Re-read it; do not re-derive | T3.3 | ✅ |

---

## Migration and id allocation — fixed up front so parallel tasks cannot collide

Measured at `3cd0407`: `sm_control` holds `V001`–`V012` (frozen at anchor `245d763`,
`backend/scripts/frozen-migration-anchors.txt`); `sm_data` holds `V001`–`V006`. The next free numbers are
**`sm_control` V013** and **`sm_data` V007**. Each task below owns exactly the file it is allocated. ⛔ No task
takes a number not in this table; a task that needs another migration stops and asks the integrator for the next
free number.

| Schema | File | Owning task | Contents |
|---|---|---|---|
| `sm_control` | `V013__dp_remote_governance.sql` | **T2.0** | **Every** CP table this plan needs, in one migration: `UNIQUE (instance_id)` on `sm_control.instance` (OR-27); `catalog_solution`, `solution_package`, `entitlement`, `target`; `solution.catalog_solution_id`; the OR-25 allow-list, `row_key` branches, triggers, CHECK and grants. One migration, because every new table must `CREATE OR REPLACE` the one shared `record_change_audit()` (`V012:29`), and two parallel migrations each replacing it would silently drop each other's branches |
| `sm_data` | `V007__instance_inventory.sql` | **T2.1** | `state_version_seq`, the stamp function, `instance_state`, `placement` (with `health_kind`/`health_path`/`health_strength`), `target_connection` (with the immutable `allowed_config_root`/`allowed_container_prefix` envelope), the placement-envelope trigger |
| `sm_data` | `V008__apply_request_state_version.sql` | **T2.2** | refuses to run over a non-empty `apply_request`; `apply_request.state_version` + a stamp trigger that **skips the compaction fold**; FK `apply_scope → instance_state` `NOT VALID` |
| `sm_data` | `V009__secret_store.sql` | **T2.4** | `sm_secret.*` tables incl. the install-level `kek_canary` (the schema itself is Phase 0's, see T2.4) |
| `sm_data` | `V010__config_generation.sql` | **T5.2** | `config_generation`, `instance_state.last_generation`/`reached_rung`, the retention delete trigger (predicate per owner ruling 2026-09-29 (Q-R): any journal-named generation is protected) |
| `sm_data` | `V011__indeterminate_reconciliation.sql` | **T6.8** | `apply_reconciliation` (per-request bookkeeping: inspection and re-drive counters, `next_attempt_at`, a terminal `gave_up_at`), plus `apply_request.reconcile_class`: a nullable classifier column with the runtime role's `UPDATE` on that column only. It is stamped by T2.2's trigger, so a give-up is an observable change (M-1). The `APPLY_INDETERMINATE → RUNNING` re-drive transition is **already legal in the DDL** (measured by the second review: V006 blocks only terminal sources, and `state_fits_operation` allows `RUNNING` for `apply`). T6.8 Step 0 re-confirms this and adds no CHECK change unless it finds otherwise |
| `sm_data` | `V012__placement_health.sql` | **T7.2** | `placement_health` |
| `sm_data` | `V013__log_level_operation.sql` | **T9.3** | only if D-LOG decides the live SET is a journaled operation |
| `sm_data` | `V014__fleet_action.sql` | **T11.2** | `fleet_action` (stamped, OR-35), `fleet_member` |
| `sm_data` | `V013__designer_layout.sql` (renumbered from V017 on 2026-10-06, before publication) | **S6** (`OR-96`) | ⚠️ *Dated note 2026-10-05 (plan 011 wave review; rows above unedited).* The designer's canvas layout record, outside `config_generation`. Carried by unmerged `p010/s6` (`bce79e5`). `V015`/`V016` are **plan 011's**, not plan 008's. If S6 merges before `V011`–`V014`, they merge out of version order and the integrator recreates the persistent dev `sm_data` under the rule below |
| `sm_data` | `V015__apply_attempt_preimage_change.sql` (the lane's placeholder `V016`, renumbered at the wave-2 merge, 2026-10-06) | **OR-108 (1)** | `apply_attempt.pre_image_change` with `pre_image_change_shape` and `pre_image_change_only_for_rollback_steps`. Took the next free number under the allocation rule below |
| `sm_data` | `V016__metric_definitions_and_endpoints.sql` (the lane's placeholder `V021`, renumbered at the wave-2 merge, 2026-10-06) | **plan 011 P11-2.2** | the five metric-definition tables, `placement_target_key` and three trigger functions; next free number after OR-108's `V015` |

⚠️ **No migration widens a hash grammar.** OR-32's keyed hash is spelt `hmacsha256v<keyVersion>:<64 hex>` (T2.3
§5), which already matches `ValueGrammar.HASH` `^[a-z0-9]+:[0-9a-f]{16,128}$` (`ValueGrammar.java:35`, enforced by
`AttemptRow.java:39` and `ReplicaObservation.java:26`) and V002's `resolution_is_evidence` element predicate
(`V002__apply_request.sql:103-106`). A format that fails either — e.g. `hmac-sha256:<id>:<hex>`, which has a `-`
and a second `:` — would need a new `sm_data` migration **and** a model-grammar change; none is allocated.

> **Allocation rule changed 2026-10-06 (integrator, before S6/S7's first push).** The S6+S7 review found that OR-96's
> `V017` would have merged ahead of V013-V016 with Flyway's `outOfOrder` off. It is renumbered **V013**
> (`V013__designer_layout.sql`), contiguous with S6's V011 and S7's V012. **Every unmerged `sm_data` migration now takes
> the next free number when it merges** — plan 010 T9.3 and T11.2, plan 011 P11-2.2 and later, plan 008 — so versions
> always reach a database in ascending order. The fixed reservations above (T9.3 V013, T11.2 V014, plan 011 V015/V016,
> plan 008 V018+) are superseded by this rule; their text is kept as history.

⚠️ **Renumbered 2026-09-29, before any of these was written.** T6.8 (the indeterminate reconciler, owner ruling
2026-09-29 (verify-then-retry)) merges in W8b, before T7.2 (W10). W11's two conditional or late migrations merge
**T9.3 (V013) before T11.2 (V014)**; if D-LOG decides V013 is not needed, the gap is harmless. Because T6.8 merges
before T7.2, it takes `V011` and the three later DP migrations move up one (`placement_health` V012, `log_level_operation` V013, `fleet_action` V014) — version order
stays merge order. Plan 008's migrations therefore start at **V015** (T0.3 Step 2). `sm_control` numbering is
unchanged (`V013` is T2.0's, a different schema).

⚠️ **Out-of-order merges.** Parallel tasks may merge `V010` before `V009`. A fresh Testcontainers database applies
whatever exists in version order, so tests are unaffected; a **persistent** development database that already
applied a higher version will refuse a lower one if Flyway's `outOfOrder` is off (M9). Rule: merge DP migration
tasks **in version order**; if that is impossible, the integrator recreates the dev `sm_data` (pre-production — no
customer database exists) and records it.

---

## Single-owner files — who may edit what

| File | Owner | Everyone else |
|---|---|---|
| `contracts/openapi/console.yaml`, `contracts/openapi/validate-openapi.py`, `ENDPOINT-INVENTORY.md` §1–§3 | **T0.1** (including the fleet rows T0.0 designs); later **T10.3** *only if* D-ACCEPT needs a change beyond point 18 (W10, no other editor in W10–W11); inventory "served" rows in **T12.1** | read only; propose a change to the owner |
| `contracts/events/**`, `mesh-events/.../NotifyEventShapes.java`, `NotifyTopics.java` | **T0.2** | read only |
| `contracts/solution-package.schema.json`, `contracts/validation/**` (fixtures, `overlap/`, negatives and `negative/generate.py`, `validate-package.py`), `contracts/run-checks.sh` steps 1–3, `contracts/view-models/src/types.ts` (only if affected) | **T0.1a** | read only — T3.1's Java validator and the landed Java `project()` port consume them. ⛔ `contracts/view-models/fixtures/**` has **no** owner in this plan: its 33 fixtures are OR-34's lock, and a change to it is routed as its own reviewed contract change (T1.2) |
| `dp-core/.../apply/persistence/TenantEnumerationRepository.java` | **T0.3** (javadoc only, W0), then **T2.4 Step 3b** (the second tenant list, owner ruling 2026-09-29 (Q-T)) | read only; T7.2 and T11.2 call the method, never re-spell its SQL |
| `docs/design/DECISIONS.md` | the **integrator**, at merge (allocates ids in merge order) | draft in the task's own design section with `«next free BE-D»` |
| `cp-api/.../authz/ConsoleOperations.java` | **T0.1** | read only |
| `console-gateway/.../http/{OperationPolicy,OperationClassification,AuthenticationInterceptor}.java` | **T1.3** | read only |
| `dp-core/src/test/.../persistence/SmDataBootstrapIT.java` | **T2.4** (W2). T2.2's sweep does not touch it — its `execute(` matches are SQL, not admissions (measured, `SmDataBootstrapIT.java:87-235`) | read only |
| `launcher/src/test/.../TwoSchemaTestDatabase.java` (shared IT properties) | **T2.6** (test master key), then **T6.2** (the production-executor switch for the seven ITs that bring their own executor) | read only |
| `dp-core/.../dp/config/ConfigRecordServiceImpl.java` | **T5.2**; then, in merge order, **T8.1 → T10.2 → T9.4** (W10–W11 sequence them; never two in one wave) | rebase on the previous owner |
| `sm_control/V013__…sql`, `CHECKSUMS.sha256`, `ControlPlanePersistenceConfiguration.java` readiness lists | **T2.0** | read only |
| `launcher/.../SolutionManagerConfiguration.java` | **T2.6 only** (swap to the two plane aggregators). Every later bean reaches production through an aggregator | never |
| `dp-core/.../DataPlaneConfiguration.java` (aggregator, created by T2.6) | each DP task adds **one `@Import` line** | rebase-merge; conflicts are one line |
| `cp-core/.../ControlPlaneConfiguration.java` (existing aggregator) | each CP task adds **one `@Import` line** | rebase-merge; conflicts are one line |
| `backend/*/pom.xml` | the task that needs the dependency, **named in its Files block** | never "on the way past" |
| `arch-tests/.../PlaneBoundaryTest.java`, `CompositionBoundaryTest.java` | **T6.7** (new rules); inventory sets only when an implementation is added | read only |
| `dp-core/.../apply/engine/ApplyExecutor.java` | in merge order **T6.4 (W8) → T6.9 (W8a) → T6.8 (W8b) → T9.3 (W11) → T11.2 (W11)**; never two in one wave, and T9.3 and T11.2, both W11, are **sequential for this file** (T9.3 first, as V013 precedes V014) | rebase on the previous owner |
| `dp-core/.../apply/engine/OutcomeService.java` | in merge order **T6.4 (W8) → T6.5 (W8a) → T6.8 (W8b)** | rebase on the previous owner |
| `dp-core/.../apply/runtime/RuntimeAdapter.java` (the seam) and its contract kit | **T6.8** only: one new conditional-write operation (M-4), with its `«next free BE-D»` | read only |
| `backend/pom.xml` build plugins | **T0.4b** (the licence gate) | never |

**Readiness checks are per configuration, not shared.** Each new DP persistence area declares its **own**
`InitializingBean` readiness check for its **own** tables in its **own** `@Configuration` (the pattern of
`ApplyPersistenceConfiguration.java:48-90`), so no task edits `ApplyPersistenceConfiguration`'s `EXPECTED_TABLES`
(`:54`).

---

## Dispatch map — what may run in parallel

**A "wave" is a set of tasks whose files are disjoint and whose dependencies are merged.** Tasks in one wave may be
dispatched to separate agents, each in its **own git worktree** branched from the integration tip at the
wave's start (`git worktree add ../p010-<task> -b p010/<task> integration/dp-remote-demo`).

**Execution model — owner ruling 2026-10-05 (`OR-70`; supersedes the earlier "review and merge each task" sentence).**
- **Sonnet 5.5 (high)** implements each task; the orchestrator keeps its own context clean and dispatches.
- Independent tasks run **in parallel** per this map: **S1 beside S2**, and **S8, S9 and S10 beside each other** (W10 and W11 already
  list them together). The merge order inside a wave and the single-owner file rules below still hold.
- **Inside a task** run only the tests that task needs: unit tests and targeted ITs (`-Dit.test=…`), plus the task's own mutation
  proofs. Commit when the task's own checks are green. **No full reactor build per task.**
- **At the end of each slice**, run the **full build once**: `mvn -o -f backend/pom.xml clean verify` (with the Testcontainers
  override, below), `VERIFY arch-tests` and `./contracts/run-checks.sh --strict`.
- **One Opus review per slice** (of the slice's whole diff, after that build is green), then **one fix round** by Sonnet 5.5 (high) —
  not a review per task, and not a second review round of the same slice.
- **One combined Fable 5.1 review after S12**, over all slices, which **replaces T12.1 Step 6's independent review**.
- Testcontainers runs are handed out one at a time.

| Wave | Tasks (parallel within a wave) | Sequential constraints inside the wave |
|---|---|---|
| **W0** | **T0.0 → T0.1 (Steps 1–6, the remaining delta) → T0.1a → T0.4a (licence audit, report only) → T0.1 Step 7 (owner sitting) → T0.2 → T0.3** | ⛔ **sequential, one agent**. In order: the fleet design (OR-47: *before the apply slice*); the remaining single-writer contract delta; the package-schema field (owner ruling 2026-09-29 (S0-10)); the measured licence report (`REQ-014`), which the owner needs in hand before deciding Q-L1/Q-L2; **the one owner sitting** (T0.1 Step 7 — the next gate of this plan, which also takes the [second review's questions](#owner-questions-raised-by-the-second-review--answered-at-t01-step-7)); the event contract; the central register. The licence **gate** (T0.4b) is **not** in W0: it blocks only the first new dependency (T3.0), so it opens W1's T3.0 agent. ✅ Already landed before W0 opens: T0.1's partial `console.yaml` slice (`9ec6797`) |
| **W1** | **T1.1 → T1.2 remainder** (the port landed early; what is left is the shape-mirror test, the byte-level check and the Spring wiring — T1.1/T1.2 list it) · **T1.3** (reach-filtered list classification) · **T2.0** (sm_control V013) · **T2.1** (sm_data V007) · **T2.3** (D-KEY design, docs only) · **T0.4b → T3.0 → T3.1** (the licence gate, then the validator — the gate lands before the dependency it checks) | arrows = same agent. T0.4b needs Q-L1/Q-L2 answered (T0.1 Step 7). T1.2 and T1.3 each add one `@Import` line to `ControlPlaneConfiguration` — rebase-merge, T1.2 first. T1.3 owns the gateway classification files; T2.0 owns `cp.persistence` readiness + V013 |
| **W2** | **T1.2 remainder** (if not finished in W1) · **T2.2** (admission guard + V008) · **T2.4** (secret store, V009, **including Step 3b** — the second tenant list and `KeyRotationMain`, no longer gated) · **T3.2** (catalog store + load/list endpoints + `EntitlementMain`, no longer gated) | T2.4 needs T2.1 + T2.3 merged; T3.2 needs T2.0 + T3.1 + T1.3. ⚠️ **T5.1 is not in W2** (M13): its `RenderedConfig.of` change touches 23 test files (measured), most of them among the 35 T2.2 sweeps — so T5.1 waits for T2.2's merge |
| **W3** | **T2.5 → T2.6** (DP inventory, then the aggregator swap) · **T5.1 → T5.2** (keyed hash, then config_generation V010 — same agent: T5.2 hashes with T5.1's `ConfigHasher`) · **T4.1** (declare protocol record, docs only) | T5.1 needs T2.2 + T2.3 merged. Disjoint: `dp.inventory` + launcher composition vs `dp.apply.runtime` + the engine ITs + demo files, then `dp.config` |
| **W4** | **T2.7** (CP targets + listTargets + the two target commands) · **T3.3** (CatalogEventEmitter + production mesh wiring) · **T4.2** (DP declare phases + tenant-scoped sweep) · **T4.3** (CP governance registration) | T2.7 and T3.3 each add one line to `ControlPlaneConfiguration`; T3.3 also one line to `DataPlaneConfiguration` — rebase-merge, T2.7 first. T4.2 needs T2.5 + T4.1; T4.3 needs T2.0 + T3.2 + T4.1 |
| **W5** | **T4.4** (declare/list/get endpoints) · **T6.1** (transport: credential by reference, in-memory key, bounded probe, demo kept compiling) | T4.4 composes T2.7, T4.2, T4.3; T6.1 owns `dp.apply.runtime.ssh` + the demo files it names, and needs T2.4 + T5.1 |
| **W6** | **T5.3** (designer/stage/history/ladder-partial endpoints) · **T6.2** (`TablePlacementCatalog` + production adapter + executor wiring) | T5.3 needs T1.2, T4.4, T5.1, T5.2; T6.2 needs T2.5, T2.6, T5.2, T6.1 |
| **W7** | **T6.3** (apply/observe/stop endpoints; `rolling-health-gated` admitted, fails closed until T6.9) | alone |
| **W8** | **T6.4** (apply-outcome + rollout + config-state emission) · **T6.7** (one-apply-path arch rules) | disjoint: `dp.apply.events` + the engine call sites vs `arch-tests` only |
| **W8a** | **T6.9 → T6.5** (same agent, sequential): `rolling-health-gated` gated on the package-declared probe, with the post-restart read-back `VERIFY` added to `ApplyExecutor` and the proved strength reported; then the ladder's `persisted`/`restart-observed` rungs, which consume T6.9's read-back | after T6.4 merges. T6.9 edits `ApplyExecutor` (T6.4 did too, in W8) and T6.5 edits `OutcomeService` (T6.4 did too), so neither may run beside T6.4 (M-7) |
| **W8b** | **T6.8** (verify-then-retry reconciler for `APPLY_INDETERMINATE`, sm_data V011) | alone — it needs T6.4's emitter (the give-up publishes `instance.apply-outcome`), T6.9's gate (the restart-only row is gated as its strategy requires) and edits `ApplyExecutor`, `OutcomeService` and the `RuntimeAdapter` seam |
| **W9** | **T6.6** (first E2E walkthrough + resilience scenarios, incl. one reconciliation scenario) | alone — the OR-46 gate; needs T6.8 merged |
| **W10** | **T7.1** (session pool) · **T7.2** (health, V012 — over the second tenant list, no longer gated) · **T8.1** (rollback) · **T9.1** (readLogs) · **T9.2** (D-LOG, docs) · **T10.1** (secrets) · **T10.3** (D-ACCEPT, docs) | T7.2 follows T7.1 in one agent. ⚠️ T10.1 and T8.1 both extend the `dp-api` `ConfigRecordService` interface in **different** methods — T8.1 merges first, T10.1 rebases; only T8.1 edits `ConfigRecordServiceImpl` in W10 (T10.1's implementation is its own new class) |
| **W11** | **T9.3** (live log levels) · **T10.2 → T9.4** (restore-runtime, then log-level-as-config — **same agent, sequential**: both edit `ConfigRecordServiceImpl`) · **T10.4** (accept-runtime) · **T10.5** (home) · **T11.2** (fleet, V014 — batch advancement over the second tenant list, no longer gated) | T9.3 needs T9.2; T9.4 needs T9.3 merged; T10.2 needs T8.1; T10.4 needs T10.3; T10.5 needs T7.2; T11.2 needs T0.0/T0.1 and T6.3. **Migration merge order: T9.3 (V013, if D-LOG needs it) before T11.2 (V014)** — the version-order rule. ⚠️ **`ApplyExecutor.java` too:** T9.3 and T11.2 both edit it (T11.2's `admit(command, id)` overload, `fleet-apply.md` §11 item 2), so for that file they are sequential, T9.3 first |
| **W12** | **T12.1** (inventory, parity, Results, review) | alone |

*(Added by the plan 011 integrator hand-off, `OR-93`: from plan 011's P11-2.1 merge, `SmDataMigrationMain.runAll` also migrates `sm_metrics`, so each later plan 010 migration task (T6.8 `V011`, T7.2 `V012`, T9.3 `V013`, T11.2 `V014`) rebases its `SmDataBootstrapIT` hunk onto P11-2.1's and every launcher IT runs the `sm_metrics` readiness check. From P11-1.1's merge the Cache fixture carries `restartSignal`, `queryParams` and `defaultChart` (additive).)*

**Critical path to the first E2E slice (OR-46):** T0.0 → T0.1 (remaining delta) → T0.1a → T0.4a → **T0.1 Step 7
(owner sitting)** → T0.2 → T0.3 → [W1] T2.1 → T2.2 → T2.5 → T2.6 → T4.1 → T4.2 → T4.4 → T5.2 → T5.3 ∥ (T5.1 → T6.1 →
T6.2) → T6.3 → T6.4 → T6.9 → T6.5 → **T6.8** → T6.6. Two feeder chains join it. First, **T0.4b → T3.0 → T3.1 → T3.2
→ T4.3 → T4.4**: a declare needs an entitlement row from `EntitlementMain`, and the licence gate precedes the first
new dependency. Second, **T3.3 → T6.4**: the production mesh wiring the emitter publishes through.

**What gates execution now.** ✅ **The owner's T0.1 Step 7 sitting is complete** (2026-09-30, licence batch closed 2026-10-05; `OR-60`
to `OR-68`): every question below is answered, and the tasks they gated (T0.1's text, T0.4b, T6.8, T6.9, T11.2 and the S8/S10 shapes) are
edited to match. What remains before W1 is T0.2 and T0.3 (and the integrator's re-run of the checks). The paragraph below is what the
sitting covered, kept for the record. **W0 can start.** Q-E, Q-T and Q-R are ruled, and so is every S0 design point (owner
rulings 2026-09-29). The sitting covered T0.1's remaining
`console.yaml` delta: the landed slice's four recorded divergences (points 12, 16, 17 and 19), point 18's added
`placementId`, and the §6.1/OR-55 and `I5` corrections to rows 9, 11, 20 and 21. It also covers T0.1a's
probe-strength schema field, T0.0's written fleet design, T0.4a's measured licence report, and the
[second review's owner questions](#owner-questions-raised-by-the-second-review--answered-at-t01-step-7). Of those
questions, only **Q-L1/Q-L2** (T0.4b) and **Q-C1–Q-C3** (T0.1's text) gate anything before W8. **Q-R1–Q-R4** and
**Q-G1/Q-G2** gate only T6.8 and T6.9, but they are put in the same sitting so that execution does not stop again at
W8. Everything else waits only on its listed dependencies and on its own Measured-facts row.

---

## File structure (new code)

| Path | Responsibility |
|---|---|
| `cp-api/.../cp/api/designer/*` ✅ **landed early** | `DesignerProjector`, `DesignerStageValidator` + `PackageModel`/`StateModel`/`ViewModel` (a Java mirror of `contracts/view-models/src/types.ts`) — the landed names replace this plan's earlier `projection` package and `ConfigProjectionService` |
| `cp-core/.../cp/designer/*` ✅ **landed early** | `DesignerProjectorImpl` (the port of `project.ts`, OR-34), `DesignerStageValidatorImpl`, `PackageJsonReader`, `ViewModelJson`; the Spring wiring (`DesignerConfiguration`) is T1.2's remainder |
| `cp-api/.../cp/api/catalog/*` · `cp-core/.../cp/catalog/*` | Catalog service, package validator (`catalog.validation`), entitlement |
| `cp-api/.../cp/api/target/*` · `cp-core/.../cp/target/*` | Target governance (OR-28) |
| `cp-api/.../cp/api/governance/*` · `cp-core/.../cp/governance/*` | Instance governance registration (OR-27, OR-41) |
| `cp-core/.../cp/events/CatalogEventEmitter.java`, `CpTransactionBoundary.java` | plan 007 Task 5a |
| `cp-core/.../cp/persistence/Jdbc*.java` | every new CP repository (JDBC confinement) |
| `dp-api/.../dp/api/{inventory,config,apply,health,logs,fleet,home}/*` | DP service interfaces and plain value records — **no dependencies** (`dp-api/pom.xml:15`) |
| `dp-core/.../dp/inventory/*` | instance/placement/target-connection services |
| `dp-core/.../dp/secret/*` | envelope encryption, `MasterKeyProvider`, `SecretResolver` |
| `dp-core/.../dp/config/*` | config-of-record, `ConfigGenerationRenderedConfigSource`, rollback/restore copy-forward |
| `dp-core/.../dp/apply/production/ProductionApplyConfiguration.java` | the production `RuntimeAdapter` + `RenderedConfigSource` + `ApplyExecutor` beans |
| `dp-core/.../dp/apply/events/ApplyEventEmitter.java` | plan 007 Task 5 + OR-4/OR-46 |
| `dp-core/.../dp/apply/reconcile/*` | the verify-then-retry reconciler for `APPLY_INDETERMINATE` (T6.8, owner ruling 2026-09-29 (verify-then-retry)) |
| `dp-core/.../dp/health/*` | the poller (OR-39) |
| `dp-core/.../dp/apply/persistence/Jdbc*.java` | every new DP repository |
| `dp-core/.../dp/DataPlaneConfiguration.java` | the DP aggregator the launcher imports (T2.6) |
| `console-gateway/.../gateway/http/*Controller.java`, `*Resolver.java` | thin controllers + `ResourceResolver`s |
| `cp-core/.../cp/persistence/{TargetRegistrationMain,EntitlementMain}.java` | CP operator commands (standalone `main`s, `SmControlLockoutRecoveryMain`'s shape) |
| `dp-core/.../dp/apply/persistence/{TargetConnectionMain,KeyRotationMain}.java` | DP operator commands (same shape; never boot a Spring context) |
| `cp-api/.../cp/api/authz/ReachAuthorization.java` · `cp-core/.../cp/authz/GrantReachAuthorization.java` | the reach-filtered list classification (T1.3) |
| `launcher/src/test/.../*EndpointsIT.java` | the production-boot proof per slice |

---
## S0 — Contracts and records

### Task T0.0 — D-FLEET: the fleet-apply design, before the apply slice (REQ-013, OR-47) — docs only

**Wave:** W0, first. **Depends on:** nothing. **Parallel:** ⛔ none (it feeds T0.1).

**Why here, not after S6 (B3):** OR-47 and `REQ-013-fleet-apply.md` ("Trace": *"needs a design before the apply
slice"*) put the design **before** the apply slice, and its choices constrain the apply contract T0.1 fixes (point
10's `submitApply`, point 4's `observeApply`) and T2.2's journal shape. Designing it after S6 would let the single-apply
contract harden first.

**Files:** Create `docs/design/backend/fleet-apply.md`; draft `«next free BE-D»` rows. **No contract edit** — the
contract rows it designs are transcribed by T0.1 (point 25), in the same one reviewed change.

REQ-013's acceptance, each answered with the concrete delta and one scenario run through every option (`cache-1`
and `cache-2` in environment `prod-us`, `cache-eu` in `prod-eu`; the operator holds `config.apply` at `prod-us` only):

1. **Authorization shape — the gateway constraint, measured.** A CATALOG operation's resource is resolved from the
   path before the handler runs, and exactly one resource is `require`d (`AuthenticationInterceptor.java:131-158`;
   `ResourceResolver` javadoc: path, query or session only, never the body). A fleet's members span environments, so
   **no single path resource authorizes a fleet**, and `OperationClassification` is sealed at four kinds
   (`OperationClassification.java`). Options: **(a)** the reach-filtered classification T1.3 builds — pre-handler:
   `config.apply` held at ≥ 1 grant in the session's organization (else `403`); in the handler, one `require` per
   member at its own instance (§5.6 rule 1: still the one enforcement point, as declare's secondary verbs are).
   Scenario: `cache-1`/`cache-2` authorized, `cache-eu` `404`-shaped refusal reported by member. **(b)** one request
   per environment (`POST /dp/environments/{environmentId}/fleet-apply`): the existing single-resource path works,
   but the scenario needs two actions and one fleet id cannot trace all three members — fails REQ-013 bullet 2.
   ✅ **Ruled (a)** — owner ruling 2026-09-29 (fleet authorization), `OR-57`: a reach-filtered pre-check, then
   one `require` per member at its own instance. The design records (b) as considered and rejected.
2. **Per-member refusal reporting** — (a) refuse the whole action with the refused list (nothing admitted); (b) admit
   the authorized members and report the refused. ✅ **Ruled as recommended** — owner ruling 2026-09-29 (fleet
   authorization): by default the whole action is refused, listing the refused members (nothing applied); an explicit
   `proceedWithAuthorized: true` applies the authorized members and reports the refused. Never a silent partial
   action.
3. **Own request, journal and audit line per member**, one traceable fleet id: `fleet_action(fleet_id, …)` +
   `fleet_member(fleet_id, instance_id, placement_id, generation, apply_id | refusal)`; `apply_request` gains
   nothing (the member row points at it), so the journal's identity and T2.2's shape are untouched. `fleet_action`
   is an owning row stamped from `state_version_seq` (OR-35) under its own row lock (m8).
4. **Partial failure, ordering, batching** — ordered members, `batchSize`, `onFailure: continue | halt-remaining`;
   the next batch is admitted only when every member of the current one is terminal; each member is observable by
   its own `applyId`, and the fleet observation lists every member. **Batch advancement needs a tenant source when
   no member is in flight** — ✅ ruled: the second tenant list, owner ruling 2026-09-29 (Q-T) (T2.4 Step 3b writes
   it; T11.2 uses it).
5. **Member generations** — each member's generation is staged through the normal stage path (OR-34 re-projection)
   **before** the fleet is submitted; the fleet request names `(instanceId, placementId, generation)` per member. A
   fleet never copies forward inside the submit transaction (Global Constraints: lock order).
6. The contract rows T0.1 transcribes: `POST /dp/fleet-applies` (`x-sm-permission: config.apply`,
   `x-sm-permission-also: [instance.restart]`, `x-sm-scope: reach`, per-member `require` in the handler) and
   `GET /dp/fleet-applies/{fleetId}` (`config.view`, `x-sm-scope: reach`, members filtered by `config.view`). ⛔ No
   new verb (the catalog is closed at 38).

- [ ] **Step 1:** write the design, every option with its delta and the scenario, and **record the two rulings**
  (points 1 and 2) as decided, citing the label. **Step 2:** links resolve.
  **Step 3:** owner review of the written design is T0.1 Step 7's (one sitting) — a check that the text says what was
  ruled, not a decision. **Step 4: Commit.** `docs(backend): D-FLEET — fleet apply designed before the apply slice (REQ-013, OR-47)`

**Results (T0.0, 2026-09-29, at `466e560`).** Steps 1–2 done; the box stays open until Step 3 (the owner sitting) and
Step 4 (the integrator's commit). Written: [`fleet-apply.md`](../../design/backend/fleet-apply.md), with points 1–2
recorded as ruled (`OR-57`), three decision drafts (`«next free BE-D»` F-1 to F-3, §9), and nine new owner questions
**Q-F1 to Q-F9** (§10) for the T0.1 Step 7 sitting. Q-F4 (defaults, `maxItems`), Q-F5 (the refuse-whole code), Q-F6
(partial visibility) and Q-F8 (whether `refused` members are re-shown after the `202`) change T0.1's text; the rest
gate only T11.2. Deltas for the integrator are in the design's §11: the operationIds
`submitFleetApply`/`observeFleetApply`; if §4.2 (a) is accepted, `AdmissionService` + `AdmissionOutcome` +
`ApplyExecutor` + `AdmissionResult` join T11.2's Files block (a pre-minted `apply_id` per member through an
`admit(ApplyCommand, ApplyId)` overload, looked up under the scope lock **before** the blocking check, answering
`AlreadyAdmitted`), and `ApplyExecutor.java`'s ownership row gains *→ T9.3 → T11.2* (both W11, both edit it); one `FleetScheduler` thread under `WorkerSingletonGuard`
is the only advancer; and **`eachMemberIsAuthorizedByItsOwnRequire()`'s description needs rewording** (it contradicts
OR-57's refuse-whole default, §11 item 5). **Review round 1 applied** (2 MAJOR, 3 MINOR): the "correctness does not
depend on one scheduler" claim was false and is withdrawn (§5.5); the refused-member visibility contradiction is
Q-F8; `applyId` is shown only once `admitted`; same-batch `halt-remaining` is Q-F9. **Review round 2 applied** (2 MAJOR, 4 MINOR): the concurrent-advance test's mutation proofs could not fire (the two
defences cover each other) and is withdrawn, each defence now proven at its own boundary (§5.5, §11 item 2); the
engine delta now names `ApplyExecutor`'s two casts, `AdmissionResult`, the 56 `ApplyCommand` sites and the T9.3
ownership conflict (§4.2); Q-F8's quote re-attributed to `REQ-013`; T0.1 point 25's stale "T0.0 §6" pointer
flagged (§11 item 1); "settled" vs the plan's "terminal" flagged as a departure in Q-F2 and §11 item 5; refused
rows' unvalidated ids stated (§4.3). Links: 18 relative links checked, 0 missing;
`./contracts/run-checks.sh --strict` green (unchanged, docs only).

### Task T0.1 — One reviewed `console.yaml` change: OR-36, OR-37, OR-38, OR-45, and the shapes the rulings imply — **the remaining delta**

**Wave:** W0, after T0.0. **Depends on:** T0.0 (the fleet rows and the reach classification's fleet use). **Parallel:** ⛔ none — single-writer contract.

⚠️ **Part of this change has already landed** (the integrator's early slice, 2026-09-29, commit **`9ec6797`** — in
`HEAD`'s history; it was first measured here as an uncommitted diff over `3cd0407` in
`contracts/openapi/{console.yaml,ENDPOINT-INVENTORY.md,validate-openapi.py}`,
`cp-api/.../authz/ConsoleOperations.java`, `console-gateway/.../{ScopeNodeList,HierarchyController}.java`,
`HierarchyControllerTest.java` and `launcher/.../HierarchyEndpointsIT.java`). **This task is now only what that slice
did not do.** ⛔ It does not re-edit the landed parts except where a row below says so, and every such row is a
recorded **divergence** between the landed slice and this plan's earlier point — presented at Step 7, never silently
reverted in either direction.

**Files (remaining):**
- Modify: `contracts/openapi/console.yaml`, `contracts/openapi/validate-openapi.py`, `contracts/openapi/ENDPOINT-INVENTORY.md`
- Modify: `backend/cp-api/src/main/java/com/solace/sm/cp/api/authz/ConsoleOperations.java` (the two fleet operationIds
  only — the five OR-45 ones are ✅ landed; `ConsoleOperationsMirrorTest` fails otherwise)
- Modify: `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/ErrorCode.java` (the four new codes;
  `SchemaMirrorLockTest.errorCodeEnumMatchesTheOpenApiErrorCodeSchemaExactly` fails otherwise)
- Modify tests: `cp-api/.../ConsoleOperationsMirrorTest.java` (only if it needs the fleet ids named),
  `console-gateway/.../SchemaMirrorLockTest.java` (only if Step 6(c) shows the landed `ScopeNodeList` change is not
  already caught — see point 2)
- ✅ Landed, **not** to be edited again: `ScopeNodeList.java`, `HierarchyController.java` (`STATIC_STATE_VERSION`
  deleted), `HierarchyControllerTest.java`, `HierarchyEndpointsIT.java` (both assert `stateVersion` is absent)

**Interfaces produced:** the contract every later task implements. **Every design point in this change is now ruled**
(owner rulings 2026-09-29 — S0-8, S0-10, S0-11, S0-15, S0-22, S0-24, fleet authorization); the owner's review at
Step 7 checked the text says what was ruled and **decided** the **four** landed-slice divergences (points 12, 16, 17,
19), point 18's added `placementId` (an addition, not a divergence), and the second review's contract questions
(Q-C1–Q-C3) — all as recommended (`OR-60` to `OR-62`). A point the owner changes is changed here, before merge — never re-decided in a later task.

**The change, point by point — landed vs remaining** (landed = measured in the working tree, 2026-09-29; "today"
line numbers of `3cd0407` are superseded by the landed file — re-measure before editing):

| # | Operation / schema | ✅ Landed in the early slice | ⏳ Remaining in this task | Basis |
|---|---|---|---|---|
| 1 | `ErrorCode` | nothing — still 13 codes | add `CONFLICT` (409: stale `baseStateVersion`, admission conflict, placement collision), `VALIDATION_FAILED` (422: package gate, projection mismatch, undeclared key, blocking validation, envelope refusal), `ENTITLEMENT_REFUSED` (409: not entitled / quota exhausted — ⚠️ **not 403**: §5.6 rule 3 makes 403 mean "verb not held"), `NOT_IMPLEMENTED` (501: §5.6 rule 5); the Java `ErrorCode` enum | §5.6; OR-40 |
| 2 | `ScopeNodeList`, `TargetList`, `LogPage`, `LogLevel` | ✅ **done**: a new `AvailabilityOnly` schema (the `Versioned` envelope minus `stateVersion`) replaces `Versioned` on all four; the three served hierarchy reads omit their constant `1`; Java record, controller and both tests changed | nothing in the YAML. Step 6(c) runs the mutation against the landed code; only if `SchemaMirrorLockTest.scopeNodeAndScopeNodeListMatchTheirRequirednessExactly` stays green under it is an explicit `scopeNodeListCarriesNoStateVersion()` added | **OR-38** |
| 3 | `ApplyObservation.state` | ✅ the 12 `RequestState` values verbatim, with a description naming the three open states | add `operation`, `placementId`, `generation`, `requestedAt`, `terminal` (boolean, `RequestState.isTerminal()`), and optional `reconcileReason` (present only while a request stays `APPLY_INDETERMINATE` after T6.8's reconciler gave up: `reconcile.foreign.content`, `reconcile.exhausted`, and the other `reconcile.*` classifiers T6.8 names; a classifier token, never file content — `I6`; read from `apply_request.reconcile_class`, V011); keep `steps`; each step item gains `gateProved` (point 26) | **OR-36**; OR-59 ("alerts") |
| 4 | `observeApply` | nothing — still one `ApplyObservation`, `sinceStateVersion` only | returns `ApplyObservationList {items[]}` (per-item `stateVersion`, §6.1); query `applyId` (optional), `sinceStateVersion` (optional, **strictly newer**, `console-integration.md` §6.1), `limit` 1–200 default 50; items ascending by `stateVersion`. ⚠️ `applyId` is what the browser's **stop** observation polls (T0.2's ruled stop exclusion: `observe?applyId=` every ~2 s until terminal) | §6.1; OR-35; owner ruling 2026-09-29 (T0.2) |
| 5 | `ConfigLadder.rungs[].rung` | ✅ the five rungs `[desired, rendered, persisted, restart-observed, effective-verified]` + the description mapping them to `types.ts`'s camelCase keys | add top-level `reached` (lowest rung across live placements) and optional `placements[] {placementId, reached}`; describe `configHash` as **keyed** (OR-32) | **OR-37**, **OR-32** |
| 6 | `Designer` | nothing | `Versioned` + `generation` (nullable) + `viewModel` = a new `ViewModel` schema transcribed from `contracts/view-models/src/types.ts:246-258` (and the types it references) | OR-34 |
| 7 | `stageConfig` body | nothing | `{baseStateVersion, componentSelection, draftValues, rendered}`; response `StagedConfig` + `unchanged` (boolean); `409 CONFLICT`, `422 VALIDATION_FAILED` | **OR-34**, **OR-33** guard 1 |
| 8 | `declareInstance` | nothing — still `POST /dp/solutions/{solutionId}/instances` | ✅ **ruled** (owner ruling 2026-09-29 (S0-8), `OR-52`): `POST /dp/environments/{environmentId}/instances`, body `{solutionId, name, targetId, declaredPackageVersion, placement, acquisition}`, `placement = {containerName, configDirectory, containerConfigPath, fileName, healthPort}`, and — ✅ **ruled** (owner rulings M-Q2 and M-Q2b, 2026-09-29; landed in T0.1 Step 8, 2026-10-05) — `acquisition = {mode: direct-http\|via-ssh}`: the per-instance acquisition mode, chosen by the operator (direct listed first, **no automatic fallback**), persisted at **instance** level, **with no host in the body** (✅ **ruled**, owner ruling 2026-10-05, `OR-82`: `direct-http` polls the **target's registered address**, `target_connection.host`, at the placement's `healthPort` and the package-declared path; nothing about the address comes from the browser); it **replaces** the per-placement `healthAcquisition` of this row's earlier text; `solutionId` = the **catalog** id (OR-41). **The body names host paths and a container only inside the target's envelope (B2)**, fixed at target registration (T2.7): anything else is `422 VALIDATION_FAILED` naming the field, never echoing the value — enforced by service, DB trigger and SFTP path check. `healthPort` is the host-side port (M12); the probe's `kind`, `path` **and `strength`** (T0.1a) come from the package's declared `health.probe`, resolved by the gateway at declare — never from the body. Why the path moves: `instance.create` is checked at the environment (OR-27) and the resource is resolved **before the body is read** (`ResourceResolver.java` javadoc). `x-sm-scope: environment`; `x-sm-permission-also: [instance.bindTarget, target.use]` checked in the handler. `409 CONFLICT` (placement collision, OR-29), `409 ENTITLEMENT_REFUSED` (quota, OR-40) | OR-27, OR-29, OR-40, OR-41 |
| 9 | `listInstances` | nothing — still `x-sm-scope: solution` | `InstanceList`'s envelope `Versioned` → `AvailabilityOnly`, and each `Instance` item carries its own `stateVersion` (`console-integration.md` §6.1: collections carry versions *"per item … never one version for the collection"*; OR-55). `{solutionId}` = **catalog** id; `x-sm-scope: reach` (point 24): `instance.view` held at ≥ 1 grant in the organization, then items filtered per instance by `instance.view` at each instance's own scope path (downward reach). ⚠️ Not `organization`: `require` at the organization refuses an environment-bound Operator `403` before filtering (`ScopeEvaluatingAuthorizationService.java:41-50`; `ScopeVisibility.java:6`) | OR-41; owner ruling 2026-09-29 (S0-24) |
| 10 | `submitApply` + `Strategy` | nothing — `placementId` still optional ("Absent means instance-wide"); `Strategy` enum `[manual, rolling-health-gated]` with a description that already calls both selectable | ✅ **ruled** (owner ruling 2026-09-29 (S0-10), `OR-53`) — **this point changed**: `placementId` **required** (v1 refuses instance-wide apply, `ApplyExecutor.java:155-156`, `BE-D23`). **`rolling-health-gated` is allowed** — ⛔ the earlier "→ `501` in this plan" is **withdrawn**: restart strategies are **generic and package-driven**; SM restarts and gates on the **package-declared** health probe, and the response and observation state **which strength the gate proved** (`readiness` = UP means serving; `liveness` = UP means the process came up — point 26, T0.1a). After the restart SM reads the config file back and verifies it with OR-32's keyed hash (ladder `persisted → restart-observed`). The `Strategy` description says all of that and names no solution. `restart-all` / `rolling-no-validation` / `scheduled` → `501 NOT_IMPLEMENTED` (the `Strategy` enum lists all five engine tokens, owner Step 7 `OR-60`; the server reads the value as a string first); `outageConfirmedBy` keeps its "required when `restart-all`" text, reachable only once `restart-all` is built. Open item 15 is answered **by declaration, not by MI development** (`I1`) | `BE-D23`; owner ruling 2026-09-29 (S0-10); open item 15 |
| 11 | `Health` | nothing — still `[HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN]` | ✅ **ruled** (owner ruling 2026-09-29 (S0-11), `OR-54`): the **notify vocabulary** (`GREEN, RED, RESTART_EXPECTED, INACTIVE_REPLICA, NOT_APPLICABLE` + NOTFOUND sub-states `unreachable, not_exposed, missing, bootstrap`); OR-39's "UNKNOWN, not DOWN" **is** the sub-state `unreachable`. Per-placement `{placementId, state, checkedAt, stateVersion}`, and **no** top-level `stateVersion`: the envelope becomes `AvailabilityOnly`. (The earlier "top-level = max of the items" is withdrawn. `instance.health` is placement-scoped and watermarked by `placementId`, per §6.1's watermark table, and OR-55 gives composed responses per-row versions only.) | OR-39, OR-37, OR-55 |
| 12 | `rollbackApply` body | ✅ `oneOf` exactly one of `applyId` / **`generation`** (the landed name — this plan's earlier `targetGeneration` is **dropped**, T8.1 follows the landed name), `strategy` required, `400`/`404` added, the OR-42 eligibility text | ✅ **ruled** (owner Step 7, batch 1, `OR-60`): `placementId` is **required**. The landed slice had it optional/nullable ("absent means instance-wide"); a rollback is an apply and v1 refuses instance-wide apply (`BE-D23`), so an absent `placementId` would always answer an error. Matches point 10 | **OR-42**; `BE-D23`; `OR-60` |
| 13 | `readLogs` | nothing | add `placementId` (required when more than one live placement), `before`, `after` (date-time); `LogPage` per point 2 | OR-38 |
| 14 | `getLogLevel` / `setLogLevel` | only point 2's envelope change. ⚠️ The landed inventory rule 8 now **states** "log-level changes return `202` as a bare `Versioned` — no `applyId`", which records the old YAML rather than fixing the inconsistency | `LogLevel {component, placementId, liveLevel?, configuredLevel?, runtimeOverride: boolean, availability}`; body `{placementId, level, mode: live\|config}` → `202 ApplyAccepted`; `mode: config` → `501` until T9.4; direct-HTTP-only + `mode: live` → `422` (OR-43: P2 only). Rewrite inventory rule 8 to match; check 12's expected `ApplyAccepted` set gains `setLogLevel` | **OR-43** |
| 15 | `GET /cp/home/summary` | contracted: `x-sm-permission: solution.view` + also `instance.view`, **`x-sm-scope: organization`**, `HomeSummary {solutionCount, instanceCount}` on `AvailabilityOnly` | ✅ **ruled** (owner ruling 2026-09-29 (S0-15), `OR-55`): `HomeSummary {solutions[] {solutionId, name, instanceCount, byHealth{state→count}, stateVersion}, availability}` — **no top-level `stateVersion`, per-solution-row versions**; `x-sm-scope: reach` (point 24; the landed `organization` would `403` an environment-bound Operator). ✅ **ruled** (owner Step 7, batch 2, Q-C1, `OR-61`): `instanceCount` is filtered **per instance** by `instance.view` at each instance's own scope, and `byHealth` counts only those instances where `health.view` is **also** held at that instance's scope. `byHealth` has **nine keys**: the five rollup keys plus `missing`, `unreachable`, `not_exposed`, `bootstrap`. The per-instance rollup is `project.ts` `healthRollupOf` **literally**, at placement scope, with the four NOTFOUND sub-states inserted: `NOT_APPLICABLE` if the placement has no probe; otherwise the first of any `RED`, `missing`, `unreachable`, `not_exposed`, `RESTART_EXPECTED`, `bootstrap`; if every replica is `INACTIVE_REPLICA`, `INACTIVE_REPLICA`; else `GREEN` | **OR-45**; §5.2 row 4 |
| 16 | `GET /cp/home/attention` | contracted: `health.view` + also `instance.view`, `x-sm-scope: organization`, items `{instanceId, solutionId, name, healthState ∈ [DEGRADED, UNHEALTHY, UNKNOWN]}`, **no per-item version** (its description argues none) | `x-sm-scope: reach` (S0-24); the S0-11 vocabulary (the landed enum is the retired REST health vocabulary); items `{instanceId, placementId?, reason: health\|apply-indeterminate\|config-diverged, state?, stateVersion}`. ✅ **ruled** (owner Step 7, batch 1, `OR-60`): **per-item `stateVersion`** (§6.1's collection rule); the landed description argued for none. ✅ **ruled** (batch 2, Q-C2, `OR-61`): each reason is filtered by the verb that guards its source (`health` → `health.view`; `apply-indeterminate`/`config-diverged` → `config.view`) | **OR-45**; §5.2 row 5; owner rulings 2026-09-29 (S0-11, S0-15, S0-24) |
| 17 | `POST …/config/secrets/{fieldKey}` | contracted: body `{value (writeOnly), baseStateVersion}` → **`200 StagedConfig`** (the call stores the value **and stages** a generation carrying its `secretRef`), `409` on a stale base | ✅ **ruled** (owner Step 7, batch 1, `OR-60`): **keep the landed shape (a)** — one call, `200 StagedConfig`; the version has a real source (the new generation) and no unreferenced secret can be stored; T10.1 stages a copy-forward of the latest draft with that field's reference replaced (origin `edit`) under the stage path's `baseStateVersion` check. The earlier two-call shape (`{value}` → `{secretRef, secretPresent}`, no `stateVersion`, m8) is **not** chosen | **OR-45**, **OR-30** |
| 18 | `POST …/config/accept-runtime` | contracted: `config.edit` + also `config.overrideWarning`, body `{baseStateVersion}` → `200 StagedConfig`, `409`, the D-ACCEPT caveat | add `placementId` (**required** — which placement's file is read back; ✅ **ruled**, owner Step 7, batch 1, `OR-60`) and `422` (read-back unparseable or carries a secret field; D-ACCEPT, T10.3, may narrow this only by a reviewed change) | **OR-45**; §5.3 row 42; `OR-60` |
| 19 | `POST …/config/restore-runtime` | contracted: body optional `{placementId?}` (nullable, "absent means instance-wide"), **"always the `manual` strategy: no restart; `instance.restart` never required"**, `202 ApplyAccepted`, `409`; check 12 already admits it | `placementId` **required** (`BE-D23`, as points 10/12). ✅ **ruled — the strategy** (owner Step 7, batch 1, `OR-60`): **(b)** `{placementId, strategy}`, `strategy` **present, default `manual`**; a **restarting strategy requires `instance.restart`** (`x-sm-permission-also`), so a restore can take effect with the same generic, package-driven strategies as an apply (S0-10). The `Strategy` enum lists **all five engine tokens**; the three unbuilt ones answer `501`. The landed manual-only shape is **not** kept | **OR-45**; §5.3 row 43; owner ruling 2026-09-29 (S0-10); `OR-60` |
| 20 | `Solution` / `listSolutions` | nothing — still `x-sm-scope: organization` | `{solutionId, solution, name, packageVersions[], latestPackageVersion, signature: unsigned, stateVersion}` (per item); `SolutionList`'s envelope `Versioned` → `AvailabilityOnly` (§6.1's collection rule; OR-55); `x-sm-scope: reach` with **no per-item filter** (S0-24). ⚠️ **No `entitled` field (m2)** | OR-40, OR-41 |
| 21 | `loadPackage` | nothing — the body still requires `organizationId` (`console.yaml:369-373`) | **remove `organizationId` from the body**: the organization is `Session.organizationId()` (`I5`: "never a request field"; Global Constraints), and the resolver never reads the body (`ResourceResolver` javadoc). A body naming another organization cannot then be accepted, silently or otherwise. `201 {solutionId, packageVersion, signature: unsigned, stateVersion}`; `422 VALIDATION_FAILED` names the gate; `409 ENTITLEMENT_REFUSED` | OR-40; `I5` |
| 22 | `Target` / `listTargets` | only `TargetList`'s envelope (point 2) | add `transport` (`ssh-docker`), `ownershipScope`, `allowedConfigRoot`, `allowedContainerPrefix` (read-only); `listTargets` `x-sm-scope: reach`, items filtered by `target.view` at each target's `ownershipScope`. ✅ **ruled** (owner ruling 2026-09-29 (S0-22), `OR-56`): downward rule kept; shared VMs are registered at the environment that uses them; an organization-owned target is usable only with organization-level `target.use`. ✅ **ruled** (owner Step 7, batch 2, Q-C3, `OR-62`): `transport` sits beside the existing `kind: [docker, kubernetes, vm]` (`console.yaml:1379-1386`); `kind` is the MI runtime, `transport` is how SM reaches it, and the description states v1's one pair (`ssh-docker` ⇒ `docker`) | OR-28; `D10` 🔒; `SM-D23` (**P**) |
| 23 | `Instance` | nothing | add `declaredPackageVersion`; `solutionId` = catalog id; `acquisition` (the same schema as row 8's, read-only; M-Q2/M-Q2b); `placements[] {placementId, targetId, containerName, probeStrength}` (point 26 — the per-placement `healthAcquisition` is gone, M-Q2b) | OR-27, OR-29 |
| 24 | `x-sm-scope: reach` | nothing — `validate-openapi.py` does not accept it, and the landed home operations are `organization` | ✅ **ruled** (owner ruling 2026-09-29 (S0-24), `OR-51`): the fifth classification — verb held at ≥ 1 grant in the session's organization (else `403`), then items filtered by the verb at each item's own scope (never navigation visibility, `ScopeVisibility` is `cp-core`). Applied to `listSolutions` (no item filter), `listInstances`, `listTargets`, both home reads and both fleet rows; `validate-openapi.py` accepts `reach` and T1.3's mirror test pins the set | M1; OR-45; OR-47 |
| 25 | `POST /dp/fleet-applies`, `GET /dp/fleet-applies/{fleetId}` | nothing; `ConsoleOperations` lacks both ids | transcribed from [`fleet-apply.md`](../../design/backend/fleet-apply.md) §7 (not "T0.0 §6": §6 there is member generations), `x-sm-scope: reach`, per-member `require`; the ruled defaults (`OR-65`, Q-F4): `members` `maxItems` **100**, `batchSize` default **1**, `onFailure` default **`halt-remaining`**; a refused fleet is always `403 FORBIDDEN` with `refusedMembers[]` (Q-F5); `observeFleetApply`'s members are filtered by `config.view` and the fleet is `404` when none is visible (Q-F6); refused members are not re-shown to other viewers (Q-F8); body carries `proceedWithAuthorized` (default `false`) — ✅ **ruled** (owner ruling 2026-09-29 (fleet authorization)): refused whole by default listing the refused members, never a silent partial action; `409`/`422` per T0.0 | **OR-47**, REQ-013 |
| 26 | **new** — the strength a gate proved | nothing | `ApplyObservation.steps[].gateProved: readiness \| liveness`. It is present only on a `rolling-health-gated` restart step whose gate **passed on the package-declared probe SM executed**, which in v1 means an HTTP kind (`actuator-health`, `http-status`). Its value is that probe's declared strength. It is absent under `manual`, absent when the gate's signal was anything else, and never derived from the declaration alone (M-6). A reconciler confirmation carries it only under Q-R2 (a). `Instance.placements[].probeStrength: readiness \| liveness \| none` is the placement's copy of the package **declaration** (T2.1), with `none` when the package declares no probe. It is a declaration, not a proof. With no probe SM executes (none declared, or only `broker-heartbeat`, which v1 does not run), `rolling-health-gated` fails closed before any write (`strategy.health.signal.absent`, `ApplyExecutor.java:556`), per Q-G1's recommendation (a). ⛔ Both enums mirror the schema's `strength` enum (plus `none` for `probeStrength`). `validate-openapi.py` (iv) pins that parity, so the vocabulary keeps one canonical home (`UI-D14`) | owner ruling 2026-09-29 (S0-10); T0.1a; Q-G1 |

`ENDPOINT-INVENTORY.md`: ✅ landed — the five OR-45 rows in §1, the count moved 25 → 30, the frontend
"regenerate types" warning, the rollback note, the accept-runtime caveat. ⏳ Remaining — the two fleet rows; the
count **recomputed from the YAML** (32 with point 25 — count, do not copy this sentence); the home rows' scope column
`organization` → reach; rule 8 per point 14; a line per landed-slice divergence the owner resolves at Step 7.

`validate-openapi.py`: ✅ landed — check 12's `ApplyAccepted` set gained `restoreRuntimeConfig`. ⏳ Remaining —
(i) accept `x-sm-scope: reach`; (ii) check 11: **every `expected` view model in
`contracts/view-models/fixtures/viewmodel/*.json` validates against `components.schemas.ViewModel`** (Python
`jsonschema` is already a declared dependency, `run-checks.sh` step 0); (iii) check 12's set gains `setLogLevel`
(point 14); (iv) **strength parity** (MINOR 3): `ApplyObservation.steps[].gateProved`'s enum equals
`solution-package.schema.json`'s `$defs/health/properties/probe/properties/strength` enum, and
`Instance.placements[].probeStrength`'s equals it plus `none`; (v) **no request body names `organizationId`**
(`I5`; row 21) — a check-11 assertion over every operation's `requestBody` schema.

- [ ] **Step 0: Measure the landed slice** (`9ec6797`). Run
  `./contracts/run-checks.sh --strict` on it and record green, and re-derive the "Landed" column above from
  `git show` — a row that no longer matches is corrected here before anything else is edited.
- [x] **Step 1: Write the failing checks first** (remaining ones only).
  - In `validate-openapi.py`, the fixture-conformance assertion (fails: no `ViewModel` schema) and the `reach`
    acceptance (fails once the first operation is marked `reach`).
  - `ConsoleOperationsMirrorTest` fails by itself once the YAML lists the two fleet operationIds.
  - `SchemaMirrorLockTest.errorCodeEnumMatchesTheOpenApiErrorCodeSchemaExactly` fails once the four codes are in the YAML.
- [x] **Step 2: Run and see them fail.** `./contracts/run-checks.sh --strict` (check 11 red) · `VERIFY cp-api` ·
  `VERIFY console-gateway`.
- [x] **Step 3: Edit `console.yaml` per the "Remaining" column** — one operation or schema at a time, re-running
  check 11 after each. The four divergences (points 12, 16, 17, 19) are written as **recommended**, and each is
  marked in the review copy so the owner sees the landed text beside it.
- [x] **Step 4: Update the Java mirrors.** `ConsoleOperations`: register the two fleet operationIds →
  `CONFIG_APPLY` / `CONFIG_VIEW` (spelt exactly as the YAML spells them; the five OR-45 ids are ✅ landed as
  `getHomeSummary`, `getHomeAttention`, `setConfigSecret`, `acceptRuntimeConfig`, `restoreRuntimeConfig` — later
  tasks use those spellings). `ErrorCode`: the four new codes.
- [x] **Step 5: Run green.** `./contracts/run-checks.sh --strict` · `VERIFY cp-api` · `VERIFY console-gateway` · `VERIFY launcher`.
- [x] **Step 6: Prove they fire.** (check (iv) / mutation (g) were closed by T0.1a — see its Results; 2026-10-05 note below.) (a) Change one fixture's `expected.engineVersion` to a string → check 11 red. (b)
  Add a sixth OR-45-style operation to the YAML without registering it → `ConsoleOperationsMirrorTest` red. (c)
  **Against the landed slice:** put `long stateVersion` back on `ScopeNodeList` → `HierarchyControllerTest`,
  `HierarchyEndpointsIT` and (report whether) `SchemaMirrorLockTest` go red. `9ec6797`'s commit message records
  that *"a mutation re-adding stateVersion turns both red"* (the mirror lock and the hierarchy IT). Cite it for those
  two, and re-run only for the `SchemaMirrorLockTest` question it does not answer. (d) Unregister one fleet operationId →
  `ConsoleOperationsMirrorTest` red. (e) Misspell `x-sm-scope: reach` as `reachable` → check 11 red. (f) Drop
  `setLogLevel` from check 12's set → red. (g) Add `startup` to `gateProved`'s enum only → check (iv) red. (h) Put
  `organizationId` back in `loadPackage`'s body → a new check-11 assertion red (no operation's request body may
  name an `organizationId`: `I5`). Revert each.
- [x] **Step 7: Owner review — the next gate of this plan** (**held 2026-09-30, batches 1–4, with the licence batch closed 2026-10-05; rulings recorded verbatim in the session's rulings file and registered as `OR-60` to `OR-81` by task S0-records, 2026-10-05, ahead of T0.3**) (one sitting, with T0.0's written design and T0.1a's
  schema field). Every design point is **already ruled** (2026-09-29); the review checks the diff says what was
  ruled, and decides the four landed-slice divergences (points 12, 16, 17, 19) and point 18's added `placementId`,
  each presented with its delta and a recommendation. **In the same sitting:** the §6.1/OR-55/`I5` corrections
  (rows 9, 11, 20, 21 — corrections to follow binding text, shown so the owner sees them, not new decisions), and
  **every** [second-review owner question](#owner-questions-raised-by-the-second-review--answered-at-t01-step-7)
  (Q-C1–Q-C3 change this diff; Q-L1/Q-L2 use T0.4a's report; Q-R1–Q-R4 and Q-G1/Q-G2 fix T6.8/T6.9 before they are
  dispatched). Each answer is recorded as an `OR-*` row by the integrator (next free id), and this plan's
  dependent task is edited to match in the same change. ⛔ A divergence the owner resolves the other way is changed
  here, and the dependent task (T8.1, T10.5, T10.1, T10.4, T10.2) is edited in the same change. **Outcome (2026-09-30, `OR-60`–`OR-62`): every divergence was resolved as recommended, and those five tasks were edited to match by task S0-records.**
- [x] **Step 8: Commit.** `contracts: the console contract for plan 010, remaining delta — observe list, designer view model, declare, reach-filtered lists, generic restart strategies, fleet`

**Results (T0.1 Steps 6–8 and T0.1a Steps 6–7, 2026-10-05, on `integration/dp-remote-demo`).** Step 7's
sitting is complete (batches 1–4 of 2026-09-30; licence batch closed 2026-10-05). (1) `console.yaml` was
checked against batches 1–2 (points 12, 16, 17, 18, 19, the five-token `Strategy`, Q-C1, Q-C1-vocab,
Q-C1-rollup, Q-C2, Q-C3) and the contract-visible batch-3/4 items (Q-G1, Q-F4–Q-F8): **the structure already
said what was ruled**; the divergence was prose — 27 lines still called ruled items "not yet ratified" /
"awaiting the owner" / "recommendation". Two checks were added first and seen red (new `validate-openapi.py`
check 16 pins the ruled shapes; check 17 refuses pending-marker prose), then the prose was rewritten as
ratified, citing the sitting. (2) **M-Q2 + M-Q2b applied:** `declareInstance`'s body carries a required
`acquisition = {mode: direct-http | via-ssh, host?}` (one shared `components.schemas.Acquisition`, direct
listed first, host present iff `direct-http`, closed host grammar); `Instance` carries it read-only; the
per-placement `healthAcquisition` is removed from both the declare body and `Instance.placements[]`. M-Q14
`metricsPorts` and M-Q15 are plan 011's and are **not** here. No Java mirror exists for the declare/Instance
shapes (`ConsoleOperations` registers operationIds and verbs only, both unchanged), so no Java file changed;
`cp-api` (43) and `console-gateway` (154) unit tests are green. (3) `ENDPOINT-INVENTORY.md` §7 now records the
ratifications, the stale `T0.1a` paragraph is corrected (check 15 exists), and the acquisition fields are
documented. Mutation proofs: 15 mutations against checks 16/17 each red, `console.yaml` restored
byte-identical (sha256 `2205fb98…`), plus mutation (g) re-run after check 15's helpers were shared — red.
**Plan text to amend by the integrator (not edited here — other tasks' sections):** T2.1's DDL (`health_acquisition`
on `placement`, → `instance_state.acquisition_mode` + `direct_host`), T2.5's `PlacementView`/`InstanceView`
(and `HealthAcquisition` in T2.1's record), T4.4's declare body, T6.9's mapping and T7.2's poller wording.
✅ **Amended 2026-10-05 by task S0-records** (owner rulings M-Q2/M-Q2b, `OR-71`): T2.1's DDL, T2.5's records, T4.4, T6.9 and T7.2 now carry the instance-level acquisition.
✅ **Amended again 2026-10-05 by the S0 close (`OR-82`)**: the `host?` named in the paragraph above no longer exists. `Acquisition` is `{mode}` only; `acquisition.host`, its grammar and its `if/then/else` are deleted from `console.yaml`; `validate-openapi.py` check 16 fails if the declare body or `Instance` carries any host-like property; `ENDPOINT-INVENTORY.md` §7 items 1, 2 and 4 are resolved by `OR-82`. T2.1 (no `acquisition_host` column), T2.5, T4.4 and T7.2 below carry the target-address rule.

**Results (T0.1, Steps 1–5 done, Step 6 done EXCEPT check (iv)/mutation (g), 2026-09-29, at
`466e560`).** Steps 1–5 are done. Step 6 is done for (a)–(f), (h); check (iv) — the parity
assertion that `gateProved`/`probeStrength` matches `solution-package.schema.json`'s `strength`
field — does not exist yet, because that field does not exist until `T0.1a` lands it, so mutation
(g) (add `startup` to `gateProved`'s enum only → check (iv) red) has nothing to run against
either. **Integrator ruling:** check (iv) and mutation (g) are **reassigned to `T0.1a`** (the task
that lands `strength`), not sequenced as a "Step 6b" of this task. Step 6's box stays unticked
here for that reason — the remaining item is not this task's to close. Every
remaining row (1–25) is transcribed per the table; row 26
(`gateProved`/`probeStrength`) is written with the ruling's literal enum
(`readiness`/`liveness`, plus `none`) but **not yet parity-checked** — that needs `T0.1a`
(a separate task this dispatch did not run), which has not landed
`solution-package.schema.json`'s `strength` field for check (iv) to compare against. All 12
checks in `./contracts/run-checks.sh --strict` pass, including two new ones this task added
(numbered 13 and 14 in `validate-openapi.py`'s own docstring, both inside its check 11 step):
33 view-model fixtures validate against `components.schemas.ViewModel`, and no request body
names `organizationId`. `VERIFY cp-api`, `VERIFY console-gateway` and `VERIFY launcher` are
green (`mvn -o -f backend/pom.xml -pl cp-api,console-gateway,launcher -am verify`).

Step 6 mutation proofs, each red → restored byte-identical → green: (a) a fixture's
`engineVersion` set to a string turns check 11 red (`'two' is not of type 'integer'`). (b) a
sixth OR-45-style operation added to the YAML without a Java registration turns
`ConsoleOperationsMirrorTest` red (two assertions). (c) **against the landed slice:** putting
`long stateVersion` back on `ScopeNodeList` (plus the one call site) turns
`HierarchyControllerTest` red, as `9ec6797`'s commit message already states; re-run to answer
the question it leaves open — `SchemaMirrorLockTest.scopeNodeAndScopeNodeListMatchTheirRequirednessExactly`
**also goes red** (`expected: [items] but was: [stateVersion, items]`). Because it does not stay
green, per row 2's own conditional this task adds **no** separate
`scopeNodeListCarriesNoStateVersion()` test — the existing lock already catches the
regression. (d) unregistering `observeFleetApply` turns `ConsoleOperationsMirrorTest` red (two
assertions, naming the orphaned operationId). (e) misspelling `x-sm-scope: reach` as `reachable`
on `getHomeSummary` turns check 11 red. (f) **corrected on review** — the plan's own mutation
(dropping `setLogLevel` from check 12's own expected set in `validate-openapi.py`, not
redirecting the operation's response schema) was re-run: it turns the `ApplyAccepted`-confinement
assertion red (`expected [...without setLogLevel], found [...setLogLevel...]`), restored
byte-identical. (g) **not run — reassigned to `T0.1a`, above**, because `T0.1a` (a separate task
this dispatch did not run) has not yet landed `solution-package.schema.json`'s `strength` field,
so no check (iv) exists yet to mutate. (h) putting `organizationId`
back into `loadPackage`'s body turns the new check-14 assertion red, naming the operation.

**Hardened on review (2026-09-29), against the reviewer's own measured mutations, each red →
restored byte-identical → green:**
- **Check 14** (`I5`, no `organizationId` in a request body) did not follow `$ref` or recurse into
  nested `properties`/`items`, so a `$ref`'d body (the new `FleetApplyRequest`) or a nested field
  (e.g. `members[].placement.organizationId`) passed unchecked. `_all_schema_properties` now
  resolves `$ref` against the document (cycle-guarded) and recurses into `properties`, `items`,
  and `allOf`/`oneOf`/`anyOf`. Mutation: adding `organizationId` to `FleetApplyRequest.properties`
  (top level, reached only through the `$ref`) turns check 14 red; a second mutation nesting it
  at `members[].placement.organizationId` also turns it red. Both restored byte-identical
  (`console.yaml` sha `ec48ac47f6e693ca8380ebb0064d47d54b880c2ee7221bc8470a69a39cf2eb79`).
- **Check 13** (every viewmodel fixture's `expected` validates against `ViewModel`) left every
  object schema in that family open (`additionalProperties` unset), so it proved only that the
  schema is no stricter than the fixtures, not that it describes them — a field the fixtures stop
  carrying would pass silently. `additionalProperties: false` is now set on `ViewModel`,
  `ComponentView`, `SectionView`, `FieldView`, `ActionView`, `RenderedEntry`, `ExcludedEntry`,
  `MetricView`, `ViewModelConfigState`, `StatusView` (and its two nested inline objects,
  `version` and `replicas[]`), `Diagnostic`, and `Validation` — every object schema `ViewModel`
  reaches. Mutation (the reviewer's own): deleting `FieldView.hiddenBy` and the whole
  `StatusView.replicas` property (from `properties` and `required`) now turns check 13 red across
  every fixture that carries either field (`'hiddenBy' was unexpected`); still green with neither
  deleted. Restored byte-identical (same `console.yaml` sha as above).

Six cheap MINORs from the same review are fixed in the same change: `FleetRefused.refusedMembers`
is no longer `required` (the reach pre-check `403` carries none — the schema no longer
contradicts its own response description); a new `PackageVersionId` schema mirrors
`solution-package.schema.json`'s `$defs/packageVersionId` exactly, and `LoadPackageResult.packageVersion`,
`Solution.packageVersions`/`latestPackageVersion`, `declareInstance.declaredPackageVersion` and
`Instance.declaredPackageVersion` all reference it (`LoadPackageResult.packageVersion` is now the
loaded document's own array, not a scalar with a false "dotted tuple" gloss); `observeApply`'s
`200` description now says "strictly newer", matching the parameter text; `setConfigSecret`'s
description no longer names the retired `secretPresent` field, and `NOT_IMPLEMENTED`'s definition
(`console.yaml` and `ErrorCode.java`) now covers both "no approved verb" and "approved but not yet
built" (`setLogLevel`'s `mode: config`); `HomeSummary.byHealth` keys are now constrained to the
S0-11 vocabulary via `propertyNames`; `Instance.placements[]` now requires its full row-23 shape
(`placementId, targetId, containerName, healthAcquisition, probeStrength`), `Solution.latestPackageVersion`
is now `required`, and `Target`'s read-only fields (`transport`, `ownershipScope`,
`allowedConfigRoot`, `allowedContainerPrefix`) now carry `readOnly: true` alongside their existing
prose. None of these narrow anything Step 7 has not already ruled; each closes a gap against the
plan's own rows.

Re-verified after every fix above: `./contracts/run-checks.sh --strict` — **all 12 checks pass**
(check 11's own sub-count unchanged: 32 operations, 65 internal `$refs` now resolve (up from 64,
the new `PackageVersionId` schema's own references), 33 view-model fixtures, no `organizationId`
in a request body). `mvn -o -f backend/pom.xml -pl cp-api,console-gateway,launcher -am verify` —
**BUILD SUCCESS**, reactor order `cp-api → dp-api → mesh-events → cp-core → dp-core →
console-gateway → launcher`: `cp-api` 43/0/0/0, `mesh-events` 127/0/0/0, `cp-core` 109/0/0/0 then
165/0/0/0, `dp-core` 247/0/0/1 then 334/0/0/4, `console-gateway` 154/0/0/0, `launcher` 6/0/0/0
then 23/0/0/0 (run/failures/errors/skipped).

**Twelve items are Step-7-recommended, not ratified (ten listed below, plus 11 and 12 added in round 4) — flagged in `console.yaml`'s prose,
[`ENDPOINT-INVENTORY.md`](../../../contracts/openapi/ENDPOINT-INVENTORY.md) §7 (its own table),
and this note, per the plan's recommendation, with the alternative named. (An earlier revision of
this note listed five and omitted the other four — corrected then, per the second review of this
task's Results: Q-C1, Q-C3 and point 18 were flagged only inline in `console.yaml`, not
consolidated here or in §7's table, the Strategy-enum widening was not flagged anywhere, and
Q-C2 was wrongly described as having no alternative. A round-3 review then found item 6 (Q-C1)
itself wrong — corrected below — and added item 10.)**
1. **Point 12** — `rollbackApply`'s `placementId` made **required** (matching `submitApply`,
   `BE-D23`). *Alternative:* keep it optional/nullable, the landed `9ec6797` shape.
2. **Point 16** — `getHomeAttention`'s items carry a **per-item `stateVersion`** (§6.1's
   collection rule). *Alternative:* none, per the landed description's argument.
3. **Point 17** — `setConfigSecret` **keeps the landed shape** (`200 StagedConfig`, one call).
   *Alternative:* two calls — store, then a separate stage.
4. **Point 18** — `acceptRuntimeConfig` gains a **required `placementId`** (which placement's
   file is read back) and a `422` for an unparseable or secret-bearing read-back. This is an
   *addition*, not a divergence from any landed or earlier-plan shape — Step 7 ratifies it; no
   alternative was offered. `T10.3` (D-ACCEPT) may narrow the `422` case only by a later reviewed
   change.
5. **Point 19** — `restoreRuntimeConfig` gains a `strategy` field (default `manual`), with
   `instance.restart` required only when the strategy restarts. *Alternative:* the landed
   manual-only shape, `instance.restart` never required.
6. **Q-C1 / row 15 — corrected, round-3 review (MAJOR).** The earlier text here read "`byHealth`
   is present only where `health.view` is held **at the row's own scope**" — wrong, because a
   catalog solution row has no scope path of its own (`listSolutions`, `console.yaml:354-357`)
   to hold anything "at". Corrected: `instanceCount` is counted over the solution's **instances**,
   filtered *per instance* by `instance.view` at each instance's own scope path (the same
   downward-reach filter `listInstances` uses); `byHealth` counts only among those instances
   where `health.view` is *also* held at that instance's own scope path — still recommendation
   (b), just anchored at the right scope. *Alternatives (unchanged):* (a) add `health.view` to
   `x-sm-permission-also`; (c) keep as is — `instance.view` implies seeing health counts. This
   corrected reading is recorded, not ratified, awaiting the owner (ENDPOINT-INVENTORY.md §7).
7. **Q-C2 / row 16** — `getHomeAttention`'s per-item `reason` is filtered by the verb that guards
   its source: `health.view` (for `health`) or `config.view` (for
   `apply-indeterminate`/`config-diverged`) — recommendation (a). *Alternative:* (b) keep
   everything under `health.view`.
8. **Q-C3 / row 22** — `Target.kind` (the MI runtime) is kept beside the new `transport` (how SM
   reaches it) — recommendation (a); the description states v1's one pair (`ssh-docker` ⇒
   `docker`). *Alternatives:* (b) drop `transport`, keeping `kind` as today; (c) replace `kind`
   with `transport`.
9. **`Strategy` enum widening** — this document widens `Strategy` to all five engine tokens
   (`Strategy.java` does define all five, so none is invented), where the plan's text asked only
   that the server read the value as a string before rejecting the three unbuilt strategies — a
   requirement a narrower, two-member enum with a raw-string pre-read equally satisfies.
   **Recommendation: keep the widening, as written.** The ruling's own wording — "the server reads
   the value as a string first" and answers `501`, "never `403`" — describes a value that *reaches*
   the server; a two-member enum would have the schema itself refuse `restart-all` etc. with a
   generic `400` before any handler runs, so the documented `501` path would be unreachable by a
   schema-conforming client. Widening does put three unselectable values into generated frontend
   types, which the picker must not offer (§3 of `ENDPOINT-INVENTORY.md` says so). *Alternative:*
   the narrower two-member enum with a raw-string pre-read (the plan's literal text; it satisfies
   the plan but not the ruling's "reads the value as a string first" phrasing as written).
10. **`byHealth` key vocabulary — new, round-3 review (MINOR).** `HomeSummary.byHealth`'s
    `propertyNames` carried only `StatusView.healthRollup`'s five values, while
    `Health.placements[].state` and `HomeAttention.items[].state` carry all nine S0-11 values
    (the five rollup keys plus the four `NOTFOUND` sub-states, including `unreachable`) — an
    instance whose only signal was `unreachable` had no bucket to land in, and no rollup mapping
    from nine down to five was ever stated. **Recommendation: widen to the full nine-value set**
    (done), rather than invent an unstated collapsing mapping. *Alternative:* keep the five-value
    set and state the mapping explicitly (no such mapping exists today, so this would need one
    written from scratch, not merely disclosed).

Plus, transcribed from [`fleet-apply.md`](../../design/backend/fleet-apply.md) §10 (T0.0's own
open items, gating this text per its table): `FleetApplyRequest.members` `maxItems: 100` (Q-F4),
`batchSize` default `1` (Q-F4), `onFailure` default `halt-remaining` (Q-F4), a refused fleet
always answers `403 FORBIDDEN` with `refusedMembers[]`, never a distinct code for an
all-`NOT_FOUND` refusal (Q-F5), and a refused member is filtered exactly like any other member —
visible only to a viewer who can see its instance (Q-F6, Q-F8). None of the nine `Q-F*` items is
decided by this task; all are recommendations only, open for the Step 7 sitting.

11. **`byHealth` per-instance rollup (Q-C1-rollup) — round 4.** Each instance counts into one bucket by
    `project.ts`'s `healthRollupOf` literally at placement scope, with the four `NOTFOUND` sub-states inserted
    (any `RED`, `missing`, `unreachable`, `not_exposed`, `RESTART_EXPECTED`, `bootstrap`, in that order; every
    placement `INACTIVE_REPLICA` → `INACTIVE_REPLICA`; else `GREEN`), so a healthy `active_standby` pair counts
    `GREEN`. The round-4 draft ranked worst-wins and would have counted every healthy pair as standby; the
    round-4 review caught it and the integrator corrected it. *Alternative:* count placements, not instances.
12. **Q-G1 fail-closed default — round 4.** `rolling-health-gated` with no SM-executed probe fails before any
    write (`strategy.health.signal.absent`), recommendation (a); (b) gates on `docker inspect` and reports
    `gateProved` absent. Transcribed verbatim from Q-G1 in §7.

**Round 4 (integrator).** Four fix rounds did not reach accept; the fifth review's one MAJOR (the rollup
contradicting `healthRollupOf`) and its MINORs (Q-G1 row transcribed verbatim, `getHomeAttention`'s 200
description, §7's stale "no mapping" alternative, this count) were fixed by the integrator directly. Inline
"round-N review" tags are kept deliberately: Step 7 requires each item to be marked in `console.yaml` beside the
delta.

**Step 0 — discharged by the integrator (round-3 ruling, corrected here).** This dispatch's own
scope was Steps 1–6 (this Results note's own heading names them), so Step 0 (run `--strict` on
the landed `9ec6797` in isolation and re-derive the "Landed" column from `git show`) was not run
*as a step of this task*. The earlier revision of this note asked the integrator to either run it
or record an equivalence; **the integrator has now done the latter**: `git diff --stat 9ec6797
HEAD -- contracts/ backend/cp-api/.../authz/ConsoleOperations.java
backend/console-gateway/.../ErrorCode.java backend/console-gateway/.../HierarchyController.java`
returns empty — `HEAD`'s contracts and the landed Java files are **byte-identical** to `9ec6797`
(verified 2026-09-29, round-3 review; `git merge-base --is-ancestor 9ec6797 HEAD` confirms
`9ec6797` is on this branch's history), so the "Landed" column above, re-derived from `git show
9ec6797`, is already the correct measurement of `HEAD` — there is no drift for Step 0 to catch.
`./contracts/run-checks.sh --strict` on the current tree (Steps 1–6's edits included) still
passes all 12 checks. The box stays unticked because the *step as written* — run `--strict` on
`9ec6797` in isolation — was still never separately executed; the integrator accepts the
byte-identical diff as the equivalent evidence and closes the open question this note raised.

**Follow-up needed, not fixed here: `ENTITLEMENT_REFUSED`'s HTTP status is unguarded.** The plan
singles out `ENTITLEMENT_REFUSED` → `409`, "not `403`" (§5.6 rule 3), but `SchemaMirrorLockTest`
checks error-code *names* against the YAML, never their HTTP statuses — measured: changing
`ENTITLEMENT_REFUSED(409)` to `ENTITLEMENT_REFUSED(403)` in `ErrorCode.java` leaves it green
(13/13). This task's Files list does not include that test, so no status-parity assertion is
added here. **Recommendation: route a status-parity check to T1.3 or whichever task first serves
`ENTITLEMENT_REFUSED`**, so the mapping cannot silently drift before then.

**Consideration for a later task, not acted on here:** the closed vocabularies this task copied
by hand from `solution-package.schema.json` and the notify-events schema (`PackageVersionId`, the
`ViewModel`-family enums, `Health.state`) are unpinned by any parity check — checked once by hand
against the source schemas and matching today, but nothing re-checks them on drift. The plan asks
for a parity check only for `strength` (check (iv), row 26). Once check (iv) exists (written
under `T0.1a`, per the integrator's reassignment above), its mechanism is worth extending to
these vocabularies too.

**Files changed:** `contracts/openapi/console.yaml`, `contracts/openapi/validate-openapi.py`,
`contracts/openapi/ENDPOINT-INVENTORY.md`,
`backend/cp-api/src/main/java/com/solace/sm/cp/api/authz/ConsoleOperations.java`,
`backend/console-gateway/src/main/java/com/solace/sm/gateway/http/ErrorCode.java`. No test file
needed editing (Step 6 confirmed the existing mirror-lock tests already catch every regression
this task's changes touch).

**Round-3 review disposition (2026-09-29, changes-required from the round-3 Opus reviewer, all
fixed per the integrator's rulings; no Java, `validate-openapi.py` or test file touched — every
fix is `console.yaml` prose/schema-shape or `ENDPOINT-INVENTORY.md` text):**

1. **MAJOR — `getHomeSummary`'s scope-filtering premise.** Fixed. `console.yaml`'s
   `getHomeSummary` description and the `HomeSummary`/`instanceCount`/`byHealth` schema
   descriptions no longer say "filtered at the row's own scope" (a catalog solution row has no
   scope path — `listSolutions`, `console.yaml:354-357`). Corrected: `instanceCount` is counted
   over the solution's instances, filtered *per instance* by `instance.view` at each instance's
   own scope path; `byHealth` counts only among those instances where `health.view` is *also*
   held at that instance's own scope path. This reading of Q-C1(b) is recorded in
   `ENDPOINT-INVENTORY.md` §7 (row "Q-C1 ⚠️") and in this Results note (item 6, above),
   explicitly **awaiting the owner** — not silently adopted as settled.
2. **MINOR — `byHealth` propertyNames too narrow.** Fixed by widening. `HomeSummary.byHealth`'s
   `propertyNames` now carries the full nine-value S0-11 vocabulary
   (`Health.placements[].state`'s set: five rollup keys + four `NOTFOUND` sub-states, including
   `unreachable`), not `StatusView.healthRollup`'s five — no rollup mapping was ever stated for
   the missing four, so widening avoids inventing one. Disclosed in `ENDPOINT-INVENTORY.md` §7
   (row "Q-C1-vocab") and this Results note (item 10, above).
3. **MINOR — `setLogLevel`'s D-LOG dependency stated as fact.** Fixed. Both `console.yaml`'s
   `setLogLevel` description and `ENDPOINT-INVENTORY.md` rule 8 now read as conditional: `T9.3`
   adds a fourth `op_known` member and `V013` **only if** `T9.2`/D-LOG decides the live `SET` is
   a journaled operation (plan 010's own T9.2/T9.3 row); if D-LOG decides otherwise, the
   operation stays permanently unobservable at `/apply/observe`, by design, not as a gap.
4. **MINOR — row 26's fail-closed rule absent from the contract.** Fixed. The `Strategy` schema
   description now states Q-G1 recommendation (a): under `rolling-health-gated`, with no
   SM-executed probe declared (none, or only `broker-heartbeat`), the engine refuses before any
   write, landing `FAILED_NO_EFFECT`/`strategy.health.signal.absent`. `gateProved`'s description
   cross-references it (absent whenever the fail-closed rule fired, not just when the gate
   failed). Flagged as Q-G1's recommendation, awaiting the owner — unchanged from the plan's own
   status for Q-G1.
5. **MINOR — check (iv) / mutation (g).** Disposition unchanged, per the integrator's explicit
   reassignment: check (iv) (`gateProved`/`probeStrength` parity against
   `solution-package.schema.json`'s `strength` field) and its mutation (g) are `T0.1a`'s work,
   not this task's — `T0.1a` has not landed the `strength` field for it to compare against.
   **Step 6 stays unticked** (mutations (a)-(f), (h) done; (g) not run, not runnable yet).
   `validate-openapi.py` is unchanged by this round.
6. **MINOR — `AvailabilityOnly` description stale.** Fixed. It now lists all ten consumers
   (`ScopeNodeList`, `TargetList`, `LogPage`, `LogLevel`, `SolutionList`, `InstanceList`,
   `HomeSummary`, `HomeAttention`, `ApplyObservationList`, `Health`) instead of only the
   original four, with the `OR-55` per-item-version rationale for the six added since.
7. **MINOR — `FleetAccepted.state`/`FleetObservation.state` untyped; invented `FLEET_REFUSED`
   token.** Fixed. Both `state` fields are now `enum: [RUNNING, SUCCEEDED,
   COMPLETED_WITH_FAILURES, HALTED]` (fleet-apply.md §5.2's CHECK constraint, verified against
   that file). `submitFleetApply`'s `403` description no longer calls the refusal
   "`FLEET_REFUSED`-shaped" — no such code exists (`FLEET_REFUSED` was Q-F5's rejected option
   (c)); both refusal cases answer `code: FORBIDDEN` on the same `FleetRefused` schema, reworded
   to say so.
8. **MINOR — Step 0 note.** Corrected (see the "Step 0 — discharged by the integrator" note,
   above): the integrator's own equivalence evidence (`git diff --stat 9ec6797 HEAD --
   contracts/ …` returns empty) is recorded in place of re-running Step 0 on an isolated
   checkout. ENTITLEMENT_REFUSED 409 status-parity gap: carried forward to T1.3 unchanged, per
   the integrator's acceptance.

**Commands run and results (round-3 fixes, 2026-09-29):**
- `./contracts/run-checks.sh --strict` — **all 12 checks pass**, both before this round's edits
  (baseline) and after: 32 operations unchanged, 65 internal `$refs` resolve (unchanged), 33
  view-model fixtures (unchanged), no `organizationId` in any request body (unchanged). `python3
  -c "yaml.safe_load(...)"` independently confirms the document still parses and that
  `HomeSummary.byHealth`'s `propertyNames.enum`, `FleetAccepted.state.enum` and
  `FleetObservation.state.enum` hold exactly the intended values.
- `mvn -o -f backend/pom.xml -pl cp-api,console-gateway,launcher -am verify` (reactor: `cp-api →
  dp-api → mesh-events → cp-core → dp-core → console-gateway → launcher`) — **BUILD SUCCESS**,
  7:20 min. Test counts **identical** to the pre-round-3 baseline in every module (no regression,
  as expected — this round touched no Java): `cp-api` 43/0/0/0, `mesh-events` 127/0/0/0, `cp-core`
  109/0/0/0 then 165/0/0/0, `dp-core` 247/0/0/1 then 334/0/0/4, `console-gateway` 154/0/0/0,
  `launcher` 6/0/0/0 then 23/0/0/0 (run/failures/errors/skipped).
- **No mutation proofs run for these seven findings.** Every fix in this round is contract
  *prose* or an enum/schema-shape change on a field no fixture or Java test currently exercises
  (`byHealth`, `FleetAccepted.state`, `FleetObservation.state`, the `Strategy`/`gateProved`/
  `AvailabilityOnly`/`setLogLevel` description text) — `run-checks.sh`'s checks validate
  structure ($ref resolution, operation/verb/scope mapping, view-model fixture conformance),
  never schema-description prose or the specific enum values of fields not yet exercised by a
  fixture. The unchanged check/test counts above are the regression evidence (nothing broke);
  there is no narrower red→green pair to demonstrate for these specific edits, the same
  limitation already noted for the hand-copied closed vocabularies ("Consideration for a later
  task", above) — extending that same parity-check mechanism to `byHealth` and the fleet `state`
  enums is a candidate for whoever builds their first consuming fixture (T10.5, T11.2).

### Task T0.1a — The package declares its health probe's strength (owner ruling 2026-09-29 (S0-10)) — a closed-vocabulary contract change

**Wave:** W0, after T0.1 Steps 1–6 and **before T0.1 Step 7** (reviewed in the same sitting). **Depends on:** the
ruling only. **Parallel:** ⛔ none — `contracts/` is single-writer and W0 is one agent.

**Why:** the ruling makes `rolling-health-gated` gate on the **package-declared** probe and report which strength
the gate proved. Only the package can say whether its probe's UP means *serving* (readiness-grade) or only *the
process came up* (liveness-only): SM may not infer it (`I2`) and may not ask the MI to change (`I1`). This is how
open item 15 is answered **by declaration** — and how SM stays a generic manager: no code branches on the solution
(Global Constraints).

**Files:**
- Modify `contracts/solution-package.schema.json` — `$defs/health/properties/probe` (today `{kind, path, port}`,
  `additionalProperties: false`, `required: [kind]` — measured 2026-09-29) gains `strength: {enum: [readiness,
  liveness]}`, **required**, so no package claims readiness by omission; its description cites `UI-D14` (the schema
  is the canonical home of every closed vocabulary) and the ruling.
- Modify every positive fixture that declares a probe. Measured 2026-09-29: `contracts/validation/fixtures/cache-2.4.package.json`
  (`:491-495`) → **`strength: liveness`** (the ruling: Cache's active_standby probe is declared liveness-only), and
  `engine-edge.package.json` (`:46`) → `liveness`. The engine-edge value is conservative, because no measurement of
  that MI's probe exists, and the commit says so. `filemesh-ftp-solace.package.json` declares no `health` (measured).
- ⚠️ **Every derived copy of the Cache fixture carries its probe too (B-1, measured 2026-09-29).** `grep -rl '"probe"'
  contracts/validation` hits the two fixtures above, **both** overlap fixtures and **all 17 negatives**. Every
  negative is a mutated copy of the Cache fixture (`negative/generate.py:10`, `BASE = …cache-2.4.package.json`), and
  `validate()` stops at the first gate-1 error (`validate-package.py:176-180`). So once `strength` is required,
  every stale copy is rejected **by gate 1**, gates 2, 4, 5, 5b, 9, 10 and 12–14 are exercised by nothing, and
  `--expect-fail` stays green because it accepts **any** error (`:193-196`). Therefore:
  - **Regenerate** `contracts/validation/negative/*.json` **and** `fixtures/overlap/cache-2.0.package.json` with
    `python3 contracts/validation/negative/generate.py`, after the Cache fixture changes (the script writes both).
    Before any edit, run it once on the unchanged tree: `git diff --stat contracts/validation` must be empty. If it
    is not, the committed files have drifted from their generator, which is a finding recorded before going on.
  - **Edit by hand** `fixtures/overlap/cache-2.4.package.json`. It is **not** generated (measured: a separate
    9.5 KB file), so it gains `strength: liveness`.
  - Add the two new cases to `generate.py`'s `CASES`: `gate01-probe-missing-strength.json` (a probe without
    `strength`) and `gate01-probe-unknown-strength.json` (`strength: "startup"`). Both are schema-level, so both are
    `gate1`. The negative count goes 17 → **19** (T3.1 asserts 19).
- Modify `contracts/validation/validate-package.py`. **`--expect-fail` asserts the expected gate, not just any
  error**, so this degradation cannot recur silently. The expected token comes from the file name's `gateNN[x]`
  prefix (`gate05b-…` → `gate5b`, `gate14-…` → `gate14`), with the measured schema-level mapping `06`, `07`, `08`,
  `11` → `gate1`: the same mapping T3.1's Java parity test asserts, and the one the Python prints today, measured
  2026-09-29. A negative rejected by a different gate is a `FAIL` naming both gates.
- Modify `contracts/run-checks.sh`, steps 1–3 only. **Step 1** also validates `fixtures/engine-edge.package.json`
  with `--dev-mode` (MINOR 4: measured, no Python gate runs over it today, and it passes with `--dev-mode`, is
  refused by gate 2 without). **Step 3** requires the overlap rejection to be **`gate3`**: it greps the output for
  `gate3  overlap:` rather than accepting any failure.
- `contracts/view-models/src/types.ts` — **no change expected**: measured, `health?: { readonly probe:
  Readonly<Record<string, unknown>> }` (`types.ts:83`) is opaque, and projection reads only the presence of health
  (`healthAvailability: 'declared' | 'not-declared'`, `types.ts:236`). The viewmodel fixtures name their package
  (`cases.ts:14`) rather than embedding it (measured: no `"probe"` in `view-models/fixtures/viewmodel/`). The task
  confirms both and that the 33 viewmodel fixtures' `expected` blocks are byte-unchanged. If either is false, the
  change is made here.
- Read only (consumers, not edited): the landed `cp-api/.../designer/PackageModel.HealthBlock(Map<String,Object> probe)`
  is opaque too, so the Java port is unaffected; T3.1's parity tests pick up the new negatives by directory.

**Consumers (later tasks, named so the field is not orphaned):** T2.1 copies it into `placement.health_strength`;
T4.4 resolves it from the package at declare; T6.9 gates on the probe and reports it; T0.1 point 26 exposes it.

- [x] **Step 1: Harden the checks first, on today's tree.** Make `--expect-fail` assert the file name's gate and make
  `run-checks.sh` step 3 require `gate3`. Add engine-edge to step 1. Run `./contracts/run-checks.sh --strict`: it
  must be **green** on the unchanged tree, with every negative rejected by its own gate. Record the 17 `-> gate…`
  lines. A red here is a pre-existing mismatch, fixed or recorded before going on.
- [x] **Step 2: Failing cases.** Add `strength` to the schema as **required**, and nothing else →
  `./contracts/run-checks.sh --strict` is **red**. The positives fail as missing `strength`; step 2 reports each of
  the 17 negatives as "rejected by `gate1`, expected `gateN`"; step 3 reports overlap rejected by `gate1`, not
  `gate3`. Record the red output. It is the proof that the hardened check sees B-1.
- [x] **Step 3: Implement.** Give the Cache, engine-edge and hand-kept overlap fixtures `strength: liveness`, add
  the two cases to `CASES`, and run `generate.py`.
- [x] **Step 4: Run green.** Run `./contracts/run-checks.sh --strict`: every gate and every negative is rejected by
  its own gate (19 lines recorded); overlap by `gate3`; engine-edge passes; the view-models engine and its 33
  fixtures pass; check parity passes. Also `VERIFY cp-core`: the landed `DesignerProjectorFixtureTest` still 33/33
  and `PackageJsonReaderTest` green over the changed fixtures (a Maven run, taken in its serialized slot).
- [x] **Step 5: Prove they fire.** Make `strength` optional → the missing-strength negative passes → red. Add
  `startup` to the enum → the unknown-member negative passes → red. Remove `strength` from the Cache fixture → red.
  **Restore one regenerated negative (e.g. `gate13-contradictory-secret.json`) to its pre-regeneration copy** →
  step 2 red, naming that file with "`gate1`, expected `gate13`". Restore the expected-gate assertion's old
  any-error behaviour **with that stale file in place** → step 2 goes **green**. That is the silent failure B-1
  describes, shown once and recorded. Remove `strength` from `overlap/cache-2.4.package.json` → step 3 red. Revert
  each.
- [x] **Step 6: Owner review** — in T0.1 Step 7's sitting (2026-09-30).
- [x] **Step 7: Commit.** `contracts: a package declares its health probe's strength — readiness-grade or liveness-only (S0-10)`

**Results (T0.1a, Steps 1–5 done, 2026-09-29, dispatched on `integration/dp-remote-demo` at `953ef44`,
uncommitted — the integrator commits).** Steps 1–5 executed exactly as written; Steps 6–7 (owner
review, commit) are not this dispatch's to close.

- **Step 1** (harden first): `validate-package.py` gained `expected_gate(filename)` — parses the
  `gateNN[x]-` prefix, maps schema-only numbers `{6, 7, 8, 11}` to `gate1` (the mapping the module's
  own docstring already states), else `gate{N}{suffix}`; `--expect-fail` now compares this against
  `errs[0]`'s own leading token and fails naming both when they differ, falling back to the old
  any-error behaviour only for a name that does not match the convention. `run-checks.sh` step 1 gained
  a third positive run — `fixtures/engine-edge.package.json --dev-mode` (MINOR 4: it is
  `provenance.author: dev-mode`-signed, gate 2 refuses it without `--dev-mode`). Step 3 now captures
  the overlap run's output and requires **both** no individual per-file gate violation on either
  overlap fixture **and** a literal `FAIL gate3  overlap:` line — a bare non-zero exit no longer
  passes. Run green on the unchanged tree (schema untouched): all 12 checks passed, all 17 negatives
  each rejected by its own gate (recorded below), overlap rejected by `gate3` only.
  ```
  gate2  signature: author=solace but no signature
  gate4  cache-engine/solace.host: when references unknown field 'no-such-field'
  gate5  cache-engine/solace.management.management-queue: when omits ancestor 'solace.enabled' required by 'solace.management.enabled'
  gate5b cache-engine/payload-key-json-pointer: when key-source='PAYLAOD' is not in options ['TOPIC', 'PAYLOAD', 'HEADER', 'COMPOSITE']
  gate1  schema: components/0/fields/2/type: 'string' is not one of ['enum', 'bool']            (gate06 file, schema-only)
  gate1  schema: components/0/metrics/0/source: 'onUnavailable' is a required property           (gate07 file, schema-only)
  gate1  schema: components/0/metrics/0: 'unit' is a required property                           (gate07 file, schema-only)
  gate1  schema: components/0/fields/1/optionsSource: Additional properties are not allowed ('url' was unexpected)  (gate08 file, schema-only)
  gate1  schema: components/0/metrics/0/source/kind: 'cache-stats' is not one of [...]           (gate08 file, schema-only)
  gate9  cache-engine: duplicate field key 'namespace'
  gate9  cache-engine: duplicate metric key 'cache.hit_rate'
  gate10 cache-engine: yaml field 'solace.extra' subtree 'solace' collides with field 'solace.enabled'
  gate1  schema: components/0/fields/1: {...} should not be valid under {'required': ['options', 'optionsSource']}  (gate11 file, schema-only)
  gate12 cache-engine: 'solace.enabled' is keyed on by a predicate but has no default and is readOnly, so every dependent field is hidden forever
  gate13 cache-engine: 'solace.password' declares type 'secret' with secret:false — one guarantee, two contradictory spellings
  gate14 cache-engine/provider: field version 2 is not declared by packageVersion
  gate14 cache-engine/cache.hit_rate: when field 'provider' is unavailable in versions [2]
  ```
- **Step 2** (failing cases): added `strength` (required, `enum: [readiness, liveness]`) to
  `$defs/health/properties/probe`, nothing else. `--strict` went red exactly as predicted: both
  positives (`cache-2.4`, `engine-edge`) failed as missing `strength`; 13 of the 17 negatives (every
  one not already schema-only) reported `FAIL (rejected by gate1, expected gateN) <file>`; step 3
  reported `FAIL overlap was rejected, but by a per-file gate violation, not gate3 (the overlap signal
  is masked)` with both overlap fixtures' `gate1` violations shown. Recorded as proof the hardened
  check sees B-1.
- **Step 3** (implement): `cache-2.4.package.json`, `engine-edge.package.json` and the hand-kept
  `overlap/cache-2.4.package.json` all gained `"strength": "liveness"` on their probe (the ruling:
  Cache's active_standby probe is declared liveness-only; engine-edge's value is conservative, no
  measurement of that synthetic MI's probe exists). `filemesh-ftp-solace.package.json` declares no
  `health` (measured, unchanged). Added `g01a`/`g01b` to `negative/generate.py`'s `CASES` —
  `gate01-probe-missing-strength.json` (deletes `strength`) and `gate01-probe-unknown-strength.json`
  (`strength: "startup"`) — and ran `generate.py`, which rewrote all 17 existing negatives (each now
  carries `strength: liveness` inherited from the regenerated `BASE`) plus
  `fixtures/overlap/cache-2.0.package.json`, and wrote the two new files. Negative count: 17 → 19.
- **Step 4** (run green): `./contracts/run-checks.sh --strict` — **all 12 checks pass**: all 19
  negatives each rejected by its own gate (confirmed by the hardened `--expect-fail`, including the
  two new `gate01-probe-*` cases both correctly attributed to `gate1`); overlap rejected by `gate3`
  only; engine-edge passes under `--dev-mode`; the TS view-model engine (48 tests) and its 33 fixtures
  regenerate identically (confirms `types.ts`/`cases.ts` needed no change — `health` stays opaque
  there, measured: no `"probe"` under `view-models/fixtures/viewmodel/`); check 11's parity checks
  (13, 14, and the new 15/(iv), below) all pass. `VERIFY cp-core`
  (`mvn -o -f backend/pom.xml -pl cp-core -am verify`, via the tc-env wrapper): **BUILD SUCCESS**,
  `cp-api` 43/0/0/0, `cp-core` 109/0/0/0 then 165/0/0/0 — `DesignerProjectorFixtureTest` still 33/33
  — **corrected by the integrator after review:** it *does* load
  `contracts/validation/fixtures/<package>.package.json` through `PackageJsonReader.readFile`
  (`DesignerProjectorFixtureTest.java:84-86,131-132`), so 29 of the 33 runs (22 cache-2.4, 7 engine-edge) read
  the changed fixtures, and 33/33 is real evidence of Java parity over the new field — and
  `PackageJsonReaderTest` green (it uses inline JSON, not the changed fixtures). `PackageModel.HealthBlock(Map<String,Object>
  probe)` (`cp-api`) stays opaque and was not edited, per the Files list.
- **Step 5** (mutation proofs — each red, then restored byte-identical (sha256 verified), then
  green):
  1. Removed `strength` from the schema's `required` list only → `gate01-probe-missing-strength.json`
     is accepted → `FAIL (accepted, should have been rejected)`. Restored; schema sha
     `df90724a3fea256b3bd59387a179e224ec5e55d7fc34cd8837d879123f70c926` both before and after.
  2. Added `startup` to the schema's `strength` enum only → `gate01-probe-unknown-strength.json` is
     accepted → same `FAIL (accepted, ...)` mode. Restored, same sha as above.
  3. Removed `strength` from `fixtures/cache-2.4.package.json`'s probe → `FAIL cache-2.4.package.json
     (1 gate violations) — gate1 schema: components/0/health/probe: 'strength' is a required
     property`. Restored; sha
     `33502979907a043215f020777a5589851c2dca19bc688fb10dac424611f767f4` both before and after.
  4. Restored `gate13-contradictory-secret.json` to its pre-regeneration copy (`git show HEAD:…`, no
     `strength`) → hardened step 2 reports `FAIL (rejected by gate1, expected gate13)
     gate13-contradictory-secret.json`, naming both gates exactly as the plan predicts. With that
     stale file still in place, reverted `--expect-fail`'s comparison to the old
     any-error-passes behaviour (temporarily, on a scratch copy) → step 2 goes **green**
     (`PASS (rejected as intended) gate13-contradictory-secret.json`) despite being rejected by the
     wrong gate — the silent failure B-1 describes, shown once and recorded here. Restored the
     hardened comparison (sha `dd757c0de742d94461d527f7acf0f114a58cebccf24bc24c94bd2df7802017ef`,
     matching before) and regenerated the negative back to its post-Step-3 form (sha
     `d5cd0f26359cb0554680cc97a856488fd58b25fbe97bc646c5569652244a5f42`, matching before).
  5. Removed `strength` from `overlap/cache-2.4.package.json` → the hardened step 3 logic reports the
     rejection as a masked per-file violation (`gate1`), not `gate3`, i.e. red. Restored; sha
     `8c4f58b0ccd4063a43a20e715f06fef9d50e9d6026feaa9998fc3645cbb716de` both before and after.

  Full suite re-run after every restoration above: `./contracts/run-checks.sh --strict` — **ALL
  CHECKS PASSED** (rc=0), 19/19 negatives green.

**ADDITIONALLY — integrator reassignment from T0.1 Step 6 (check (iv) / mutation (g)), done as part
of this dispatch, per the task's explicit instruction:**

Added `check_strength_parity(doc)` to `contracts/openapi/validate-openapi.py` (numbered 15 in its own
docstring, inside `run-checks.sh`'s "step 11"; the plan's remaining-work list calls it check (iv)).
It reads `solution-package.schema.json`'s `$defs/health/properties/probe/properties/strength` enum
and asserts `ApplyObservation.steps[].gateProved`'s enum equals it exactly, and
`Instance.placements[].probeStrength`'s enum equals it plus `none` — both schemas are
`allOf: [Versioned $ref, inline object]` compositions, so the check resolves `$ref` and merges across
`allOf` members rather than doing a flat `.get("properties")`. Both assertions pass against the
`console.yaml` text already in the tree (it already carried the literal
`[readiness, liveness]` / `[readiness, liveness, none]` enums, flagged "blocked on T0.1a" — now
unblocked and checked). No `console.yaml` prose was touched, per the instruction to leave T0.1's
`console.yaml` work otherwise alone.

Mutation (g) run against it: added `startup` to `console.yaml`'s `gateProved` enum only (the one
`enum: [readiness, liveness]` occurrence in the file, confirmed unique before editing) →
`FAIL ApplyObservation.steps[].gateProved's enum ['liveness', 'readiness', 'startup'] does not match
solution-package.schema.json's strength enum ['liveness', 'readiness']` (`probeStrength`'s own check
still passed, unaffected). Restored; `console.yaml` sha
`bad4145a14dfb744f7c2a14babbb047001541c7953c443fa01bc84de4a7c152e` both before and after. Re-ran
`python3 openapi/validate-openapi.py` standalone and `./contracts/run-checks.sh --strict` afterward:
both green.

**Open for the owner / a later task, not acted on here:**
- Steps 6 (owner review) and 7 (commit) are explicitly out of this dispatch's scope — left unticked.
- The plan's own T0.1a text names three consumers not yet built: T2.1 (`placement.health_strength`),
  T4.4 (resolves it from the package at declare) and T6.9 (gates on the probe and reports it) — none
  of those tasks was touched here.
- T3.1 (named in the plan's own cross-references as the task whose Java parity test "asserts 19" and
  "picks up new negatives by directory") does not exist in this tree yet — no Java test currently
  hardcodes the negative count, so nothing needed updating for that reason today, but T3.1 still owes
  that assertion once it is written.
- Per T0.1's own Results note, the closed vocabularies copied by hand into `console.yaml`
  (`PackageVersionId`, the `ViewModel`-family enums, `Health.state`) remain unpinned by any parity
  check; check 15/(iv) added here is scoped to `strength` only, per the plan's own row 26. Extending
  the same mechanism to those vocabularies is flagged there as future work, not done here.

---

### Task T0.2 — The `instance.apply-outcome` event contract (OR-4, executed by OR-46)

**Wave:** W0, after T0.1. **Parallel:** ⛔ none.

**Files:**
- Modify: `contracts/events/envelope.schema.json` (the closed `event` enum — `console-integration.md` §6: *"adding an
  event means extending `envelope.schema.json`'s `event` enum, not merely adding a `oneOf` branch"* — **and its
  `stateVersion` description**, which still says "config generation where one exists" (`envelope.schema.json:23`); it becomes OR-35's rule, one
  `sm_data` sequence stamped per owning row, with a pointer to T0.3's decision (m4)),
  `contracts/events/notify-events.schema.json`, `contracts/events/asyncapi.yaml`, `contracts/events/README.md`,
  `contracts/events/validate-events.py`, `contracts/events/fixtures/expected-topics.json`
- Create: `contracts/events/fixtures/apply-outcome.json`, `contracts/events/negative/apply-outcome-instance-kind.json`,
  `contracts/events/negative/apply-outcome-with-error-detail.json`, `contracts/events/negative/apply-outcome-without-placement.json`
- Modify: `backend/mesh-events/src/main/java/com/solace/sm/events/{NotifyEventShapes,NotifyTopics}.java`
- Modify tests: `backend/mesh-events/src/test/java/com/solace/sm/events/SchemaMirrorLockTest.java` and the mesh-events
  contract tests that read `contracts/events/fixtures`

**The shape — copied from OR-4, not re-derived:** `event = "instance.apply-outcome"`; `entity.kind = "placement"`
(`const`); `entity` = `kind` + `instanceId` + **required** `placementId`; `payload` = `{applyId, state}` with
`additionalProperties: false`, `state` ∈ the terminal states **of the `apply` operation only** — `STAGED,
APPLIED, FAILED_NO_EFFECT, FAILED_ROLLED_BACK, CONFIG_DIVERGED, SUPERSEDED, INDETERMINATE_RESOLVED` (the
`RequestState.isTerminal()` set, `RequestState.java:29-31`, minus the two stop states) **plus `APPLY_INDETERMINATE`**
— ✅ **ruled included** (owner ruling 2026-09-29 (T0.2), `OR-58`): it is not terminal but is where the engine
stops and — after T6.8's reconciler has confirmed, retried or given up — where a human must act, so a consumer must
be told. Each landing publishes once per stamp: a first landing on `APPLY_INDETERMINATE`, and the reconciler's
give-up, which writes its classifier to the request's new `reconcile_class` column (V011, T6.8) and so moves its
`stateVersion` (T2.2's trigger stamps any change except the compaction fold; OR-35) — T6.4, T6.8. ⚠️ Measured by
the second review (M-1): today `apply_request` has **no** classifier column, and `land()` persists only the state
(`ApplyExecutor.java:771-774`). Without V011's column a give-up would change nothing stamped and publish nothing.
⛔ **No error detail**: no message, no `ErrorClass` token (OR-4).

⛔ **`STOPPED`/`STOP_FAILED` are excluded (M10).** A stop may be instance-wide — `ApplyExecutor.admit` applies
`BE-D23`'s instance-wide refusal to `apply` only and deliberately not to a stop (`ApplyExecutor.java:146-157`,
`BE-D26`) — and OR-4 requires `entity.kind = "placement"` with a **real, required** `placementId`; the sentinel is not
a storable placement (`placement_not_sentinel`, T2.1). An instance-wide stop's outcome therefore has no legal
envelope, and OR-4 approves an **apply** outcome, not a stop outcome. ✅ **Ruled** (owner ruling 2026-09-29 (T0.2)):
stop outcomes are not in this event; **stops are REST-observed**. **The declared behaviour, written into
`contracts/events/README.md` and `observeApply`'s description:** after a `stop` returns `202 {applyId}`, the browser
polls `GET …/apply/observe?applyId=<id>` about every **2 s** until the item is terminal (`STOPPED`/`STOP_FAILED`),
and `instance.health` reflects the stopped container within one poll interval (T7.2). This is a **declared** poll,
not a degraded mode hidden behind a live claim; a stop notice is still a separate owner question if one is ever
wanted, listed in Non-goals.

**Topic:** ✅ **ruled** (owner ruling 2026-09-29 (T0.2)): today's gated topic shape; OR-6's re-addressing moves all
events together later. The reasoning, kept: OR-6's taxonomy (`sm/v1/{org}/{subOrg}/{env}/notify/solution/{solutionId}/instance/…`)
is **not landed** in `contracts/events/` (`NotifyTopics.java:35-44` still derives `sm/v1/{tenant}/notify/instance/…`),
and OR-46 lands only OR-4's leg. So the new event follows the **gated** taxonomy, exactly as its placement-scoped
sibling `instance.rollout` does: `sm/v1/{tenant}/notify/instance/{instanceId}/apply-outcome/{applyId}`. The OR-6
re-addressing of all seven events is **not** in this plan (non-goal) and must move them together.

**Config-state firing rule (OR-37) is documented in `contracts/events/README.md`**, not encoded in the schema: v1
emits `instance.config-state` only for an instance with exactly one live placement; with more, the instance rung is
the lowest across placements and the event fires only when that lowest rung moves.

- [x] **Step 1: Write the failing contract cases.** The fixture (valid), the three negatives (each must be
  rejected, each for its own reason: `kind = instance` with a `placementId`; a `payload.message`; no
  `placementId`) plus a fourth, `negative/apply-outcome-stop-state.json` (`state: STOPPED` — rejected by the enum),
  the topic line in `expected-topics.json`. In mesh-events:
  `NotifyEventShapesTest.applyOutcomeIsCatalogued()`, `NotifyTopicsTest.applyOutcomeTopicCarriesTheApplyId()`.
- [x] **Step 2: Run; check 10 and the mesh-events tests fail.**
- [x] **Step 3: Implement** — the enum member, the `oneOf` branch, the AsyncAPI channel, the Java shape and topic rule.
- [x] **Step 4: Run green.** `./contracts/run-checks.sh --strict` · `VERIFY mesh-events`.
- [x] **Step 5: Prove they fire.** Remove `required: [placementId]` from the branch → the no-placement negative
  passes → check 10 red. Remove the `event` enum member but keep the branch → the fixture fails → red. Add `STOPPED`
  to the payload enum → the stop-state negative passes → red. Revert.
- [x] **Step 6: Owner review of the event-contract diff.** *(2026-10-05: replaced by the slice review under the owner's execution model, `OR-70` — "the opus review is per … slice … once", then one combined review at the end; not held per task.)* The two design points (`APPLY_INDETERMINATE`; the gated
  topic) and the stop exclusion are **already ruled** (2026-09-29); the review checks the diff and the README's
  declared ~2 s stop-observe poll say what was ruled.
- [x] **Step 7: Commit.** `contracts(events): instance.apply-outcome — OR-4's approved placement-scoped notice`

**Results (T0.2, 2026-10-05, on `integration/dp-remote-demo`).** The seventh event landed exactly as `OR-4`/`OR-58` state it:
`instance.apply-outcome`, `entity.kind = placement` with a required `placementId`, `payload = {applyId, state}` closed, `state` = the
eight apply outcomes (the seven terminal apply states plus `APPLY_INDETERMINATE`), stops excluded, the gated topic
`sm/v1/{tenant}/notify/instance/{instanceId}/apply-outcome/{applyId}`. `contracts/events/README.md` declares the ~2 s stop-observe
poll, the `OR-37` config-state firing rule and `OR-35`'s `stateVersion` rule (the envelope description now says it). Four negatives,
each pinned to its own reason in `validate-events.py` and in `NotifyEventShapesTest`; the level-safety self-test now plants
`applyId` on both apply-addressed events. Step 2 was seen red on both sides before Step 3. Step 5's three mutations fired (check 10
red; the Java `SchemaMirrorLockTest` also red under the third) and were restored byte-for-byte. Verified:
`./contracts/run-checks.sh --strict` green; `mvn -o -f backend/pom.xml -pl mesh-events -am verify` (159 tests); dp-core's
`EventPublisherContractTest` (consumes the same fixtures, 15 tests). Not done here (outside this task's file list, routed to T0.3):
`docs/design/common/contracts/console-integration.md` §6/§9 still say "approved, contract NOT landed" and `ENDPOINT-INVENTORY.md`
is untouched.

---

### Task T0.3 — Records: the `stateVersion` correction, plan 008's amendment, OR-44's runbook line

**Wave:** W0, after T0.2. **Parallel:** ⛔ none (central register).

**Files:** Modify `docs/design/DECISIONS.md` (one new `BE-D` row), `docs/design/backend/decisions.md` (its body),
`docs/design/backend/design.md` (a pointer at the superseded sentence — the sentence itself is not a decision and
may carry a "superseded by" marker), `docs/plans/backend/008-placement-registry-and-apply-journal-identity.md`,
`docs/runbooks/install-prerequisites.md`, `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/TenantEnumerationRepository.java`
and `…/worker/ApplyWorker.java` (javadoc only).

- [x] **Step 1: Record the OR-35 correction** as the next free `BE-D`: *"`stateVersion` comes from one `sm_data`
  sequence (`sm_data.state_version_seq`), stamped by trigger on every owning row when it changes — `instance_state`,
  `config_generation`, `placement_health`, `apply_request` (new column), `fleet_action` — never on the immutable
  `placement` row. **A write that changes nothing an observer can see does not stamp** (the `apply_request`
  compaction fold, which rewrites only `attempt_summary`; a health probe that confirms the same state).
  **Commit-order rule:** every stamping transaction first takes the owning `instance_state` row lock (for
  `fleet_action`, its own row lock), so within one instance stamp order equals commit order and a
  `sinceStateVersion` backfill cannot skip a late committer. Lock order is `apply_scope → instance_state → (row)`,
  and a copy-forward and the submit that follows it are two transactions. Supersedes `backend/design.md` §3's 'the config generation where
  one exists' (`design.md:96-97`), which is measured defective: one generation emits several `instance.config-state`
  notices and every notice after the first would be discarded."* ⚠️ The commit-order rule is this plan's
  **reading** of OR-35 (a single sequence alone does not give commit order across concurrent transactions); the
  review must confirm it.
- [x] **Step 2: Amend plan 008** with a dated header note: Task 1's registry is delivered by plan 010 T2.1 **with
  `target_id`** (OR-29); its lookups bind `(tenant, instance, placement)` (the v2 paper's F20); its premise citing
  OR-2's superseded half (`008-…md:15-17`) is corrected to OR-16; its migrations renumber to the next free `sm_data`
  numbers after plan 010's allocation (**V015** onward — V011 is now T6.8's, see the allocation note).
- [x] **Step 3: OR-44 in code and runbook.** Replace `TenantEnumerationRepository`'s "must still be RAISED as a
  `DECISIONS.md` open item" wording (`:41-44`) and the matching note on `ApplyWorker.pollOnce` with a citation of
  **OR-44**, and name the **second** exception (owner ruling 2026-09-29 (Q-T), `OR-49`) as the only other one
  — its method is written by T2.4 Step 3b, not here. Add to `install-prerequisites.md`: one SM replica; on Kubernetes `strategy: Recreate` (fill **M10**
  first and cite it), because `WorkerSingletonGuard` makes a `RollingUpdate` unable to complete (plan 007 Results
  round 2 finding 7).
- [x] **Step 3b: Cite the 2026-09-29 rulings, do not draft questions.** The three former owner questions and the S0
  design points are **ruled** ([Owner rulings](#owner-rulings-2026-09-29--the-former-gating-questions)); the
  integrator registers them in `DECISIONS.md` as `OR-*` rows and has substituted them in this plan (OR-48 to OR-59, done 2026-09-29). This step
  only checks that each record T0.3 writes (the `BE-D` body, plan 008's note, the runbook line) cites the ruling by
  label, and that none still describes a ruled item as open. ⚠️ The verify-then-retry ruling **supersedes plan 005's
  "resume never touches `APPLY_INDETERMINATE`"** (`BE-D12`/`BE-D16`, **PROPOSED**) — T6.8 records that supersession;
  T0.3 does not pre-empt it.
- [x] **Step 3c: Name the fourth caller.** The javadoc of the second list also names plan 011's `MetricsCollector` as the fourth, named caller (owner ruling M-Q3, `OR-72`, which amends `OR-49`) — as a note that plan 011 adds it, since nothing in this plan calls the list for it.
- [x] **Step 4: Verify.** Every markdown link resolves (`python3 - <<'PY'` link check over the four touched docs, or
  the repo's own link checker if one exists — find it first); `VERIFY dp-core` compiles with the javadoc change.
- [x] **Step 5: Commit.** `docs: record the stateVersion correction (OR-35), amend plan 008 (OR-29), cite OR-44`

**Results (T0.3, 2026-10-05, at `690993f` on `integration/dp-remote-demo`).** Steps 1–5 done. `BE-D27` records OR-35's `stateVersion`
correction (pointer markers at `design.md` §3); plan 008 carries its dated amendment (V015 onward); `TenantEnumerationRepository` and
`ApplyWorker` javadoc cite OR-44 and name OR-49/OR-72 (javadoc only — the slice review confirmed it by grep); the install runbook mandates
one replica and `Recreate` with M10 measured (its quote was corrected the same day to the source's actual words, I2). Link check over the
touched documents: only links broken before this task (agent-gateway, the D10 demo waiver). No code behaviour changed, so no mutation proof
belongs to this task. ⚠️ **Flag carried:** `BE-D27` rule 3 (commit-order) is this design's reading of OR-35, not the owner's wording, and
stands as design until T2.1's review confirms it with a two-thread interleaving test.

---

### Task T0.4 — The dependency licence gate (`REQ-014`, invariant `I8`) — in two parts

**Why here:** `I8` applies to every dependency this plan adds (T3.0's JSON-Schema library first), so the gate must
exist before the first new dependency lands, and the existing tree must be audited once. **Split (M-8):** the audit
is certain to find licences `REQ-014` sends to the owner. Measured in `~/.m2`: logback, JUnit 5, jakarta.annotation,
BouncyCastle and HdrHistogram all declare names off the allow-list (Q-L1). The audit therefore runs **in W0, before
the owner sitting**, so the owner decides from a measured list. The **gate** runs in **W1**, as the first step of
the T3.0 agent: it blocks only the first new dependency, so it must not hold the sequential W0.

**Scope, settled by `REQ-014`'s text, not by this plan:** *"ships **or builds with** — direct **and** transitive"*.
So compile, runtime, provided **and test** scope are in, and so are the **build plugins** with their own
dependencies. This task's plugin is one of them, and it checks itself.

#### T0.4a — the audit (W0, after T0.1a, before T0.1 Step 7) — report only

**Files:** Create `docs/tasks/licence-audit-2026-09.md`: the measured report of the current reactor, with every
artifact in every scope and every build plugin, its version, its declared licence name(s) and the source of the
reading. **No `pom.xml` change is committed in T0.4a.** The plugin is run from the command line, or from an
uncommitted local edit that is reverted.

- [x] **Step 1: Fill M17** (i)–(v) by WebFetch. **Ask the user for the one online resolution** the plugin and the
  report need. ⛔ Do not drop `-o` on your own.
- [x] **Step 2: Audit.** Run the report over the whole reactor, covering every scope and the plugins. Record each
  artifact against the allow-list: **allowed**, **first-party** (JCSMP, the WebApp SDK — `REQ-014`'s named
  exceptions), **needs an owner decision** (anything else, including a multi-licence POM under Q-L2 (a)), or
  **refused** (GPL/AGPL, SSPL, BSL/BUSL, source-available or commercial-only). An artifact whose POM declares
  **no** licence is **needs an owner decision**, never "allowed". Nothing is silently allow-listed.
- [x] **Step 3: Commit** the report: `docs: the measured licence audit of the backend reactor (REQ-014)`. It is
  presented at T0.1 Step 7 (Q-L1, Q-L2).

**Results (T0.4a, Steps 1–2 done, 2026-09-29, dispatched on `integration/dp-remote-demo` at `953ef44`,
uncommitted — the integrator commits).** Step 3 (commit) is not this dispatch's to close.

- **Step 1** (M17): filled in the [Measured facts](#measured-facts--fill-each-before-the-task-that-needs-it)
  table and in full in the audit report. Headline: the candidate plugin (`org.codehaus.mojo:license-maven-plugin:2.7.1`)
  is itself `LGPL-3.0` — a new fact, not previously flagged, that only matters if T0.4b adopts this exact plugin
  permanently. Its `add-third-party`/`aggregate-add-third-party` goal gates via `failOnMissing` +
  `failOnBlacklist` (both default `false`, must both be set `true`); ~~matches by **exact string**, never regex,
  against licence names~~ — **corrected in round 3**: the *licence-name* comparison itself is exact-string only,
  but the plugin's own `excludedGroups`/`includedGroups`/`excludedArtifacts`/`includedArtifacts` parameters (on
  the same goal T0.4b binds) **are** unanchored-substring regexes — see the audit report's M17(iv) — so "never
  regex" was true of one comparison and false of the plugin overall (folded first by exact-string
  `licenseMerges`); handles a multi-licence POM with **"any
  one licence on the allow-list passes"** semantics — the opposite of Q-L2 option (a)'s "every licence must be
  allow-listed," so option (a) needs a custom check the native mojo cannot express; and structurally cannot see
  **build plugins** (`project.getArtifacts()` excludes them) while it **does** see test scope by default
  (`excludedScopes` defaults to `system` only). Full detail, with every source citation, in the audit report.
- **Step 2** (audit): ran `aggregate-add-third-party` once online (the plugin resolved into `~/.m2`; every later
  Maven command in this dispatch ran `-o`), ~~covering all 8 reactor modules~~ — **corrected in round 2**: the
  reactor is 9 modules plus the parent (10 total, matching the build log's `[n/10]`) — and every scope but
  `system`. 123 distinct artifacts; zero with no licence declared; ~~zero refused-family licences~~ — **retracted
  in round 2**: `jakarta.annotation-api`'s `GPL2 w/ CPE` half is a `GPL-*` name, an open owner question, not a
  clean zero (see "Refused-family licences found" in the audit report). Classified: ~~90 allowed outright~~ —
  **corrected in round 2**: ~83 of **116 actual third-party artifacts** (the "123" figure double-counted 7 rows
  that are Solution Manager's own reactor modules under an inherited Apache-2.0 label; ~90 came from counting
  those 7 as allowed too) (six different literal spellings of Apache-2.0, plus MIT/BSD-2-Clause/BSD-3-Clause), 1 first-party
  (`sol-jcsmp`, the named JCSMP exception), and **9 exact-coordinate lines needing an explicit owner decision**
  (now **13**, after round 2's 4 new and round 3's 4 further new — see the audit report) —
  5 corrected/re-confirmed from the plan's prior Q-L1 list (logback, jakarta.annotation-api, JUnit 5 EPL-2.0, plus
  BouncyCastle now measured as **two** version families and JUnit **4**'s separate EPL-**1.0** line) and 4 new
  (`net.java.dev.jna:5.13.0` dual Apache-2.0/LGPL-2.1-or-later; `jakarta.activation-api:2.1.4` and
  `jakarta.xml.bind-api:4.0.5`, both "Eclipse Distribution License - v 1.0" — verified via the Eclipse
  Foundation's own page as SPDX `BSD-3-Clause`; and ArchUnit's unqualified "BSD" label, evidenced by its own POM's
  licence URL to be ASM's `BSD-3-Clause`). **Correction against the plan's own prior measurement**: `HdrHistogram`,
  named in Q-L1, is **not actually resolved by this reactor at all** (absent from the 123-artifact report and from
  `dependency:tree -Dincludes=org.hdrhistogram:HdrHistogram` across every module) — its `~/.m2` presence was
  residual from something else on the machine. Build plugins (`maven-failsafe-plugin`, `maven-jar-plugin`,
  `spring-boot-maven-plugin`, plus the unnamed default-lifecycle plugins) were audited separately outside the
  mojo's reach, per M17(v); `spring-boot-maven-plugin` and the two ASF plugins checked are Apache-2.0 by direct
  source read, ~~the remaining default-lifecycle ASF plugins were left unverified rather than assumed from the
  pattern~~ — **fixed in round 2**: all six were individually fetched and confirmed Apache-2.0 too (see the audit
  report's "Build plugins" section). Nothing was allow-listed, exception-listed, or decided by this dispatch —
  every "needs a decision" item is left open for the owner sitting (T0.1 Step 7), exactly as the task requires.
- **Files touched:** `docs/tasks/licence-audit-2026-09.md` (new, uncommitted); this plan file (M17's status row,
  this Results block, Steps 1–2 ticked). No `pom.xml` anywhere in `backend/` was touched — the plugin ran as a
  bare command-line goal invocation, never added to any POM, so there was nothing to revert.

**Round 2 (review disposition, same date, no new online resolution used).** An independent review returned 5
MAJOR + 2 MINOR findings against the round-1 report; all 7 were confirmed against the plugin's own source
(re-read) and fresh offline measurement, and all 7 are fixed in `docs/tasks/licence-audit-2026-09.md` — see that
document's own "Disposition of the second review" section for the point-by-point record. Headline corrections:
(1) two of the plugin's own `@Parameter`s (`excludedGroups`/`includedGroups`/`excludedArtifacts`/
`includedArtifacts`) are unanchored-substring regexes on the very goal T0.4b binds, not a different goal as
round 1 said; (2) a no-licence dependency *does* enter `licenseMap` and *is* caught by `checkForbiddenLicenses`
once the allow-list is armed, correcting round 1's mechanics (T0.4b's actual required flag combination is
unchanged: `failOnBlacklist=true` with a non-empty `includedLicenses`); (3) build-plugin versions are now
resolved offline (`mvn -o dependency:resolve-plugins`) rather than read from `~/.m2` residue, surfacing two new
off-list build-tool-only dependencies (`org.tukaani:xz:1.9` Public Domain; `net.java.dev.jna:jna`/`jna-platform`
`5.17.0` Apache-2.0+LGPL-2.1-or-later) now added to the owner's open list; (4) "zero refused-family licences" is
retracted — `jakarta.annotation-api`'s `GPL2 w/ CPE` half is now an open owner question, not a clean pass; (5) a
full 123-row per-artifact, per-module, per-scope appendix is now committed in the report (previously only in the
ephemeral scratchpad), splitting out the 7 rows that are Solution Manager's own reactor modules (inherited
Apache-2.0, not third-party) from the 116 actual third-party artifacts. Nothing in this round changes the task's
scope or its "approves nothing" boundary: every corrected/new item is still left open for the owner sitting
(T0.1 Step 7), and Step 3 (commit) is still not this dispatch's to close.

**Round 3 (review disposition, same date, no new online resolution used).** A third independent review returned
3 MAJOR + 3 MINOR findings; five are confirmed and fixed, one MINOR sub-claim (an exact row count — the underlying
point stands) is disputed with evidence — see `docs/tasks/licence-audit-2026-09.md`'s "Disposition of the third
review" section for the point-by-point record. Headline corrections: (1) `failOnMissing`/`failIfWarning` are
**not** a softer WARN-only report as round 2 said — `checkMissing()` throws `MojoFailureException` on a no-licence
dependency independently of `includedLicenses`, so `failOnMissing=true` alone fails the build on that case; (2)
the build-plugin transitive audit was still incomplete — `org.jdom:jdom2:2.0.6.1`, `org.antlr:antlr4-runtime:4.7.2`,
`com.github.luben:zstd-jni:1.5.5-11` and, in a different mechanism entirely, the Surefire JUnit-Platform test
**provider** classpath's `org.junit.platform:junit-platform-launcher:1.12.2` are four more exact-coordinate lines
the owner needs, taking the open count from 9 to **13**; (3) M17(i)'s "own runtime dependencies" was left
unchecked while M17 was marked "✅ filled" — eight specific artifacts are now individually verified (two, `xalan:serializer`
and `plexus-i18n`, have no licence in the POM chain measured at all; `nuiton-processor` is `LGPL-3.0`;
`sisu.inject` is `EPL-2.0`; `xml-apis` declares three licences at once) and **M17's status is downgraded to
"partially filled"** because the plugin's wider 72-artifact class realm is still not exhaustively checked; (4)/(5)
two leftover contradictions from round 2's own text (an unstruck sentence in M17(iii), and a BouncyCastle
disposition bullet that wrongly applied `launcher`'s mixed `1.86`/`1.80`/`1.80.2` set to `demo-launcher`, which
actually resolves a clean `1.80`-line set) are corrected. **Disputed:** the finding that Appendix A.1 has "exactly
84" pure-Apache rows — re-counted twice, independently, at **83**, matching this report's own figure; the finding's
other half (the ASF build-plugin evidence understates itself — the effective POM, via `org.apache:apache:32`, does
carry an explicit `<licenses>Apache-2.0` element, not just a file header) is confirmed and fixed regardless.
Nothing in this round changes the task's scope or its "approves nothing" boundary: every corrected/new item is
still left open for the owner sitting (T0.1 Step 7), and Step 3 (commit) is still not this dispatch's to close.

**Rounds 4–9 and the commit (integrator, 2026-09-30).** Six further independent Opus reviews ran (rounds 4–9; fixes
by Sonnet, the last two by Sonnet 5.5). Each round found embedded third-party content one layer deeper: shaded
classes, bundled public-domain code, data files and appended licence sections inside otherwise-allowed jars. The
consolidated owner table grew from 25 rows to **43**: 40 from the implementer, plus rows 41–43 added by the integrator
from round 9's three MAJORs. Those three are the IT container images, including `busybox:1.36` (GPL-2.0 only,
re-verified at busybox.net); `caffeine:3.1.8`'s public-domain `StripedBuffer`; and the W3C, Sun and IBM-authored
files in the shipped Tomcat jar. **The loop did not converge**, and at the owner's instruction ("Commit with open
list") the report is committed with round 9's eight findings listed **open** at its head. It claims neither
completeness nor any approval. All 43 rows and the open findings go to Q-L1/Q-L2 at T0.1 Step 7.

#### T0.4b — the gate (W1, first step of the T3.0 → T3.1 agent)

**Depends on:** T0.4a, and Q-L1/Q-L2 — ✅ **answered** at T0.1 Step 7 (`OR-66`, `OR-67`, `OR-68`; summarised below).
**Parallel with:** W1. It owns `backend/pom.xml`'s
build-plugin section. No other W1 task edits `backend/pom.xml`; T3.0 edits `cp-core/pom.xml`.

**What the owner ruled (verbatim in the rulings file; registered as `OR-66` to `OR-68`) and this task builds:**
- **Q-L2 = (a′) "Elect stated choice":** where an artifact's own licence text states a choice (SPDX `OR`, or "at your option") the gate
  elects the allowed option and **records the election**; with no stated connective it **fails closed** and the owner approves. This
  **replaces** step 1(d)'s "every declared licence must be allowed".
- **Approvals by exact coordinate and version** in `backend/licence-exceptions.txt`, each with its reason, scope and ruling id:
  groups A and B (rows 1, 2, 27; 3, 6, 26, 31, 35, 36, 39, 43 of [`licence-audit-2026-09.md`](../../tasks/licence-audit-2026-09.md)'s consolidated
  table; `OR-66`); group C, test-only (rows 4, 5, 7, 8, 9, 10, 28, 29(a), 33, 34, 37(c)) and group E, build-tool-only (rows 11 to 25,
  29(b..), 30, 32, 37(a)(b), 38, 40, 42), scoped test-only / build-tool-only (`OR-67`); **explicit owner waivers for row 19
  (`xalan:serializer:2.7.3`) and row 20 (`plexus-i18n:1.0-beta-10`)**, whose POM chains state no licence — each waiver recorded as a
  waiver, never as a licence name; **row 41, the test images, as tools:** `busybox:1.36`, `postgres:14` and the Testcontainers `ryuk`
  image approved by exact tag, test-only.
- **The gate tool stays `license-maven-plugin:2.7.1`.**
- **Tool scope (`OR-68`):** the Maven installation, the JDK and the never-run `site`/`deploy` plugins are outside `REQ-014`'s "builds with".
  **Add a Maven Wrapper** (`backend/.mvn/wrapper/…`, `mvnw`, `mvnw.cmd`) — ⛔ **its licence is verified first, by WebFetch of the
  wrapper's own licence text (`I2`), before it is added**; if it is not on the allow-list the task stops and reports. **Record the JDK
  version** the build is pinned to (the measured one — read it from the toolchain, do not recall it — in a file T0.4b names in its
  commit).
- **SM's own licence (`OR-68`):** `backend/pom.xml` declares an **explicit Solace proprietary `<licenses>` block**, landing in this
  task's commit.

**Files:** Modify `backend/pom.xml` (the licence plugin, bound to `verify`, with the allow-list; and the proprietary `<licenses>` block). Create
the Maven Wrapper files (after the licence check above). Create
`backend/licence-allowlist.txt`: Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC and the PostgreSQL Licence, the
allow-list `REQ-014` records as confirmed by the owner 2026-09-29. It also holds the explicit name mapping, e.g.
"The Apache Software License, Version 2.0" → `Apache-2.0`, as **exact strings**. Create
`backend/licence-exceptions.txt`: the first-party Solace libraries and every Q-L1 approval, each by **exact
coordinates**, with its reason, its scope and the ruling that approved it.

- [x] **Step 1: Gate.** The build fails on a resolved artifact, in any scope or a plugin, when:
  (a) a declared licence is outside the allow-list and the artifact is not in the exceptions file;
  (b) its POM declares **no** licence and it is not in the exceptions file (**unknown fails**);
  (c) its licence name is not an exact entry in the mapping (**an unmapped name fails**); or
  (d) under Q-L2 **(a′)** (`OR-66`): an artifact declares several licences and its own licence text states **no** choice (no SPDX `OR`, no
  "at your option"), and any one of them is off the allow-list — a stated choice instead elects the allowed option and records the election.
  Name normalisation and exclusions match **exact** strings and coordinates, never a substring or an unanchored
  regex (M17 (iv) measures what the plugin supports; where it only offers a regex, every pattern is anchored `^…$`
  and quoted, and a test proves a near-miss does not match). If the plugin cannot see part of M17 (v), a second
  check covers that part: test scope, or plugins.
- [x] **Step 2: Run green** on today's tree plus the Q-L1 approvals: `VERIFY arch-tests` (every production module)
  and `VERIFY demo-launcher`.
- [x] **Step 3: Prove it fires** (each: see red, revert, see green, record).
  - A throwaway test-scope dependency under a refused licence → `verify` red.
  - Remove a first-party exception line → red on JCSMP.
  - A throwaway dependency whose POM declares **no** licence (a local `file:` repository artifact made for the test)
    → red.
  - Change one mapping entry by one character (e.g. `Apache-2.0` → `Apache-2.1` on its right-hand side, or a trailing
    space on its left-hand side) → red on every artifact that used it.
  - Under Q-L2 (a′): HdrHistogram declares two licences (`CC0` and `BSD-2-Clause`, Q-L1's measured names); **whichever way T0.4b's
    reading of its licence text goes (a stated choice, or none), the outcome is recorded** — if no choice is stated, remove its
    approval → red even though it also declares `BSD-2-Clause`; and a throwaway artifact whose licence text states "at your option" with one allowed option → green with
    the election **recorded**; the same artifact with one character of the connective changed → red.
  - Remove the row 19 waiver → red on `xalan:serializer:2.7.3`, and the report names a **waiver**, not a licence.
  - Remove the proprietary `<licenses>` block from `backend/pom.xml` → a test that asserts it is present goes red.
- [x] **Step 4: Commit.** `build: the I8 dependency licence gate (REQ-014)`

**Results (T0.4b, 2026-10-05, on `integration/dp-remote-demo`; Steps 1–4 done, with one owner question open).** Committed as `build: the I8 dependency licence gate (REQ-014)` and `build: close the slice review of the I8 licence gate (T0.4b)`. Files: `backend/pom.xml` (`license-maven-plugin:2.7.1` bound to `verify` in a shipped and a test execution, every filter pinned, plus the one `Proprietary - Solace` `<licenses>` block), `backend/licence-allowlist.txt`, `backend/licence-exceptions.txt` (exact coordinate + version lines, with the owner waivers for rows 19 and 20 recorded as waivers), `backend/licence-artifact-filters.txt`, `backend/licence-third-party.ftl`, `backend/scripts/check-licences.py` with `backend/scripts/test-check-licences.sh`, the Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/…`, `.gitattributes`), a README section, and `contracts/run-checks.sh` steps 13 and 13b. The JDK the build is pinned to is recorded in `backend/README.md` (Build prerequisites).
- **Gate.** The Maven gate is green on today's tree: `mvn -o -DskipTests verify` over all nine modules (it ignores `-DskipTests`). The script's self-test passes its scenarios, including 14 added by the slice review.
- **Mutation proofs** (each red, restored byte-for-byte, green): a refused-licence test dependency; the JCSMP line removed; a dependency with no licence; a one-character mapping change (`Apache-2.0` → `Apache-2.1`, and `Version 2.0` → `2.1`); jna 5.13.0's approval removed (stands in for HdrHistogram, which is not in this tree); a multi-licence dependency with no election; an exception version changed; a test-only approval used in shipped scope; a fake "Solace first-party" claim; the plugin disabled by `-Dlicense.*` flags (20 flags and a six-filter combination stay red once every filter is pinned); a system-scope jar; a removed xz:1.9 approval and a bumped plexus-utils version against the real tree; the proprietary block removed (script red, run-checks plant red); rows 19/20 waivers missing, stale, or used on a licensed artifact.
- ⚠️ **Open for the owner: `org.eclipse.sisu:org.eclipse.sisu.plexus:0.9.0.M3`** (EPL-2.0, build-tool), a sibling of audit row 18 that the audit missed. It is **not approved**. A pending note in `licence-exceptions.txt` holds the exact line to add. Until the owner rules, `check-licences.py` and `run-checks.sh` stay red on that one coordinate; after the ruling the integrator adds the OR-* and audit row 18(b).
- **Deviations and residuals, for the owner.** (1) The plugin passes any dependency with at least one allow-listed licence, so Q-L2 is enforced in the FreeMarker template. A stated choice (`OR-66`) is recorded as an exception line citing `OR-66` with no new ruling; the two automatic-election proofs of Step 3 are **not built** (the jna election is proved instead). Owner to confirm. (2) The plugin cannot see build plugins or system-scope jars; `check-licences.py` covers both and is wired as run-checks 13/13b, not into `verify`. (3) `includedTypes=jar,test-jar,pom,bundle`; an artifact of another type is not gated. (4) The 13 Appendix B spelling variants are mapped as the plan text prescribes; the proprietary name uses an ASCII hyphen. (5) Maven's `.sha512` is recorded in `maven-wrapper.properties` but not enforced (the wrapper checks SHA-256 only). (6) The wrapper plugin's Apache-2.0 licence was read via its parent chain and the repository LICENSE. (7) Wrapper resolution is online on first use; who authorised that is not established and needs recording.

⚠️ **Frontend and go-agent are out of this plan's scope.** `REQ-014` needs the equivalent npm (and Go) gate there,
recorded as remaining work in T12.1.

---

## S1 — The Java `project()` (OR-34)

✅ **Landed early (the integrator's slice, 2026-09-29, commit **`0718603`** — in `HEAD`'s history; T1.2's
remainder cites it).** The port exists in `cp-api`/`cp-core` **designer** packages, not the `projection` packages this plan
first named, and with typed models rather than canonical-JSON strings. **The landed code wins; this plan follows its
names.** Measured in the working tree:

| Landed file | What it is |
|---|---|
| `cp-api/.../cp/api/designer/DesignerProjector.java` | the `project()` port interface (pure; `PackageModel.SolutionPackage`, `StateModel.ComponentSelection`, draft `Map`, `StateModel.ObservedState`, catalog `Map`, requested package version) |
| `cp-api/.../cp/api/designer/DesignerStageValidator.java` | OR-34's stage-time re-projection check: re-projects server-side, refuses `UndeclaredDraftKey` / `ValueMismatch` / `MissingFromClaim` / `UnexpectedInClaim`, returns the **server's** result to store |
| `cp-api/.../cp/api/designer/{PackageModel,StateModel,ViewModel}.java` | the Java mirror of `types.ts` (`ViewModel.ENGINE_VERSION` included) |
| `cp-core/.../cp/designer/{DesignerProjectorImpl,DesignerStageValidatorImpl,PackageJsonReader,ViewModelJson}.java` | the port (657 lines), the validator, the package reader, the view-model → JSON tree writer |
| `cp-core/src/test/.../cp/designer/{DesignerProjectorFixtureTest,DesignerProjectorRulesTest,DesignerStageValidatorTest,PackageJsonReaderTest}.java` | the golden test over the 33 shared fixtures (`thereAreExactlyThirtyThreeSharedFixtures` is its own test), rule tests, validator tests |

**Reported 33/33 by the integrator; not re-measured by this plan edit** (a build was running in the worktree). T1.2's
remainder re-runs it and records the count.

⚠️ **What the landed golden test proves, stated exactly.** `DesignerProjectorFixtureTest` compares the port's output
tree to the fixture's `expected` block with `Map.equals` / `List.equals` — its own javadoc says **map key order is
insignificant** and list order is significant. That proves **structural** equality (including `engineVersion`, which
every fixture's `expected` carries and `ViewModelJson` emits — so the planned `EngineVersionLockTest` is already
covered), not the **byte** identity this plan asked for, where key order is part of the contract
(`fixtures.ts:27-30`). Acceptance row 4 is worded to what is proven until T1.2's byte-level check lands.

### Task T1.1 — `cp-api` projection value types — ✅ landed as `cp.api.designer`; remainder: the shape-mirror test

**Wave:** W1. **Depends on:** nothing now (the types are landed). **Parallel with:** T2.0, T2.1, T2.3, T3.0/T3.1.

✅ **Done by the landed slice:** the value types and the port interface (`DesignerProjector`, `PackageModel`,
`StateModel`, `ViewModel`). ⛔ This plan's earlier `ConfigProjectionService` / canonical-JSON-string interface is
**not** built — every later reference (T5.3) uses `DesignerProjector` + `DesignerStageValidator`.

⏳ **Remaining:**

**Files:** Create `backend/cp-api/src/test/java/com/solace/sm/cp/api/designer/ViewModelShapeMirrorTest.java`.

- [x] **Step 1: Failing test.** `ViewModelShapeMirrorTest` reads `contracts/view-models/src/types.ts` (path relative
  to the reactor, as `SchemaMirrorLockTest` reads `console.yaml`) and asserts every `readonly` property of
  `ViewModel`, `FieldView`, `RenderedEntry`, `StatusView`, `Diagnostic` has a record component of the same name in
  `cp.api.designer.ViewModel`'s nested records — so a field added to the TS engine without the Java type fails
  here. (It fails first if any landed record has drifted; that is a finding, fixed in the port, not in the test.)
- [x] **Step 2: Run.** **Step 3:** fix any drift it reports. **Step 4: `VERIFY cp-api` green.**
- [x] **Step 5: Prove it fires.** Delete one component (e.g. `RenderedEntry.renderedPath`) → red. Revert.
- [x] **Step 6: Commit.** `test(cp-api): the designer view-model types are locked to view-models/types.ts`

**Results (T1.1, 2026-10-05).** `324e70e` `ViewModelShapeMirrorTest` over 11 output interfaces plus `StatusView.version`/`replicas`; no drift found; deleting `RenderedEntry.renderedPath` turned it red. S1 review (`cdbe6d8`) closed three measured blind spots: `ConfigState` is now mirrored; `readonly` is required on every property (a non-readonly property used to fall out of the scan); the `FieldValue` union arms are compared with the sealed interface's permitted records. Each proven by the matching `types.ts` edit (fifth arm, extra `ConfigState` property, non-readonly property: red), then reverted. It reads the listed interfaces and one nested level only.

### Task T1.2 — the `project()` port in `cp-core` — ✅ landed; remainder: byte-level check, mutations, Spring wiring

**Wave:** W1/W2 (same agent as T1.1's remainder). **Depends on:** T1.1's remainder. **Parallel with:** everything
outside `cp-core`'s `designer` package.

✅ **Done by the landed slice:** the function-by-function port (`DesignerProjectorImpl`), the stage validator, and
the structural golden test over all 33 fixtures plus the fixture-count test. ⛔ `hashInput` is **not** ported, and
**no longer needed server-side**: T5.3 refuses a mismatch through `DesignerStageValidator`'s structured reasons
rather than comparing `hashInput` bytes, and T5.2's `content_digest` is its own keyed hash (OR-32). `FE-D17`'s
`hashInput` stays the TS engine's (browser save/diff); the planned `HashInputParityTest` is dropped, with this
reason recorded in the commit.

⏳ **Remaining:**

**Files:** Create `backend/cp-core/src/main/java/com/solace/sm/cp/designer/DesignerConfiguration.java` (the two
beans); Modify `backend/cp-core/src/main/java/com/solace/sm/cp/config/ControlPlaneConfiguration.java` (one
`@Import` line — measured: the landed port declares **no** Spring bean); Modify or create
`backend/cp-core/src/test/java/com/solace/sm/cp/designer/DesignerProjectorFixtureTest.java` (the byte-level case) and
`DesignerConfigurationTest.java`.

- [x] **Step 1: Failing tests.** (a) `projectsTheFixtureBytesExactly` — serialize the port's tree with the same
  canonical writer rules the TS generator uses (`fixtures.ts` `canonical()`: 2-space indent, key order as emitted)
  and compare **bytes** with the fixture's `expected` block, for all 33. (b) `theContextHasExactlyOneProjectorAndOneStageValidator`.
- [x] **Step 2: Run; they fail** ((a) fails if and only if key order drifts — if it passes first time, record that
  and run Step 5's key-order mutation to prove it can fail).
- [x] **Step 3:** fix any key-order drift in `ViewModelJson`; add `DesignerConfiguration` + the `@Import` line.
- [x] **Step 4: `VERIFY cp-core` green (record the fixture count: 33); `VERIFY arch-tests` green.**
- [x] **Step 5: Prove they fire** — ⚠️ the landed slice records none of these; run them now. Swap the port's `cmp`
  (`project.ts:28`) for `String::compareTo` → report which fixtures go red. If none does, that is a **finding about
  the shared fixtures**, recorded in Results and routed as its own reviewed `contracts/view-models` change. It is
  ⛔ **not** fixed here: adding a fixture breaks OR-34's 33-fixture lock and `thereAreExactlyThirtyThreeSharedFixtures`,
  and `contracts/view-models/fixtures/**` has no owner in this plan (single-owner table). A Java-only unit test in
  `DesignerProjectorRulesTest` may pin the comparator meanwhile. Make `evaluate` coerce `"true"` to `true` → `predicate-strict-equality-no-coercion`
  red. Render a secret value → `secret-never-resolved-in-viewmodel` red. Emit one object's keys in a different order
  → (a) red while the structural test stays green (the reason (a) exists). Revert each.
- [x] **Step 6: Commit.** `feat(cp-core): the designer projector is wired and byte-identical to the TS engine on all 33 fixtures (OR-34)`

**Wiring into the stage endpoint stays with T5.3** (the landed `DesignerStageValidator` javadoc says the same: the
endpoint, persistence and the apply path are out of the port's scope).

**Results (T1.2, 2026-10-05).** `0a8a913` `DesignerConfiguration` + `@Import`; `ViewModelJson` `ActionView` key order fixed (26 of 33 byte cases failed before). Byte identity 33/33 (`DesignerProjectorFixtureTest` 100 = 1 count + 33 structural + 33 byte + 33 writer calibration). `HashInputParityTest` dropped (reason in the commit). Mutations, each red then reverted: `String.valueOf` fallback in `evaluate` → `aPredicateDoesNotCoerceAStringToTheLiteralItsTextSpells`; `FieldView` key order swapped → 33 byte, 0 structural; secret projected as `Value` → `secret-never-resolved-in-viewmodel` and `edge-both-secrecy-spellings`; `Collator(ENGLISH)` on the rendered sort → 4 `fm-*` fixtures, structural and byte. The literal `String::compareTo` swap cannot fail: the port already uses it, the same UTF-16 order as `project.ts`'s `<`. **Fixture-set finding:** the shared fixtures cannot tell coercion from strict equality. The slice review found a JS-loose `==` in `strictEquals` green across 120 tests; closed Java-only (`cdbe6d8`: three `DesignerProjectorRulesTest` cases + one `DesignerStageValidatorTest` case, all red under the mutation). **Route:** a separate reviewed `contracts/view-models` change adding coercion fixtures (single-writer; breaks the 33-fixture lock deliberately; not this plan). **Scope of the byte claim:** `ViewModelJson` (package-private, test-only today), not a served response; Jackson over the `cp-api` records would emit `enabled` before `visible` and has no `FieldValue` discriminator, so T5.3 must serialize through a `cp-api`-exposed writer backed by `ViewModelJson` or add a byte-level test at the HTTP boundary. Wiring: `DesignerConfigurationTest` runs the configuration alone; the booted-context check (exactly one `DesignerProjector`, `DesignerStageValidator`, `ReachAuthorization`) is in `ProductionAuthenticationIT`, proven by dropping the `@Import` (red).

### Task T1.3 — The reach-filtered list classification (S0 point 24; M1)

**Wave:** W1. **Depends on:** T0.1 merged (point 24 — ✅ ruled, owner ruling 2026-09-29 (S0-24), `OR-51`). **Parallel with:** T1.1/T1.2, T2.0, T2.1, T2.3,
T3.0/T3.1 (owns the gateway classification files, one new cp-api file, one new cp-core authz class + one `@Import`).

**Why (measured):** an organization-scoped CATALOG list is refused before its handler runs for any principal bound
below the organization — the interceptor `require`s the verb at the one resolved resource
(`AuthenticationInterceptor.java:131-158`), and `ScopeEvaluatingAuthorizationService.require` allows only a grant
whose path **authorizes** (is a prefix of) the resource's path (`ScopeEvaluatingAuthorizationService.java:44-50`;
`ScopeVisibility.java:6`: "authorization reach remains downward only"). An Operator bound at `[org, unit, env]` —
the plan's own `demo-operator` — gets `403` from `listInstances`, `listTargets`, `listSolutions` and the home reads.

**Files:** Create `backend/cp-api/src/main/java/com/solace/sm/cp/api/authz/ReachAuthorization.java`;
Create `backend/cp-core/src/main/java/com/solace/sm/cp/authz/{GrantReachAuthorization,ReachAuthorizationConfiguration}.java`;
Modify `backend/cp-core/src/main/java/com/solace/sm/cp/config/ControlPlaneConfiguration.java` (one `@Import`);
Modify `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/{OperationClassification,OperationPolicy,AuthenticationInterceptor}.java`;
Create tests `cp-core/.../authz/GrantReachAuthorizationIT.java` (as `sm_cp_runtime`), `console-gateway/.../{ReachClassificationTest,ReachOperationsMirrorTest}.java`.

```java
package com.solace.sm.cp.api.authz;
/**
 * S0 point 24. For list and fan-out operations whose items live at many scopes. ⛔ Not an isAllowed:
 * the pre-check throws, and the filter returns a subset — an unauthorized item is simply absent, so
 * there is no forgotten else (AuthorizationService's javadoc reason for having no boolean sibling).
 */
public interface ReachAuthorization {
    /** Throws AccessDeniedException unless the verb is held at >= 1 grant inside the principal's own organization. */
    void requireHeldAnywhere(PrincipalRef principal, Permission permission);
    /** The ids (of one kind, in the principal's organization) at whose own scope the verb is AUTHORIZED — downward reach. */
    Set<UUID> authorizedSubset(PrincipalRef principal, Permission permission, ResourceKind kind, Collection<UUID> ids);
}
```

`GrantReachAuthorization` reuses `AuthorizationGrantRepository`, `PrincipalOrganizationResolver` and the existing
`ScopePathResolver` (one bounded, tenant-bound lookup per call, not one `require` per item). `OperationClassification`
gains `Reach(Permission)`; `OperationPolicy` classifies exactly the operations `console.yaml` marks
`x-sm-scope: reach`; the interceptor calls `requireHeldAnywhere` for them and resolves no resource.

- [x] **Step 1: Failing tests.** `anEnvironmentBoundOperatorPassesTheReachPreCheck()`,
  `aPrincipalHoldingTheVerbNowhereIs403()`, `theSubsetUsesDownwardReachNotNavigationVisibility()` (an env-bound grant
  does **not** authorize its organization's own org-level resource), `aSiblingEnvironmentsIdIsNotInTheSubset()`,
  `anotherOrganizationsIdIsNeverInTheSubset()`, `theReachSetMirrorsConsoleYamlExactly()` (`ReachOperationsMirrorTest`
  reads `console.yaml`).
- [x] **Steps 2–4:** fail → implement → `VERIFY cp-core`, `VERIFY console-gateway`, `VERIFY arch-tests`
  (`gatewayUsesApisOnly` green: the gateway sees only the cp-api interface).
- [x] **Step 5: Prove they fire.** Use `ScopeVisibility.isVisible` in the subset → the downward-reach test red. Drop
  the organization bind → the other-organization test red. Classify one reach operation as `Catalog` → the mirror
  test red. Revert each.
- [x] **Step 6: Commit.** `feat: the reach-filtered list classification — list reads for principals bound below the organization (S0 point 24)`

**Results (T1.3, 2026-10-05).** `e2bb6bc` `ReachAuthorization` (`cp-api`) `requireHeldAnywhere`/`authorizedSubset`; `GrantReachAuthorization` (`cp-core`) uses `ScopePath.authorizes`, never `ScopeVisibility`; `OperationClassification.Reach` for the seven reach operations. Gateway 164 unit, `GrantReachAuthorizationIT` 12, `ControlPlaneConfigurationIT` 11, `HierarchyEndpointsIT` 6, `ProductionAuthenticationIT` 4. Mutations red then reverted: `ScopeVisibility.isVisible` in the subset; organization bind dropped (two tests); `inOrganization` filter dropped from the pre-check; `listTargets` classified `Catalog`; interceptor pre-check removed (3 cases); `ReachAuthorizationConfiguration` import removed (`HierarchyEndpointsIT` 6 errors). S1 review fixes: `29d7d5a` `findScopePaths(kind, organization, ids)` makes a subset one grant read + one organization read + one directory read (it was N+2), the organization bound in SQL (mutation: `GrantReachAuthorizationIT` red); `f33238d` `FakeAuthorizationService` records `authorizedSubset` calls and answers empty unless the test authorizes the id, and `SchemaMirrorLockTest` now pins every `ErrorCode` status to the OpenAPI prose (closes T0.1's `ENTITLEMENT_REFUSED` 409 gap; mutation to 403 red). **Open:** no handler calls `authorizedSubset` yet; T3.2+ handler tests must assert the call was made with the operation's own verb and that an unauthorized id is absent. Kinds without a scope directory return an empty subset.

---

## S2 — The DP inventory substrate, credentials and target governance

### Task T2.0 — `sm_control` V013: every CP table this plan needs, in one audited migration

**Wave:** W1. **Depends on:** T0.1 (ErrorCode/shape names only). Q-E is ruled (owner ruling 2026-09-29 (Q-E), `OR-48`), so its fourth edit is **unconditional**.
**Parallel with:** T1.x, T2.1, T2.3, T3.0/T3.1.

**Files:** Create `backend/cp-core/src/main/resources/db/migration/sm_control/V013__dp_remote_governance.sql`;
Modify `…/sm_control/CHECKSUMS.sha256`, `backend/cp-core/src/main/java/com/solace/sm/cp/persistence/ControlPlanePersistenceConfiguration.java`
(readiness: `READINESS_QUERY`/`EXPECTED_TABLES`, `AUDITED_TABLES`, `AUDIT_READINESS_QUERY`,
`TRUNCATE_REFUSED_TRIGGERS_QUERY`/`EXPECTED_TRUNCATE_REFUSED_TRIGGERS`, plus a V013 presence probe mirroring the
V012 one, `:181-195`), `backend/cp-core/src/main/java/com/solace/sm/cp/identity/AuditOperations.java`;
Create `backend/cp-core/src/test/java/com/solace/sm/cp/persistence/GovernanceSchemaIT.java`.

**The DDL (reviewable here; the migration is this, in this order):**

```sql
-- OR-27: the instance id is globally unique; the resource directory already looks it up alone
-- (JdbcResourceScopeDirectory.java:36-37) and fails closed on a duplicate. This makes the assumption structural.
ALTER TABLE sm_control.instance ADD CONSTRAINT instance_id_unique UNIQUE (instance_id);

-- OR-41 / OR-14: the org-scoped catalog entry, and the packages loaded under it.
CREATE TABLE sm_control.catalog_solution (
    organization_id uuid NOT NULL REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    catalog_solution_id uuid NOT NULL UNIQUE,
    solution text NOT NULL CONSTRAINT catalog_solution_name CHECK (solution ~ '^[a-z][a-z0-9-]{0,63}$'),
    created_at timestamptz NOT NULL,
    CONSTRAINT catalog_solution_pk PRIMARY KEY (organization_id, solution),
    CONSTRAINT catalog_solution_org_id UNIQUE (organization_id, catalog_solution_id));   -- target of the composite FK below
CREATE TABLE sm_control.solution_package (
    load_seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- the catalog entry's stateVersion source
    organization_id uuid NOT NULL,
    catalog_solution_id uuid NOT NULL,
    -- m9: the package's organization IS its entry's organization — structurally, not by convention.
    CONSTRAINT solution_package_entry FOREIGN KEY (organization_id, catalog_solution_id)
        REFERENCES sm_control.catalog_solution (organization_id, catalog_solution_id) ON DELETE RESTRICT,
    package_version text NOT NULL,       -- canonical text of the package's packageVersion (it is an array, e.g. [1])
    applies_to text NOT NULL,
    digest text NOT NULL CONSTRAINT solution_package_digest CHECK (digest ~ '^sha256:[0-9a-f]{64}$'),
    signature_status text NOT NULL CONSTRAINT solution_package_signature CHECK (signature_status = 'unsigned'),
                                         -- OR-40: signing is phase 2; the CHECK widens when it lands
    document jsonb NOT NULL,
    loaded_by uuid NOT NULL REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    loaded_at timestamptz NOT NULL,
    CONSTRAINT solution_package_once UNIQUE (catalog_solution_id, package_version));

-- OR-40: which solutions an organization may run, and how many instances of each.
CREATE TABLE sm_control.entitlement (
    organization_id uuid NOT NULL REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    solution text NOT NULL,
    max_instances integer NOT NULL CONSTRAINT entitlement_positive CHECK (max_instances > 0),
    set_by uuid NOT NULL REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    set_at timestamptz NOT NULL,
    CONSTRAINT entitlement_pk PRIMARY KEY (organization_id, solution));

-- OR-41: the environment's Solution scope node points at its catalog entry; one node per env per entry.
ALTER TABLE sm_control.solution ADD COLUMN catalog_solution_id uuid NULL
    REFERENCES sm_control.catalog_solution (catalog_solution_id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX solution_one_per_catalog_entry ON sm_control.solution
    (organization_id, organization_unit_id, environment_id, catalog_solution_id)
    WHERE catalog_solution_id IS NOT NULL;

-- OR-28: the target governance row. Connection details live in sm_data (T2.1), never here.
CREATE TABLE sm_control.target (
    target_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    ownership_scope_id uuid NOT NULL,
    -- m9: the owning scope is inside the target's organization (an install-kind scope has a NULL
    -- organization_id and so can never match). Needs scope_org_id below.
    CONSTRAINT target_ownership_in_org FOREIGN KEY (organization_id, ownership_scope_id)
        REFERENCES sm_control.scope (organization_id, scope_id) ON DELETE RESTRICT,
    name text NOT NULL CONSTRAINT target_name CHECK (length(name) BETWEEN 1 AND 128),
    kind text NOT NULL CONSTRAINT target_kind CHECK (kind IN ('docker','kubernetes','vm')),
    transport text NOT NULL CONSTRAINT target_transport CHECK (transport IN ('ssh-docker')),
    created_by uuid NOT NULL REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    retired_at timestamptz NULL,
    CONSTRAINT target_name_once UNIQUE (organization_id, name));
-- m9: target of target_ownership_in_org. scope_id is already the PK, so this adds no new uniqueness.
ALTER TABLE sm_control.scope ADD CONSTRAINT scope_org_id UNIQUE (organization_id, scope_id);
```

The ownership scope's **kind** is checked by the registering command (organization, organization unit or
environment — never solution, instance or install); the composite FK is what makes a cross-organization owner
unrepresentable.

Then, in the same file: `CREATE OR REPLACE FUNCTION sm_control.record_change_audit()` — **V012's body verbatim**
(`V012:29-211`) with exactly these edits: (1) the OR-25 allow-list (`V012:92-94`) gains `INSTANCE_DECLARED`,
`PACKAGE_LOADED`, `ENTITLEMENT_SET`, `TARGET_REGISTERED`, `TARGET_RETIRED`; (2) the `row_key` `CASE`
(`V012:127-176`) gains branches for `catalog_solution` (`catalog_solution_id`), `solution_package` (`load_seq`),
`entitlement` (`organization_id`,`solution`), `target` (`target_id`); (3) **for `solution_package` only, the
`document` key is removed from `new_values`** — the audit row keeps `digest`, which names the content exactly, so an
immutable `change_audit` row never copies up to 111 KB of package (`filemesh-ftp-solace.package.json` is 111,543
bytes, measured) per load (m9); (4) **per owner ruling 2026-09-29 (Q-E):** the actor
exemption (`V012:109-124`) admits `ENTITLEMENT_SET` with a `NULL` actor **on `entitlement` only**, scoped exactly as
V012 scopes `LOCKOUT_CLEARED` to `login_throttle`; nothing else changes. Replace the
`change_audit_audit_operation` CHECK (drop + add, `V011:39-42`) with the widened set. Attach `change_audit_trigger`
and `truncate_refused` to the four new tables exactly as V011 attaches them to the thirteen. Grants:
`GRANT SELECT, INSERT ON catalog_solution, solution_package, target TO sm_cp_runtime; GRANT SELECT, INSERT, UPDATE
(max_instances, set_by, set_at) ON entitlement …; GRANT UPDATE (retired_at) ON target …; GRANT INSERT ON
sm_control.solution, sm_control.instance, sm_control.scope …` (declare, T4.3) — ⚠️ widening V008's `SELECT`-only
hierarchy grant (`V008__runtime_grants.sql:3-4`) is **the** privilege change of this plan; say so in the commit.

- [x] **Step 1: Failing `GovernanceSchemaIT`** (runs every write **as `sm_cp_runtime`**, over the same two-phase
  bootstrap as `ChangeAuditIT`):

```java
@Test void theInstanceIdIsGloballyUnique() { /* two orgs, same instance_id → 23505 instance_id_unique */ }
@Test void everyNewTableIsAuditedWithItsOwnRowKey() { }            // one INSERT each → one change_audit row, row_key non-empty
@Test void aRuntimeWriteWithoutAuditContextIsRefusedOR025() { }    // each new table, SQLSTATE OR025
@Test void theNewOperationsAreAcceptedAndAnUnknownOneIsRefused() { }
@Test void truncateOfEachNewTableIsRefused() { }
@Test void aSecondSolutionNodeForTheSameCatalogEntryInOneEnvironmentIsRefused() { }
@Test void signatureStatusOtherThanUnsignedIsRefused() { }        // OR-40: signing is not built
@Test void theRuntimeRoleCannotUpdatePackageDocumentsOrDeleteAnything() { }   // 42501
@Test void readinessRefusesAV012SchemaNamingV013() { }            // the V012 probe pattern, one version on
@Test void aPackageWhoseOrganizationDiffersFromItsEntrysIsRefused() { }   // 23503 solution_package_entry (m9)
@Test void aTargetOwnedByAnotherOrganizationsScopeIsRefused() { }         // 23503 target_ownership_in_org (m9)
@Test void aPackageLoadsAuditRowCarriesTheDigestNotTheDocument() { }      // m9
@Test void entitlementSetWithNoActorIsAcceptedOnEntitlementOnly() { }     // owner ruling 2026-09-29 (Q-E): entitlement table only
```

- [x] **Step 2: Run; all fail.** **Step 3: Write V013, pin its checksum, extend readiness.** **Step 4: `VERIFY cp-core`; `VERIFY launcher` (readiness on the real boot); `./contracts/run-checks.sh --strict` (step 12: frozen migrations still byte-identical).**
- [x] **Step 5: Prove they fire.** Drop the `target` branch from `row_key` → `everyNewTableIsAuditedWithItsOwnRowKey`
  red (row_key `{}`). Remove `PACKAGE_LOADED` from the allow-list → its write refused → red. Edit one byte of V012 →
  `MigrationChecksumsTest` and step 12 red. Drop the `document` strip → the audit-size test red. Widen the Q-E
  exemption to every table → a `NULL`-actor `ENTITLEMENT_SET` write to `target` succeeds → the scoping test red.
  Revert each.
- [x] **Step 6: Commit.** `feat(cp-core): sm_control V013 — catalog, entitlement, target governance, instance-id uniqueness, all audited (OR-25)`
- [x] **Step 7 (after push, follow-up commit):** ✅ 2026-10-05: V013 first reached origin in `5c1d2c1`; that SHA is appended. append this commit's SHA to `backend/scripts/frozen-migration-anchors.txt`.

**Results (T2.0, 2026-10-05).** `a7d3fd4` V013 + `GovernanceSchemaIT` 15. Deviations: `scope_org_id` before `target` (DDL order); readiness as a second V013 stage. **Step 7 stays open** until V013 is pushed (then append the SHA to `frozen-migration-anchors.txt`).

### Task T2.1 — `sm_data` V007: the sequence, the stamp, `instance_state`, `placement`, `target_connection`

**Wave:** W1. **Depends on:** T0.3 (the recorded rule). **Parallel with:** T1.x, T2.0, T2.3, T3.x.

**Files:** Create `backend/dp-core/src/main/resources/db/migration/sm_data/V007__instance_inventory.sql`;
Create `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/InventorySchemaIT.java`.

```sql
CREATE SEQUENCE sm_data.state_version_seq AS bigint MINVALUE 1 NO CYCLE;          -- OR-35: ONE sequence

CREATE TABLE sm_data.instance_state (
    tenant_id uuid NOT NULL, instance_id uuid NOT NULL,
    environment_id uuid NOT NULL, catalog_solution_id uuid NOT NULL,     -- opaque CP ids; no FK (I7)
    solution text NOT NULL, declared_package_version text NOT NULL,
    name text NOT NULL CONSTRAINT instance_name CHECK (length(name) BETWEEN 1 AND 128),
    lifecycle text NOT NULL CONSTRAINT instance_lifecycle CHECK (lifecycle IN ('pending','active','abandoned')),
    -- Owner rulings M-Q2/M-Q2b (OR-71): acquisition is chosen PER INSTANCE at declare and followed by both the health
    -- poller and the metrics collector; the MODE survives a re-target's new placement (M-Q2). Immutable in v1 (not in any UPDATE grant).
    -- OR-82 (2026-10-05): NO host column. In direct-http the DP polls the TARGET's registered address
    -- (target_connection.host, fixed by the operator command that registers the target, T2.7) at the placement's
    -- health_port and health_path; nothing about the address comes from the browser, and a re-target follows the new
    -- target's address automatically. The MODE is all an instance stores.
    acquisition_mode text NOT NULL CONSTRAINT instance_acquisition_mode CHECK (acquisition_mode IN ('direct-http','via-ssh')),
    created_by uuid NOT NULL, created_at timestamptz NOT NULL,
    state_version bigint NOT NULL,
    PRIMARY KEY (tenant_id, instance_id),
    CONSTRAINT instance_name_once UNIQUE (tenant_id, environment_id, name));

CREATE TABLE sm_data.target_connection (                  -- OR-28: mutable; read fresh per SSH session
    tenant_id uuid NOT NULL, target_id uuid NOT NULL,
    transport text NOT NULL CONSTRAINT target_connection_transport CHECK (transport = 'ssh-docker'),
    host text NOT NULL, port integer NOT NULL CONSTRAINT target_connection_port CHECK (port BETWEEN 1 AND 65535),
    ssh_user text NOT NULL,
    host_key_fingerprint text NOT NULL CONSTRAINT target_connection_fp CHECK (host_key_fingerprint ~ '^SHA256:[A-Za-z0-9+/]{20,86}={0,2}$'),
    credential_ref uuid NOT NULL,                        -- sm_secret.secret(secret_id); never the key (I6)
    -- B2: the envelope a declare body may name paths and containers inside. Set by the registering operator
    -- command (T2.7), IMMUTABLE (not in any UPDATE grant): changing it means registering a new target.
    allowed_config_root text NOT NULL,                   -- SshDockerPlacement's canonical-absolute-path grammar, not '/'
    allowed_container_prefix text NOT NULL,              -- SshDockerPlacement's TOKEN grammar, 1..64 chars
    created_by uuid NOT NULL, created_at timestamptz NOT NULL, retired_at timestamptz NULL,
    PRIMARY KEY (tenant_id, target_id));
-- M7(a): one live target per SSH HOST per ORGANIZATION, not per target row. The pinned host key identifies the
-- host whatever name or port reaches it, so two rows for one VM in one organization (an alias, a second sshd port)
-- are refused, and the per-target indexes below are therefore per-host. PER ORGANIZATION (OR-89, owner ruling
-- 2026-10-05): the index leads with tenant_id, so two organizations may each register the same machine (the
-- accepted consequence: one host driven by two organizations' configurations). A cloned image sharing a host key
-- inside one organization is refused too, which fails safe.
CREATE UNIQUE INDEX target_connection_host_once ON sm_data.target_connection (tenant_id, host_key_fingerprint) WHERE retired_at IS NULL;

CREATE TABLE sm_data.placement (                          -- OR-29: immutable; re-target mints a new row
    tenant_id uuid NOT NULL, placement_id uuid NOT NULL
        CONSTRAINT placement_not_sentinel CHECK (placement_id <> '00000000-0000-0000-0000-000000000000'),
    instance_id uuid NOT NULL, target_id uuid NOT NULL,
    container_name text NOT NULL, remote_config_directory text NOT NULL,
    container_config_path text NOT NULL, file_name text NOT NULL,
    mechanism text NOT NULL, retained_generations integer NOT NULL CHECK (retained_generations >= 1),
    -- M8: the probe is declared per placement (OR-29), copied at declare from the package's health.probe
    -- (schema: kind ∈ actuator-health | http-status | broker-heartbeat, path and port optional, strength required
    -- once T0.1a lands — solution-package.schema.json $defs/health/properties/probe). Only the two HTTP kinds are SSH/HTTP-probable;
    -- a package declaring neither stores NULLs and its health reads NOTFOUND not_exposed (T7.2), never a guess.
    health_kind text NULL CHECK (health_kind IN ('actuator-health','http-status')),
    health_path text NULL CHECK (health_path ~ '^/[A-Za-z0-9/_.-]{0,255}$'),    -- HostProbe's grammar (T6.1)
    health_port integer NULL CHECK (health_port BETWEEN 1 AND 65535),           -- the HOST-side port (M12)
    -- Owner ruling 2026-09-29 (S0-10): the package's declared probe strength (T0.1a), copied at declare like kind
    -- and path. What a rolling-health-gated gate can prove on this placement (T6.9) — never inferred, never Cache-coded.
    health_strength text NULL CHECK (health_strength IN ('readiness','liveness')),
    CONSTRAINT health_probe_complete CHECK ((health_kind IS NULL) = (health_path IS NULL)
                                        AND (health_kind IS NULL) = (health_port IS NULL)
                                        AND (health_kind IS NULL) = (health_strength IS NULL)),
    -- (no per-placement acquisition: owner rulings M-Q2/M-Q2b, OR-71, moved it to instance_state)
    created_at timestamptz NOT NULL, retired_at timestamptz NULL,
    PRIMARY KEY (tenant_id, placement_id),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES sm_data.instance_state (tenant_id, instance_id),
    FOREIGN KEY (tenant_id, target_id) REFERENCES sm_data.target_connection (tenant_id, target_id));
-- OR-29: per-target (= per-host, above) uniqueness of what an apply WRITES, what a stop ADDRESSES and what a
-- probe READS, among live placements. The config FILE needs no index of its own: file = dir + '/' + name, so a
-- unique directory already makes the file unique (M7(b): a (dir, file) index could never fire on its own).
CREATE UNIQUE INDEX placement_container_once ON sm_data.placement (tenant_id, target_id, container_name) WHERE retired_at IS NULL;
CREATE UNIQUE INDEX placement_config_dir_once ON sm_data.placement (tenant_id, target_id, remote_config_directory) WHERE retired_at IS NULL;
-- M7(d): two live placements probing one host port would each read the other's health.
CREATE UNIQUE INDEX placement_health_port_once ON sm_data.placement (tenant_id, target_id, health_port) WHERE retired_at IS NULL;
```

The field grammars (`container_name`, the two paths, `file_name`, `mechanism`, and `allowed_*`) copy
**`SshDockerPlacement`'s** patterns (`SshDockerPlacement.java:21-25, 60-65`) as CHECKs — two enforcers, the codebase's
standing pattern (`ApplyCommand` javadoc). `placement` is immutable: a `BEFORE UPDATE` trigger refuses any change
except `retired_at` NULL → value (one function per table, `V003:37-43`'s measured reason).

**The envelope trigger (B2).** A `BEFORE INSERT` trigger on `placement` reads its `target_connection` row (same
tenant, same schema — no cross-schema read) and refuses (`SQLSTATE 23514`, message naming the **field**, never the
value) unless `container_name` starts with `allowed_container_prefix` and `remote_config_directory` equals
`allowed_config_root` or starts with `allowed_config_root || '/'`. This is the second of three enforcers: the
service checks first (T2.5, a clean `422`), and the adapter's preflight compares the SFTP-canonicalized directory
against the root (T6.1, **M8** — so a symbolic link inside the root cannot escape it).

**The stamp.** One function per owning table (again `V003:37-43`): `BEFORE INSERT OR UPDATE` it (1) locks the owning
`instance_state` row `FOR UPDATE` (the recorded commit-order rule, T0.3), then (2) `NEW.state_version :=
nextval('sm_data.state_version_seq')`. On `instance_state` itself the lock is the row being written. Grants:
`sm_dp_runtime` gets `SELECT, INSERT` on all three, `UPDATE (lifecycle)` on `instance_state`, `UPDATE (retired_at)`
on `placement`, `UPDATE (host, port, ssh_user, host_key_fingerprint, credential_ref, retired_at)` on
`target_connection` (⛔ **not** `allowed_config_root`/`allowed_container_prefix` — the envelope is immutable), and whatever **M5** measures `nextval` needs (`USAGE` on the sequence, or a
`SECURITY DEFINER` stamp function with pinned `search_path`, V011's pattern) — **measure first, then choose, then
write the choice into the migration comment**.

- [x] **Step 0: Fill M5** (measure in a scratch IT as `sm_dp_runtime`, then WebFetch to confirm the reading).
- [x] **Step 1: Failing `InventorySchemaIT`** (as `sm_dp_runtime`):

```java
@Test void everyInsertAndUpdateOfAnOwningRowTakesTheNextSequenceValue() { }
@Test void theRuntimeRoleCannotSetStateVersionItself() { }       // an explicit value is overwritten, or refused — assert which M5 measured
@Test void stampsWithinOneInstanceAreMonotonicInCommitOrder() {
    // Review Focus 1. T1 stamps (row lock held), T2 on the SAME instance blocks until T1 commits, so T2's
    // stamp > T1's and T2 commits after. Run 20 interleavings with two threads; assert commit order == stamp order.
}
@Test void stampsOnDifferentInstancesDoNotSerialize() { }        // the lock is per instance, not global
@Test void thePlacementRowIsImmutableExceptRetirement() { }
@Test void aSecondPlacementOnOneTargetMayNotReuseContainerNameConfigDirOrHealthPort() { }   // Review Focus 2 — three cases
@Test void aSecondLiveTargetForTheSameHostKeyIsRefused() { }     // M7(a), OR-89: in ONE organization — an alias or a second sshd port; another organization may register the same key
@Test void aPlacementOutsideTheTargetsConfigRootIsRefused() { }  // B2: /home/<user>/.ssh with root /srv/sm → 23514
@Test void aSiblingPathSharingTheRootsPrefixIsRefused() { }      // B2: root /srv/sm, dir /srv/sm-evil
@Test void aContainerOutsideTheTargetsPrefixIsRefused() { }      // B2
@Test void theRuntimeRoleCannotWidenTheEnvelope() { }            // 42501 on UPDATE (allowed_config_root)
@Test void aProbeIsAllOrNothing() { }                            // health_probe_complete, strength included (S0-10)
@Test void instanceStateHasNoAddressColumn() { }                  // OR-82: information_schema has no host/address column on instance_state; the mode CHECK is the only acquisition constraint
@Test void theRuntimeRoleCannotChangeAnInstancesAcquisition() { }              // 42501: not in UPDATE (lifecycle)
@Test void aRetiredPlacementFreesItsContainerNameForANewOne() { }
@Test void theSentinelIsNotAStorablePlacementId() { }            // 23514 placement_not_sentinel
@Test void everyGrammarCheckRefusesWhatSshDockerPlacementRefuses() { }   // parameterized over SshDockerPlacementTest's bad values
@Test void theRuntimeRoleCannotDeleteAnyInventoryRow() { }       // 42501
@Test void everyQueryShapeIsTenantScoped() { }                   // tenant B cannot see tenant A's rows by id
```

- [x] **Step 2: Run; all fail.** **Step 3: Write V007.** **Step 4: `VERIFY dp-core` green.**
- [x] **Step 5: Prove they fire.** Remove the `FOR UPDATE` from the stamp → `stampsWithinOneInstanceAreMonotonicInCommitOrder`
  red (report how many interleavings of 20 broke; if 0, the test is too weak — add a latch that forces the race).
  Drop `placement_config_dir_once` → its case red. Grant `UPDATE (container_name)` → immutability test red. Compare
  the root with a bare `LIKE root || '%'` → the sibling-prefix test red. Drop `target_connection_host_once` → the
  alias test red; drop `tenant_id` from its columns → the cross-organization case red (OR-89). Revert each.
- [x] **Step 6: Commit.** `feat(dp-core): sm_data V007 — one stateVersion sequence, instance_state, immutable placements unique per target (OR-29, OR-35)`

**Results (T2.1, 2026-10-05).** `af0640e` V007 + `InventorySchemaIT`; M5 filled (the trigger may assign a column the role lacks; `nextval` needs `USAGE`; `CACHE 1`). `8431827` dropped `acquisition_host` per OR-82 (`InventorySchemaIT` 79; `RefusalShapesIT` named-CHECK count 46). **Open:** every `instance_state` UPDATE stamps. **Superseded by OR-89 (2026-10-05):** `target_connection_host_once` is `(tenant_id, host_key_fingerprint) WHERE retired_at IS NULL` — per organization, not install-wide (see the OR-89 note under the S2 dispositions).

### Task T2.2 — Admission refuses an undeclared instance; `apply_request.state_version` (V008)

**Wave:** W2. **Depends on:** T2.1. **Parallel with:** T2.4, T3.2, T1.2 (disjoint files; this task owns
`dp.apply.engine` and the 35 admitting IT fixtures — which is why T5.1 waits for it, M13).

**Files:** Create `…/sm_data/V008__apply_request_state_version.sql`; Modify
`backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/{AdmissionService,RefusalReason}.java`,
`…/apply/model/ApplyRequestRow.java`, `…/apply/persistence/ApplyRequestRepository.java`;
Modify the dp-core and launcher IT fixtures that admit undeclared instances — **35 files, measured**
(`grep -rlE '\.admit\(|new ApplyCommand\(|INSERT INTO sm_data\.apply_(request|scope)' backend/dp-core/src/test backend/launcher/src/test`),
preferring the shared helpers `ApplyEngineHarness`/`ApplyRows` so most files change by one line; ⛔ **not**
`SmDataBootstrapIT` (its `execute(` matches are SQL statements, not admissions — T2.4 owns it in W2);
Create `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/{DeclaredInstanceGuardIT,ApplyRequestStateVersionIT}.java`;
Create a shared test helper `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/InventoryFixtures.java` (`declare(tenant, instance)`, `placement(tenant, instance, target)`).

V008, in this order:

1. **Refuse a non-empty journal** — a `DO` block raises (naming the recreate procedure, never a row) when
   `sm_data.apply_request` has any row. ⚠️ **No backfill (M6):** V006's `forbid_terminal_regression` lets a terminal
   row change only when `to_jsonb(NEW) - 'attempt_summary' = to_jsonb(OLD) - 'attempt_summary'`
   (`V006__terminal_guard.sql:12-21`), so an `UPDATE` that writes `state_version` on an existing terminal row is
   refused by the guard itself; and a legacy row for an undeclared instance could not be stamped anyway (step 3).
   Pre-production, no customer database exists; a development database carrying demo-era journal rows is recreated,
   and the commit says so.
2. `ALTER TABLE sm_data.apply_request ADD COLUMN state_version bigint NOT NULL;` (legal on an empty table).
3. **The stamp trigger** (T2.1's pattern), `BEFORE INSERT OR UPDATE`, which **raises** when the owning
   `instance_state` row is missing (never stamps silently) and **skips the compaction fold**: on `UPDATE`, when
   `to_jsonb(NEW) - 'attempt_summary' - 'state_version' = to_jsonb(OLD) - 'attempt_summary' - 'state_version'` it
   leaves `NEW.state_version := OLD.state_version`, takes no lock and draws no value. That makes the two `BEFORE
   UPDATE` triggers **order-independent**: on the fold neither changes `state_version`, so the guard's comparison
   holds whichever fires first; on `STAGED → SUPERSEDED` the guard's first branch returns `NEW` before comparing; on
   a non-terminal row the guard does not compare. (PostgreSQL's firing order among same-event triggers is therefore
   not relied on — no Measured-facts row is needed for it.) A compaction is not an observable change, so not
   stamping it is OR-35's "when it changes", not an exception to it.
4. ⛔ the column is **not** added to the column-scoped `UPDATE`/`INSERT` grants (`V002:148-153`) — the trigger sets
   it (M5).
5. `ALTER TABLE sm_data.apply_scope ADD CONSTRAINT scope_has_instance FOREIGN KEY (tenant_id, instance_id)
   REFERENCES sm_data.instance_state (tenant_id, instance_id) NOT VALID;` (**M6**).

- [x] **Step 0: Fill M6.**
- [x] **Step 1: Failing tests.**

```java
@Test void admissionRefusesAnInstanceThatWasNeverDeclared() { /* NotAdmitted(INSTANCE_NOT_DECLARED) — before any row */ }
@Test void admissionRefusesAPendingOrAbandonedInstance() { }     // only lifecycle = 'active' is admissible
@Test void aScopeRowForAnUndeclaredInstanceIsRefusedByTheDatabaseToo() { }   // 23503 scope_has_instance
@Test void everyRequestTransitionTakesAHigherStateVersion() { }  // REQUESTED → RUNNING → terminal, three increasing values
@Test void theRuntimeRoleStillCannotWriteStateVersion() { }
@Test void theCompactionFoldOnATerminalRowStillSucceedsAndDoesNotStamp() { }   // M6: V006's sanctioned write 2
@Test void stagedToSupersededStampsAndIsStillPermitted() { }                    // M6: V006's sanctioned transition 1
@Test void anyOtherWriteToATerminalRowIsStillRefused() { }                      // V006 unchanged
@Test void v008RefusesToRunOverANonEmptyJournal() { }                           // migration IT, as the owner role
@Test void theWholeExistingSuiteStillPassesOverDeclaredInstances() { }   // the harness change is the regression proof
```

- [x] **Step 2: Run; fail.** **Step 3: Implement.** `RefusalReason.INSTANCE_NOT_DECLARED`; the check runs **inside**
  the admission transaction, after the `apply_scope` lock (`AdmissionService.java:83-84`), reading
  `instance_state.lifecycle` with a tenant-bound query. Update every IT fixture to declare first via
  `InventoryFixtures` — mechanical; ⛔ **no test is deleted or weakened**; record before/after counts.
- [x] **Step 4: `VERIFY dp-core`; `VERIFY launcher`** (the worker ITs admit too).
- [x] **Step 5: Prove they fire.** Remove the admission check → the first test red **and** the FK still refuses
  (proving the two enforcers are independent). Add `state_version` to the INSERT grant and write it from Java →
  `theRuntimeRoleStillCannotWriteStateVersion` red. Remove the fold skip from the stamp → the compaction-fold test red
  (the guard raises). Revert each.
- [x] **Step 6: Commit.** `feat(dp-core): admission refuses undeclared instances; apply_request carries a stamped stateVersion (V008)`

**Results (T2.2, 2026-10-05).** `077b104` V008; `INSTANCE_NOT_DECLARED`; M6 filled (`NOT VALID`; `SHARE ROW EXCLUSIVE`); dp-core failsafe 440 at that commit. The `AttemptIT` clock-skew failure observed there was not T2.2's; the cause (an `ended_at = now()` against an application-clock `started_at` firing `attempt_time_ordered` before `outcome_known`) is fixed in `39af802`.

### Task T2.3 — D-KEY: master key, envelope, rotation, backup, and the HMAC key (design, docs only)

**Wave:** W1. **Depends on:** nothing but OR-30/OR-32. **Parallel with:** everything (docs only; it touches no code).

**Files:** Create `docs/design/backend/secret-store.md`; draft its decision rows with `«next free BE-D»`.

The design must answer, with **M7** filled and cited:

1. **Key hierarchy.** Recommended: one **key-encryption key (KEK)** from the environment
   (`SM_DP_MASTER_KEY`, base64 of 32 random bytes, plus `SM_DP_MASTER_KEY_ID`), wrapping a per-secret **data key**
   (DEK) with `AES/GCM/NoPadding`; the secret value is encrypted under its DEK; the row stores `{key_id, wrapped_dek,
   dek_iv, value_iv, ciphertext, aad_version}` and the **AAD binds `tenant_id`, `secret_id` and `purpose`**, so a
   ciphertext moved to another row or tenant fails authentication. The KEK never enters the database (OR-30).
2. **Fail closed at boot** (Review Focus 5): absent, malformed or wrong-length key → refuse to start, with a message
   naming the variable and never the value; a **canary row** (a known plaintext encrypted at first boot) proves the
   key is the *right* one before the worker starts. The canary is **install-level, not tenant data**: one row per
   KEK id in its own table `sm_secret.kek_canary(key_id, wrapped_dek, dek_iv, value_iv, ciphertext)` with no
   `tenant_id` — so reading it is not a cross-tenant read (B4), and it is never a `SecretStore` entry.
3. **Rotation.** A new KEK id; the **`KeyRotationMain` operator command** (dp-core persistence package, the
   `SmControlLockoutRecoveryMain` shape — never a Spring context, Global Constraints) re-wraps every DEK under the new
   KEK in batches (the ciphertext of the value never changes); the old KEK stays loadable
   (`SM_DP_MASTER_KEY_PREVIOUS`) until the command reports zero rows under the old id. A resumed apply that reads a
   secret mid-rotation decrypts with whichever id the row names — no window. Re-wrapping "every DEK" reads every
   tenant's rows: ✅ **ruled** — the command iterates the second tenant list (owner ruling 2026-09-29 (Q-T),
   `OR-49`) and re-wraps **per organization**, each batch tenant-bound; it never reads a DEK without its tenant.
4. **Backup.** The KEK is backed up by the operator outside SM (runbook text); a database backup without the KEK is
   ciphertext only; losing the KEK loses every credential and is recoverable only by re-entering them (stated, not
   softened).
5. **The OR-32 HMAC key.** Options: (a) a second environment secret `SM_DP_CONFIG_HASH_KEY` + id; (b) an HMAC key
   stored as one more envelope-encrypted secret of purpose `CONFIG_HASH_KEY`; (c) a key derived from the KEK. Recommend
   **(b)**: one root of trust, and rotation reuses §3's machinery. (c) is **not relied on**, so whether JDK 17 exposes
   a key-derivation function is not asserted either way (m7 — no Measured-facts row is needed for a fact nothing
   uses). Decisions this section must record:
   - **Scope: one key per tenant** (m5). `SecretStore.put(tenantId, …)` binds the tenant into the AAD, so a key is
     already a tenant's; and a per-tenant key means no two tenants' hashes are ever comparable. The hasher is therefore
     addressed by tenant — `ConfigHasher.hash(TenantId, byte[])` (T5.1) — and every call site already holds one
     (`PlacementRef.tenant()`, `PlacementRef.java:18`). The key is minted lazily, on a tenant's first stage.
   - **Spelling: `hmacsha256v<keyVersion>:<64 lowercase hex>`**, `keyVersion` a positive decimal per tenant. It fits the
     model grammar `^[a-z0-9]+:[0-9a-f]{16,128}$` (`ValueGrammar.java:35`) and V002's `resolution_is_evidence`
     element predicate (`V002__apply_request.sql:105`) **unchanged** — no migration, no grammar edit (B1). A spelling
     with `-` or a second `:` (e.g. `hmac-sha256:<id>:<hex>`) fails both.
   - **Verification across rotation:** the verifier keeps every key version still named by a non-terminal journal
     row, so a resumed apply's bound hash stays verifiable.
   - **Where the hash is computed: in the DP, never on the customer host** (B1). The key cannot leave the DP, so the
     transport returns bytes and the adapter hashes them (T5.1); a remote `sha256sum` is exactly the unkeyed digest
     OR-32 forbids.
6. **Schema.** `sm_secret` is a separate schema owned by `sm_dp_owner`, created by **Phase 0**
   (`sm_data_phase0.sql` gains `CREATE SCHEMA sm_secret AUTHORIZATION sm_dp_owner; GRANT USAGE ON SCHEMA sm_secret TO
   sm_dp_runtime;`, because a migrator cannot create a schema — measured for `sm_control`, `V001__scope.sql:1-7`);
   its tables are created by `sm_data` `V009`, schema-qualified. The runtime reads ciphertext; it never needs `DELETE`.
7. **What is refused.** No secret in a log line, exception message, `toString`, event, HTTP body, or history row;
   `SecretMaterial` zeroises its `char[]`/`byte[]` on close and is `AutoCloseable`.

- [x] **Step 1:** Fill M7. **Step 2:** Write the doc. **Step 3:** Links resolve. **Step 4: Commit.** `docs(backend): D-KEY — the DP secret store's key hierarchy, rotation, backup and the keyed config hash (OR-30, OR-32)`

**Results (T2.3, 2026-10-05).** `9629cc7` D-KEY design `docs/design/backend/secret-store.md` (M7 filled); `52439c3` §11 ruled (OR-85). **Open:** draft decisions K-1..K-4 still need `BE-D` ids (register at S2 close).

### Task T2.4 — The secret store: `sm_secret` (V009), envelope encryption, `SecretResolver`, rotation

**Wave:** W2. **Depends on:** T2.1, T2.3 merged. **Parallel with:** T2.2, T3.2. **Step 3b is no longer gated** (owner ruling 2026-09-29 (Q-T)).

**Files:** Modify `backend/dp-core/src/main/resources/db/bootstrap/sm_data_phase0.sql`; Create `…/sm_data/V009__secret_store.sql`
(`sm_secret.secret`, `sm_secret.kek_canary`);
Create `backend/dp-core/src/main/java/com/solace/sm/dp/secret/{MasterKeyProvider,EnvironmentMasterKeyProvider,Envelope,SecretMaterial,SecretResolver,SecretStore,SecretPurpose,SecretConfiguration}.java`;
Create `…/dp/apply/persistence/{JdbcSecretRepository,JdbcKekCanaryRepository}.java`; **Step 3b:** Create
`…/dp/apply/persistence/KeyRotationMain.java` + `KeyRotationMainTest.java`, Modify
`…/dp/apply/persistence/TenantEnumerationRepository.java` (the second tenant list — single-owner row) + its IT
`TenantEnumerationRepositoryIT.java`;
Create tests `…/dp/secret/{EnvelopeTest,SecretMaterialTest,EnvironmentMasterKeyProviderTest}.java`, `…/apply/persistence/SecretStoreIT.java`;
Modify `SmDataBootstrapIT` (Phase 0 now creates two schemas).

**Interfaces produced:**

```java
package com.solace.sm.dp.secret;
public enum SecretPurpose { SSH_PRIVATE_KEY, CONFIG_FIELD, CONFIG_HASH_KEY, CANARY }
public interface SecretStore {
    /** Encrypts and stores; returns the reference. The value is zeroised by the caller after this returns. */
    UUID put(UUID tenantId, SecretPurpose purpose, char[] value, UUID actor);
    boolean exists(UUID tenantId, UUID secretId);
}
public interface SecretResolver {
    /** Decrypts for ONE use. Close the result; its buffer is zeroised on close. */
    SecretMaterial resolve(UUID tenantId, UUID secretId, SecretPurpose expected);
}
```

- [x] **Step 1: Failing tests.**

```java
@Test void roundTripUnderTheSameKeyRestoresTheValue() { }
@Test void aCiphertextMovedToAnotherTenantOrRowFailsAuthentication() { }   // AAD binding
@Test void everyEncryptionUsesAFreshIv() { }                               // M7: GCM IV uniqueness per key
@Test void aMissingMalformedOrShortMasterKeyRefusesStartupWithoutEchoingIt() { }   // Review Focus 5
@Test void aWrongMasterKeyFailsTheCanaryBeforeTheWorkerStarts() { }
@Test void aReadDuringRotationDecryptsUnderWhicheverKeyIdTheRowNames() { }   // tenant-scoped; not gated
@Test void theCanaryIsInstallLevelAndCarriesNoTenant() { }                     // B4: no cross-tenant read at boot
// Step 3b: rotationRewrapsEveryDekAndLeavesTheValueCiphertextUntouched(), plus the second tenant list's tests (below)
@Test void noExceptionMessageOrToStringCarriesPlaintextOrCiphertext() { }  // grep the thrown messages and toString()
@Test void secretMaterialIsZeroisedOnClose() { }
@Test void theRuntimeRoleCannotDeleteOrReadAcrossTenants() { }             // as sm_dp_runtime
@Test void phase0CreatesSmSecretOwnedBySmDpOwner() { }
```

- [x] **Step 2: Run; fail.** **Step 3: Implement** per D-KEY. `JdbcSecretRepository` lives in
  `dp.apply.persistence` (JDBC confinement); the crypto lives in `dp.secret` and imports no JDBC type.
- [x] **Step 3b (owner ruling 2026-09-29 (Q-T), `OR-49` — no longer gated):** (i) **the second tenant list,
  written once:** `TenantEnumerationRepository.tenantsWithActiveInstances()` —
  `SELECT DISTINCT tenant_id FROM sm_data.instance_state WHERE lifecycle = 'active'`, one column, persistence
  package, javadoc naming the ruling beside OR-44's and listing its permitted callers (the health poller,
  fleet batch advancement, the key re-wrap; and `OR-72`'s fourth, plan 011's `MetricsCollector`, which that plan adds). Tests: `theSecondListReadsOnlyTheTenantIdOfActiveInstances()` (a
  tenant whose only instance is `pending`/`abandoned` is not yielded), `anIdleTenantWithAnActiveInstanceIsYielded()`
  (no apply in flight — the B4 case). (ii) `KeyRotationMain` — standalone, runtime DP credential from the
  environment, both KEKs from the environment, iterates (i) and re-wraps per organization in batches, reports the
  count left under the old id, never echoes a key; its test is
  `rotationRewrapsEveryDekAndLeavesTheValueCiphertextUntouched`. **Mutation:** widen (i) to
  `SELECT DISTINCT tenant_id FROM sm_data.instance_state` (no lifecycle predicate) → the pending-only test red.
- [x] **Step 4: `VERIFY dp-core`; `VERIFY arch-tests`.**
- [x] **Step 5: Prove they fire.** Reuse one IV → `everyEncryptionUsesAFreshIv` red. Drop `tenant_id` from the AAD →
  the move test red. Log the value in the decrypt-failure path → the message test red. Skip the canary → the wrong-key
  test red. Revert each.
- [x] **Step 6: Commit.** `feat(dp-core): the DP secret store — envelope encryption in a separate schema, fail-closed master key (OR-30)`

**Results (T2.4, 2026-10-05).** `49fa1d1` `sm_secret` V009, AES-256-GCM envelope with AAD (tenant, secret, purpose), `SM_DP_MASTER_KEY` or `SM_DP_MASTER_KEY_FILE`, canary, `KeyRotationMain` with `--tenant`, `tenantsWithActiveInstances()`; `dd9342f` migration counts reconciled for V008+V009. dp-core after merge: surefire 302 (1 skip), failsafe 447 (4 skip), 0 fail. **Open:** the worker's `@DependsOn(kekCanaryCheck)` is T2.6's; `put` refuses `CONFIG_HASH_KEY` (T5.1 mints it).

### Task T2.5 — DP inventory repositories, services and `dp-api` ports

**Wave:** W3. **Depends on:** T2.1, T2.2, T2.4. **Parallel with:** T5.1, T5.2 (disjoint: this owns `dp.inventory` +
its repositories; it does **not** touch `dp.apply.runtime.ssh` — the `SshDockerPlacement` change and
`TablePlacementCatalog` moved to T6.1/T6.2, so the record, the transport that reads it and the demo that builds it
change in one task, M5).

**Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/inventory/{InstanceInventoryService,TargetConnectionService,DeclareInstanceCommand,PlacementSpec,HealthProbeSpec,ProbeStrength,AcquisitionMode,InstanceView,PlacementView,InstanceLifecycle,PlacementCollisionException,PlacementOutsideEnvelopeException,TargetConnectionSpec}.java`;
Create `backend/dp-core/src/main/java/com/solace/sm/dp/inventory/{InventoryService,InventoryConfiguration}.java`;
Create `…/dp/apply/persistence/{JdbcInstanceStateRepository,JdbcPlacementRepository,JdbcTargetConnectionRepository}.java`;
Create tests `…/dp/inventory/InventoryServiceIT.java`.

**Interfaces produced** (`dp-api`, plain records, `UUID` ids — `dp-api` has no dependencies, `dp-api/pom.xml:15`):

```java
public interface InstanceInventoryService {
    /** Phase 1 of declare (OR-27): the DP mints the id; the row is 'pending' until activate(). */
    InstanceView reserve(UUID organizationId, DeclareInstanceCommand command);     // throws PlacementCollisionException
    InstanceView activate(UUID organizationId, UUID instanceId);                 // idempotent
    /** pending → abandoned AND the instance's placements retired, in one transaction (M7(c)): an abandoned
     *  declare must not hold a container name, directory or health port forever. Idempotent. */
    void abandon(UUID organizationId, UUID instanceId);
    Optional<InstanceView> findActive(UUID organizationId, UUID instanceId);
    List<InstanceView> listActive(UUID organizationId, UUID catalogSolutionId);
    List<UUID> pendingOlderThan(UUID organizationId, java.time.Duration age);    // for the declare sweep (T4.2)
}
public record DeclareInstanceCommand(UUID environmentId, UUID catalogSolutionId, String solution,
        String declaredPackageVersion, String name, UUID targetId, AcquisitionMode acquisition,   // OR-71: per instance; OR-82: the mode only
        PlacementSpec placement, UUID requestedBy) {}
/** OR-82: the mode only. DIRECT_HTTP polls the target's registered address (target_connection.host) at the placement's health port and path. */
public enum AcquisitionMode { DIRECT_HTTP, VIA_SSH }
/** Browser-named fields; reserve() refuses (PlacementOutsideEnvelopeException → 422) any outside the target's envelope (B2). */
public record PlacementSpec(String containerName, String configDirectory, String containerConfigPath,
        String fileName, HealthProbeSpec probe) {}
/** From the package's health.probe (gateway-resolved at declare, M8) + the host-side port; null when the package declares no HTTP probe.
 *  strength is the package's declared probe strength (T0.1a, owner ruling 2026-09-29 (S0-10)). */
public record HealthProbeSpec(String kind, String path, int hostPort, ProbeStrength strength) {}
public enum ProbeStrength { READINESS, LIVENESS }
public record InstanceView(UUID instanceId, String name, UUID environmentId, UUID catalogSolutionId,
        String declaredPackageVersion, InstanceLifecycle lifecycle, AcquisitionMode acquisition,
        List<PlacementView> placements, long stateVersion) {}
public record PlacementView(UUID placementId, UUID targetId, String containerName,
        Optional<ProbeStrength> probeStrength) {}   // empty = no HTTP probe declared (T0.1 point 26: "none")
public interface TargetConnectionService {
    void register(UUID organizationId, UUID targetId, TargetConnectionSpec spec, char[] privateKey, UUID actor);
    void rotateCredential(UUID organizationId, UUID targetId, char[] privateKey, UUID actor);
}
```

`reserve` checks the envelope **first** (`containerName` starts with the target's `allowed_container_prefix`;
`configDirectory` equals `allowed_config_root` or lies under `allowed_config_root + "/"`), before any row is
written — the clean `422`; V007's trigger is the second enforcer (B2). The placement registry's catalog view
(`TablePlacementCatalog`) is built in **T6.2**, together with the `SshDockerPlacement` change in T6.1, so the
record and its only readers change together (M5).

- [x] **Step 1: Failing tests.**

```java
@Test void reserveMintsAUuidV7AndLeavesTheInstancePending() { }
@Test void aPendingInstanceIsNotListedNorFindable() { }
@Test void activateIsIdempotentAndStampsAHigherStateVersion() { }
@Test void aPlacementCollisionIsReportedAsItsClassWithoutNamingTheOtherInstance() { }   // Review Focus 2
@Test void aPlacementOutsideTheEnvelopeIsRefusedBeforeAnyRowAndNamesOnlyTheField() { }  // B2
@Test void abandonRetiresThePlacementsSoTheirNamesAreFree() { }                          // M7(c)
@Test void aCredentialRotationIsReadByTheNextSessionNotTheCurrentOne() { }
@Test void noViewOrExceptionCarriesTheCredentialRef() { }   // credential_ref is internal; views never expose it
```

- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY dp-core`; `VERIFY arch-tests`.**
- [x] **Step 5: Prove they fire.** Return pending rows from `findActive` → red. Skip the service's envelope check →
  the refusal now comes from the trigger (`23514`), not the `422` path → the "before any row" test red (and the
  trigger still refuses — two independent enforcers). Abandon without retiring → M7(c)'s test red. Revert each.
- [x] **Step 6: Commit.** `feat(dp): instance inventory, placements and target connections behind dp-api (OR-27, OR-28, OR-29)`

**Results (T2.5, 2026-10-05).** `010fc9c` (merged as `16e49aa`) inventory ports, `InventoryService`, three JDBC repositories behind persistence ports; `InventoryServiceIT` 15 (red first against a stub), `InventoryConfigurationIT` 2. Mutations: `findActive` without the lifecycle filter, envelope check skipped, abandon without retiring placements, collision unmapped -> red. Notes: `PlacementSpec` has no `mechanism`/`retained_generations` field, so `InventoryService` defaults `ADDITIONAL_LOCATION` and 10 (a package-declared mechanism would be a contract change); `instance_name_once` keeps an abandoned name reserved (T4.2); `InventoryServiceIT` lives in `dp.apply.persistence` (package-private `SmDataPostgres`).

### Task T2.6 — `DataPlaneConfiguration`: one aggregator, one launcher edit

**Wave:** W3, same agent as T2.5 (after it). **Parallel:** ⛔ the launcher file is single-owner; T3.3 waits for this.

**Files:** Create `backend/dp-core/src/main/java/com/solace/sm/dp/DataPlaneConfiguration.java`
(`@Import({ApplyPersistenceConfiguration, ApplyEngineConfiguration, ApplyWorkerConfiguration, SecretConfiguration,
InventoryConfiguration})`); Modify `launcher/.../SolutionManagerConfiguration.java:30` to import
`{ControlPlaneConfiguration, DataPlaneConfiguration}` only; Modify `launcher/.../SolutionManagerConfigurationTest.java`;
Modify `launcher/src/test/.../{TwoSchemaTestDatabase,HierarchyEndpointsIT,ProductionAuthenticationIT,ProductionRateGateAndForwardingIT}.java` (M4).

⚠️ **Importing `SecretConfiguration` makes every launcher boot fail closed without a master key** (T2.3 §2) — that
is all 11 launcher ITs (measured). The fix is **two places**: `TwoSchemaTestDatabase.provision(registry)`
(`TwoSchemaTestDatabase.java:45-47`), which 8 of the 11 call, also registers a per-run random test KEK and its id as
properties; and the 3 that provision their own properties — `HierarchyEndpointsIT`, `ProductionAuthenticationIT`,
`ProductionRateGateAndForwardingIT` (measured: they do not reference `TwoSchemaTestDatabase`) — gain the same call.
(**M16** (i): the environment-variable spelling production uses binds to the same property.) ⛔ No default key in
any committed properties file. Those three ITs are in this task's Files.

- [x] **Step 0: Fill M16 (i).**
- [x] **Step 1: Failing tests** `theLauncherImportsExactlyTheTwoPlaneAggregators()` (reflect the `@Import` value) and
  `aBootWithoutAMasterKeyRefusesToStartNamingTheVariable()` (a launcher IT that deliberately skips the test key).
- [x] **Step 2–4:** implement; `VERIFY launcher` (every existing launcher IT must stay green — they are the proof the
  swap changed nothing); `VERIFY arch-tests` (`CompositionBoundaryTest` unchanged and green).
- [x] **Step 5: Prove it fires.** Import `ApplyEngineConfiguration` directly in the launcher again → the new test red.
- [x] **Step 6: Commit.** `refactor(launcher): compose the two plane aggregators only`

**Results (T2.6, 2026-10-05).** `2eafe78` (merged as `8507647`) one `DataPlaneConfiguration` aggregator, one launcher edit; worker + startup sweep `@DependsOn(kekCanaryCheck)`; launcher boot without the master key refuses, naming `SM_DP_MASTER_KEY`; launcher ITs register a per-run random key. Fixed the T2.4 `Clock` ambiguity (qualified `smDataClock` in `SecretConfiguration` and `InventoryConfiguration`). M16(i) measured: the provider reads `SM_DP_MASTER_KEY` / `SM_DP_MASTER_KEY_ID` via `Environment.getProperty`; relaxed binding applies only to `@ConfigurationProperties`. Mutations: launcher importing `ApplyEngineConfiguration`, worker without `@DependsOn`, aggregator without `SecretConfiguration` -> red. `AdmissionSerializationIT` flaked once in a full run, passed 2/2 alone. **At integration (`f8d5b59`):** T5.1's and T5.2's configurations were added to the aggregator (`DataPlaneConfigurationTest` updated failing-first) and their two unqualified `ObjectProvider<Clock>` lookups, which merged textually clean but would have failed the launcher boot, were qualified to `smDataClock`, guarded by a reflection test over every DP `@Bean` clock parameter.

### Task T2.7 — Target governance, `listTargets`, and the two target registration commands

**Wave:** W4. **Depends on:** T1.3, T2.0, T2.5, T2.6. **Parallel with:** T4.2, T4.3, T3.3 (disjoint; merges before T3.3 by convention).

**Files:** Create `backend/cp-api/src/main/java/com/solace/sm/cp/api/target/{TargetDirectory,TargetRegistration,TargetView}.java`;
Create `backend/cp-core/src/main/java/com/solace/sm/cp/target/{TargetService,TargetConfiguration}.java`,
`…/cp/persistence/{JdbcTargetRepository,TargetRegistrationMain}.java`; Modify `…/cp/persistence/JdbcResourceScopeDirectory.java:44`
(`DEPLOYMENT_TARGET` → the target's `ownership_scope`); Modify `ControlPlaneConfiguration.java` (one import);
Create `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/TargetConnectionMain.java`;
Create `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/{TargetController,TargetList,Target}.java`;
Create tests `cp-core/.../{TargetServiceIT,TargetRegistrationMainTest,TargetRegistrationMainIT}.java`,
`dp-core/.../persistence/{TargetConnectionMainTest,TargetConnectionMainIT}.java`, `console-gateway/.../TargetControllerTest.java`,
`launcher/.../TargetEndpointsIT.java`; extend `SchemaMirrorLockTest` with the `Target`/`TargetList` records.

**Two commands, one per plane (M3; `I7`).** Target registration is the provisioning path for a Target (§5.5:
`target.create` has no endpoint anywhere in the contract). It writes both planes, and no single module may: a
cp-core class may not import dp-core, and vice versa (`PlaneBoundaryTest.java:53-61`). So it is two standalone
`main`s, each in its plane's persistence package, each shaped like `SmControlLockoutRecoveryMain` (runtime credential
from the environment, collaborators constructed by hand, **no Spring context**, exit codes, nothing echoed):

1. **`TargetRegistrationMain` (cp-core)** authenticates a principal through cp-core's own `LocalLoginService`
   (username + password read from the console, never a flag or an env var — the same throttle and audit the HTTP
   login uses), then `authorizationService.require(principal, TARGET_CREATE, <ownership scope resource>)` — a verb
   check at the scope the target will be owned at, never a role check (OR-8). It inserts `sm_control.target`
   (audit `TARGET_REGISTERED`, actor = that principal, `RequestOrigin` client address `127.0.0.1`) and prints the new
   `targetId` and nothing else.
2. **`TargetConnectionMain` (dp-core)** takes that `targetId`, the connection fields, **the envelope**
   (`allowedConfigRoot`, `allowedContainerPrefix` — B2) and a private-key **file path** the operator names once; it
   reads the key into a `char[]`, stores it through `SecretStore` (purpose `SSH_PRIVATE_KEY`), writes
   `target_connection`, and zeroises the buffer. Its authority is the DP runtime credential on the host — it cannot
   call CP authorization (`I7`), and it needs none: a connection row is inert until a CP target with that id exists
   and a principal passes `target.use` at its ownership scope (T4.4). A connection row with no CP target is never
   reachable; a CP target with no connection row is listed with `availability` degraded ("no connection record").
   Both commands are idempotent on `targetId`.

`listTargets`: `x-sm-scope: reach` (S0 point 24, T1.3): pre-handler `target.view` held anywhere in the organization;
items are the organization's targets filtered by `ReachAuthorization.authorizedSubset(…, DEPLOYMENT_TARGET, ids)` —
downward reach at each target's `ownershipScope`. ⛔ Not `ScopeVisibility` (cp-core, either-direction, and forbidden
to the gateway by `gatewayUsesApisOnly`, `PlaneBoundaryTest.java:63-70`).

- [x] **Step 1: Failing tests.**

```java
@Test void listTargetsReturnsOnlyTargetsTheCallerHoldsTargetViewOn() { }   // an env-bound Operator sees its env's targets only
@Test void anOrganizationOwnedTargetIsNotListedForAnEnvironmentBoundOperator() { }   // downward reach, S0 point 22
@Test void listTargetsCarriesNoStateVersion() { }                     // OR-38
@Test void aTargetWithNoConnectionRecordIsListedAsDegradedNotHidden() { }
@Test void theCpCommandRefusesAPrincipalWithoutTargetCreateAtTheOwnershipScope() { }
@Test void theCpCommandRefusesASolutionInstanceOrInstallOwnershipScope() { }
@Test void theDpCommandNeverEchoesTheKeyAndZeroisesTheBuffer() { }
@Test void theDpCommandRefusesAnEnvelopeOfSlashOrAnEmptyPrefix() { }  // B2: a root of "/" is no envelope
@Test void bothCommandsAreIdempotentOnRerun() { }
@Test void neitherCommandStartsASpringContext() { }                   // M3: no second worker pool, ever
@Test void theTargetWriteIsAuditedAsTargetRegisteredWithTheActor() { }   // as sm_cp_runtime
@Test void listTargetsIsReachableInTheProductionBoot() { }            // TargetEndpointsIT over real HTTP
```

- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY cp-core`, `VERIFY dp-core`, `VERIFY console-gateway`, `VERIFY launcher`, `VERIFY arch-tests`.**
- [x] **Step 5: Prove they fire.** Drop the per-item subset → the organization-owned test red. Skip `require` in the
  CP command → its refusal test red. Accept `/` as a root → the envelope test red. Revert each.
- [x] **Step 6: Commit.** `feat: target governance, listTargets served, and the two plane-local target registration commands (OR-28)`

**May claim:** `listTargets` **served**. Target *creation over HTTP* does not exist (§5.5, routed).

**Results (T2.7, 2026-10-05).** `8037a39` (merged as `42ecfb6`) target governance; `listTargets` served (Reach, `TARGET_VIEW` on `DEPLOYMENT_TARGET`, real-app `TargetEndpointsIT` 5); two standalone registration mains, no Spring context (`TargetRegistrationMainIT` 6, `TargetConnectionMainIT` 5; 7 after the S2/S3 fixes and OR-89 — its host-key refusal is now the SAME organization registering a host key twice, and another organization may register it). Notes: the `.gitignore` `target/` rule hid the packages `cp.api.target`/`cp.target` — added `!**/src/**/target/`; `dp-api` `TargetConnectionService.liveEnvelopes` + `TargetEnvelope` added; the DP command's `created_by` defaults to a sentinel or `SM_TARGET_ACTOR_ID`; the CP command is idempotent on organization + name; a must-rotate principal is refused. Implementation was written before the tests — proved by mutation instead.

---
**S2 slice review — dispositions (Opus, 2026-10-05; fixed on `p010/merge2`, each failing check seen red first).**

- **MAJOR AuthorizationIT red at `DEPLOYMENT_TARGET` — FIXED.** The kind left the "unbacked" list (it resolves through `sm_control.target`, which exists only from V013); `TargetServiceIT.aTargetResolvesToItsOwnOwnershipScopeAndAnUnknownIdFailsClosed` pins it. **Correction to the T2.7 Results above:** its "`VERIFY cp-core` green" was not true of the merged head; the cp-core failsafe run failed on this one test until this fix.
- **MAJOR I5 defence in depth — FIXED.** `EnvelopeSecretStore.resolve` and `EnvelopeHashKeySource` now open the envelope with the CALLER's tenant (and id) and refuse a row for another tenant/id as NOT_FOUND; new tests use a port whose lookups ignore the tenant. Mutation: check removed with caller arguments kept, the AAD alone still refuses (AUTHENTICATION_FAILED); check removed and the row's own arguments restored, `theAadIsASecondEnforcer…` red.
- **MINOR master key sources — FIXED.** `SecretConfiguration.keyLookup` reads the six `SM_DP_MASTER_KEY*` names from the process environment only and refuses a key found in any other source, naming the variable (never the value); tests lift it with the explicit `sm.dp.master-key.test-accept-property-sources=true` (the harnesses set it). That flag can also be passed on a command line, so it narrows rather than seals the surface; a sealed seam would need a test-only bean and ~15 call sites. Javadoc corrected. Mutation red (`SecretConfigurationKeySourceTest`).
- **MINOR key re-wrap retire query — FIXED.** The summary prints `SELECT key_id, count(*) FROM sm_secret.secret GROUP BY key_id` and "retire only when its count is 0"; asserted in `KeyRotationMainTest`. The install-runbook section stays T12's.
- **MINOR fresh-install canary — FIXED, flagged.** A first canary is minted only when `sm_secret.secret` is also empty, via `CanaryRows.anySecretExists()` (`SELECT EXISTS`, a boolean; no tenant id, key id or value leaves the database). **Owner to confirm this boolean is not an `OR-49` cross-tenant read**; the `JdbcKekCanaryRepository` comment, which said the check touched no secret table, is corrected. The IT fixture `resetCanaries` now empties `sm_secret.secret` too.
- **MINOR V007 database gaps — FIXED** (V007 is unpushed, edited in place): a lifecycle trigger (`pending`→`active`/`abandoned` only; an unknown value is left to the CHECK; same-value writes pass); the envelope trigger refuses a retired target (23514, names `target_id`); a retire guard refuses retiring a target with live placements. One IT case each; the stamp-contention test re-writes the current value each round because the lifecycle no longer moves back and forth.
- **MINOR idempotent registration — FIXED.** Re-running prints "already registered … credential unchanged (use credential rotation)" instead of "recorded"; a retired row is refused. The stored key is still not compared with the supplied one.
- **MINOR `target_connection_host_once` — CLOSED by OR-89 (owner, 2026-10-05).** (1) The rule is **per organization**: `sm_data` V007 (unpublished, edited in place) makes the index `(tenant_id, host_key_fingerprint) WHERE retired_at IS NULL`. Two organizations may each register the same machine; one organization may not register it twice. `InventorySchemaIT.aSecondLiveTargetForTheSameHostKeyIsRefused` (alias and second port refused in one organization; another organization accepted) and `TargetConnectionMainIT.anotherOrganizationMayRegisterTheSameHostKey` were red first; dropping `tenant_id` from the index turns both red. (2) One sshd presents several host keys, so pinning another algorithm's fingerprint registers the same host twice. A real fix needs a `host_key_algorithm` column, a command argument and T6.1's session open to restrict its negotiated host-key algorithm to the pinned one, or to refuse a host whose presented key is pinned elsewhere; carried to **T6.1** as an obligation. The retire-under-live-placements half is fixed above. *(Added by the plan 011 integrator hand-off, `OR-92` OQ-23: the index is per organization, so nothing in the database stops two organizations that share one machine from naming the same metrics or health host port (`placement_health_port_once` is per target, and a target is per organization too). The ruling is a runbook rule, not a constraint: organizations sharing one machine must partition host ports.)*

---

## S3 — The package catalog, the Java validator, entitlement, `catalog.package`

### Task T3.0 — Choose the JSON-Schema library (measured, recorded)

**Wave:** W1 (after T0.4b, in the same agent — the licence gate exists before the first new dependency). **Depends
on:** T0.4b merged. **Parallel with:** W1.

**Files:** Modify `backend/cp-core/pom.xml` (the one new external dependency, exact version pinned); this plan's
Measured-facts row M1; draft a decision row (`«next free BE-D»`: the library, the version, the licence, and that
`BE-D10`'s "mirror-and-lock, no codegen, no producer-side runtime validation" is **unaffected** — this is
*consumer*-side load-time validation, `backend-consumption.md` §9's `BE-D11`, **P**).

- [x] **Step 0: Licence (`I8`).** Record the library's and every transitive dependency's licence from their own
  repositories at the pinned version (`REQ-014` already records `com.networknt:json-schema-validator` and
  `com.ethlo.time:itu` as Apache-2.0 — re-verify at the version M1 pins); T0.4b's gate must pass with the dependency
  added.
- [x] **Step 1: Fill M1.** List the keywords `contracts/solution-package.schema.json` actually uses
  (`python3 -c 'import json,re;…'` over the file — record the set), and confirm the candidate supports each at
  draft 2020-12. A library that supports "2020-12" but not one keyword the schema uses is disqualified.
- [x] **Step 2: Ask the user for one online resolution** (`mvn -f backend/pom.xml -pl cp-core dependency:resolve`
  without `-o`). ⛔ Do not proceed offline-with-a-guess.
- [x] **Step 3: Commit** with T3.1 (one commit, so the dependency never lands without its first user).

**Results (T3.0, 2026-10-05).** `4db6ee2` `docs/tasks/t3-0-json-schema-library.md`: networknt 2.0.8 (Apache-2.0) + itu 1.14.0; all 24 keywords the schema uses are supported at 2020-12; one online resolution (OR-86); the bundled `ucd/` tables approved with notices (OR-87). Landed as its own commit, not with T3.1's (Step 3's "one commit" was not followed).

### Task T3.1 — The Java package validator, at parity with `validate-package.py`

**Wave:** W1 (same agent as T3.0). **Parallel with:** W1 (owns only `cp-core` `catalog.validation` + its pom line).

**Files:** Create `backend/cp-core/src/main/java/com/solace/sm/cp/catalog/validation/{PackageValidator,SchemaGate,SignatureGate,OverlapGate,SemanticGates,GateFailure,ValidationResult}.java`;
Create `backend/cp-core/src/test/java/com/solace/sm/cp/catalog/validation/{PositiveFixtureParityTest,NegativeFixtureParityTest,OverlapParityTest}.java`.

Gates, named as the Python names them (`validate-package.py:18-176`): gate 1 schema; gate 2 signature; gate 3
overlap across loaded packages of one catalog entry; the semantic gates 4, 5, 5b, 9, 10, 12, 13, 14. Gates 6, 7, 8
and 11 are schema-level: the Python reports them **as `gate1`** (its only schema label, `validate-package.py:22`),
so the parity mapping is `gate06-*`, `gate07-*`, `gate08-*`, `gate11-*` → `gate1` (m1, measured).

**Gate 2 in production (m1, measured):** the Python refuses `author: solace` without a `signature` and refuses
`author: dev-mode` unless `--dev-mode` (`validate-package.py:26-34`); it checks presence, not a cryptographic
signature. Production load runs gate 2 **with dev mode off** — exactly the Python's default — so a `dev-mode`
package (`engine-edge`, `filemesh-ftp-solace` — measured by `grep '"author"'`) is refused `422` in production, and
every load is recorded `signature_status = 'unsigned'` because nothing verifies a signature (OR-40). The parity
tests run the positive fixtures with dev mode **on** for exactly the two dev-mode fixtures, as `run-checks.sh`
does for `filemesh-ftp-solace` (`run-checks.sh:131-132`). ⚠️ `engine-edge` is not in `run-checks.sh` step 1 at all
(measured); the Java test includes it and records that it is the first gate run over it.

- [x] **Step 1: Failing parity tests.**

```java
@ParameterizedTest @MethodSource("positives")   // contracts/validation/fixtures/*.package.json (dev mode on for the two dev-mode ones)
void everyPositiveFixturePassesEveryGate(Path pkg) { }
@Test void aDevModePackageIsRefusedByGate2WithDevModeOff() { }   // the production policy
@ParameterizedTest @MethodSource("negatives")   // contracts/validation/negative/*.json — assert there are 19 (17 measured + T0.1a's two)
void everyNegativeIsRejectedByTheGateItsFileNameNames(Path negative) {
    // gate05b-when-value-not-an-option.json must fail gate "5b" — and ONLY that gate; gate06/07/08/11 files map to
    // "1" (the Python's own label). A validator that rejects every negative for a schema reason passes a count test
    // and proves nothing.
}
@Test void overlappingAppliesToIsRejectedByGate3() { }    // contracts/validation/fixtures/overlap/*.json (m1: the measured path)
@Test void theValidatorAndThePythonGateAgreeOnEveryFileInBothDirectories() {
    // The Python has no machine-readable mode (measured: validate-package.py:183-217 prints text only). So run
    // `python3 contracts/validation/validate-package.py <file> --expect-fail` per negative and parse the gate token
    // from its "-> gate<id> …" line (it prints errs[0]); run the positives without --expect-fail and require "PASS".
    // Compare to Java's verdict per file. Skipped with its reason only if python3/jsonschema is absent — never a
    // hand-written table standing in for the Python.
}
@Test void noGateFailureMessageEchoesAFieldValue() { }    // a secret-shaped default in a package must not reach a log
```

- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY cp-core`; `./contracts/run-checks.sh --strict` (Python gates unchanged).**
- [x] **Step 5: Prove they fire.** Disable gate 5's entailment check → `gate05-when-omits-ancestor` passes → its
  parity case red. Make gate 13 a no-op → red. Revert.
- [x] **Step 6: Commit.** `feat(cp-core): the Java package validator at parity with the Python gates (BE-D11 consumer side)`

**Results (T3.1, 2026-10-05).** `d56a1fa` the Java package validator at parity with `validate-package.py` on 3 positives, 19 negatives, the overlap pair and 30 derived mutants (the Python run as oracle per file); gate messages never echo values; Jackson 2.21.4 compatible; `cp-core` unit 240 (57 new). Mutations across gates 1/2/5/5b/13/14, the overlap `<=`, the Python bool quirks and the `SchemaGate` message -> red. Two Python quirks (`true` as an int; `true == 1`, `1 == 1.0`) are preserved and pinned — route to the `contracts/validation` owner.

### Task T3.2 — The catalog store, entitlement, `loadPackage` and `listSolutions`

**Wave:** W2. **Depends on:** T1.3, T2.0, T3.1. (The entitlement command's authority is ruled — owner ruling 2026-09-29 (Q-E), `OR-48`.)
**Parallel with:** T2.2, T2.4.

**Files:** Create `backend/cp-api/src/main/java/com/solace/sm/cp/api/catalog/{SolutionCatalog,PackageLoad,LoadedPackage,CatalogEntry,EntitlementRefusedException,PackageRejectedException}.java`;
Create `backend/cp-core/src/main/java/com/solace/sm/cp/catalog/{CatalogService,EntitlementService,CatalogConfiguration}.java`,
`…/cp/persistence/{JdbcCatalogRepository,JdbcEntitlementRepository,EntitlementMain}.java`; Modify
`JdbcResourceScopeDirectory.java:44` (`SOLUTION_PACKAGE` → the organization, OR-14); Modify `ControlPlaneConfiguration.java` (one import);
Create `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/{SolutionController,Solution,SolutionList,LoadPackageRequest,LoadPackageResult}.java`;
Create tests `cp-core/.../{CatalogServiceIT,EntitlementMainTest,EntitlementMainIT}.java`, `console-gateway/.../SolutionControllerTest.java`,
`launcher/.../CatalogEndpointsIT.java`; extend `SchemaMirrorLockTest`.

**The entitlement command (M3; owner ruling 2026-09-29 (Q-E)).** `EntitlementMain` is a standalone `main` in
`cp.persistence`, shaped like `SmControlLockoutRecoveryMain` (runtime credential from the environment, no Spring
context, exit codes, nothing echoed); it needs nothing from any other task (the earlier draft's
`SetupAuthentication` from T2.7 is gone). As ruled, its authority is the runtime credential on the host and it
writes with actor `NULL`, operation `ENTITLEMENT_SET`, client address `operator-cli` (the recovery command's own
constant, `SmControlLockoutRecoveryMain.java:62`), which V013's table-scoped exemption admits (T2.0). No verb is
added or bent.

**Interfaces produced:**

```java
public interface SolutionCatalog {
    /** OR-40: refuses a solution the organization is not entitled to. Validates (T3.1) before any write. */
    LoadedPackage load(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String packageJson);
    List<CatalogEntry> list(UUID organizationId);                                   // filtered by the caller in the gateway
    Optional<LoadedPackage> find(UUID organizationId, UUID catalogSolutionId, String packageVersion);
    /** For projection and declare: the canonical document text, exactly as stored. */
    Optional<String> documentOf(UUID organizationId, UUID catalogSolutionId, String packageVersion);
}
// OR-40's write path is EntitlementMain only (no cp-api write port: nothing over HTTP may set an entitlement in v1).
// The read the declare pre-check needs is InstanceGovernance.withinQuota (T4.3).
```

A load: validate (all gates, gate 3 against the entry's already-loaded packages) → entitlement check (a solution
with no `entitlement` row is refused, `ENTITLEMENT_REFUSED`) → in one CP transaction with audit context
`PACKAGE_LOADED`: upsert `catalog_solution` (first load of that solution in the org mints `catalog_solution_id`),
insert `solution_package` (`digest` = sha256 of the canonical document; `signature_status = 'unsigned'`). A
re-load of an identical `(solution, packageVersion)` with an identical digest is idempotent (`200`, same result); a
different digest under the same version is `422 VALIDATION_FAILED` ("version already loaded with different
content"). `stateVersion` of a catalog entry = the max `load_seq` of its packages — the identity column is the
sequence, so no trigger is needed CP-side (recorded in the decision draft of T3.0's commit, `«next free BE-D»`).

- [x] **Step 1: Failing tests.**

```java
@Test void loadRequiresPackageLoadAtTheOrganization() { }                 // OR-14
@Test void anUnentitledSolutionIsRefusedWith409EntitlementRefused() { }
@Test void aPackageFailingAGateIs422AndNamesTheGateAndWritesNothing() { }
@Test void anIdenticalReloadIsIdempotent() { }
@Test void aDifferentDocumentUnderAnExistingVersionIsRefused() { }
@Test void theLoadIsRecordedUnsignedAndTheResponseSaysSo() { }            // OR-40
@Test void theLoadIsAuditedAsPackageLoadedWithTheActor() { }              // as sm_cp_runtime
@Test void listSolutionsReturnsCatalogEntriesWithAStateVersionThatRisesOnEachLoad() { }
@Test void anEnvironmentBoundOperatorCanListTheCatalog() { }              // S0 point 24: reach, no item filter
@Test void aPrincipalHoldingSolutionViewNowhereIs403() { }
@Test void aDevModePackageIsRefused422InProduction() { }                  // T3.1's gate-2 policy
@Test void theEntitlementCommandWritesAnAuditedRowWithNoActorOnEntitlementOnly() { }   // owner ruling 2026-09-29 (Q-E)
@Test void theEntitlementCommandNeverStartsASpringContext() { }           // M3
@Test void loadAndListAreReachableInTheProductionBoot() { }               // CatalogEndpointsIT
```

(The earlier draft's `listSolutionsCarriesEntitledFalse…` is removed, m2: v1 has no withdrawal — no `DELETE` grant
and `max_instances > 0` — so the state it tests cannot be produced; S0 point 20 drops the field.)

- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY cp-core`, `VERIFY console-gateway`, `VERIFY launcher`, `VERIFY arch-tests`.**
- [ ] **Step 5: Prove they fire.** Skip the entitlement check → its test red. Write the row before validation → the
  gate test's "writes nothing" assertion red. Classify `listSolutions` as organization-scoped `Catalog` → the
  env-bound test red (`403`). Revert each.
- [x] **Step 6: Commit.** `feat: the package catalog — loadPackage and listSolutions served, entitlement at load (OR-40, OR-41)`

**May claim:** `loadPackage`, `listSolutions` **served**; packages are **unsigned** (OR-40). **Not** `catalog.package` (T3.3).

**Results (T3.2, 2026-10-05).** `023f451` (merged as `0c5f74e`) catalog store, entitlement at load, `loadPackage` (201 new / 200 idempotent reload) and `listSolutions` served (Reach pre-check only, no per-item subset per OR-51: a catalog entry has no scope path of its own), `EntitlementMain` (`SM_CONTROL_ENTITLEMENT_SET_BY`, exit 3 if not an active principal); `CatalogServiceIT` 18, `EntitlementMainIT` 6, `SolutionControllerTest` 15; byte-locked schema copy in `cp-core`; packages recorded unsigned. Not claimed: `catalog.package` (T3.3). Step 5's mutations were not reported by the lane and stay unticked.

### Task T3.3 — `CatalogEventEmitter` in `cp-core`, and the first production publisher wiring

**Wave:** W4. **Depends on:** T3.2, T2.6. **Parallel with:** T2.7, T4.2, T4.3.

**Files:** Create `backend/cp-core/src/main/java/com/solace/sm/cp/events/{CatalogEventEmitter,CpTransactionBoundary,CatalogEventsConfiguration}.java`;
Modify `backend/cp-core/pom.xml`, `…/cp/catalog/CatalogService.java` (the call), `ControlPlaneConfiguration.java` and
`backend/dp-core/src/main/java/com/solace/sm/dp/DataPlaneConfiguration.java` (one import line each), the launcher's
`src/main/resources/application.properties` — **the only launcher properties file** (measured; there is no
`application.yml`) — documenting the `sm.mesh.*` keys commented out, as the file already does for
`sm.gateway.*` (⛔ the password is supplied from the environment and never committed, `I6`);
Modify `backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/{MeshProperties,MeshBridgeConfiguration}.java` and
`…/meshbridge/MeshBridgeConfigurationTest.java` (M4, below);
Create `cp-core/.../{CatalogEventEmitterTest,CpTransactionBoundaryTest}.java`, `launcher/.../{CatalogEventIT,MeshDisabledBootIT}.java`.

This is **plan 007 Task 5a, executed as written** (`007-…md:855-990`) — read it in full; its tests, its POM notes
(`cp-core/pom.xml` gains `mesh-events` compile + test-jar, and its `<description>` at `:15-18`, which says "never …
`mesh-events` (`I7`)", is corrected in the same change with a decision row `«next free BE-D»`), its "one shared raw
transport, two plane-local after-commit wrappers" shape and its mutation list all apply unchanged. **One deviation,
recorded in the commit and the decision draft:** Task 5a had the *launcher* construct the CP-local
`AfterCommitEventPublisher`. The launcher may construct no bean (`SolutionManagerConfiguration.java:14-16`,
`CompositionBoundaryTest`), and it does not need to: `cp-core`'s own `CatalogEventsConfiguration` takes
`@Qualifier("meshEventPublisher") EventPublisher raw` — typed by the `mesh-events` interface, so `cpNeverImportsDp`
holds — which is exactly the qualified injection `MeshBridgeConfiguration.java` (javadoc of `meshEventPublisher`/`eventPublisher`) **measured** to resolve a
`defaultCandidate = false` bean (M15). Two additions:

1. **Production has no publisher at all today.** `SolutionManagerConfiguration.java:30` imports no
   `MeshBridgeConfiguration`, and that class says so itself ("the future application class `@Import`s this
   deliberately", `MeshBridgeConfiguration.java:18`). So this task adds `MeshBridgeConfiguration` to
   `DataPlaneConfiguration`'s imports (not the launcher), with **no default broker**.
   ⚠️ **Measured conflict (M4):** `MeshProperties`' compact constructor `requireText`s `sm.mesh.host`, `vpn` and
   `client-username` (`MeshProperties.java:34-36`), so importing the bridge as-is makes **every** boot without
   `sm.mesh.*` fail — every existing launcher IT included. The fix is explicit, not a condition on bean presence:
   `MeshProperties` gains `boolean enabled` (`sm.mesh.enabled`, default `false`). **Disabled:** host/vpn/username
   are not required, and the raw `meshEventPublisher` bean is a dropping publisher that counts every drop and logs
   one WARN at boot ("mesh publishing disabled: sm.mesh.enabled=false") — a **declared degraded** state, never a
   refusal to boot. **Enabled:** every existing `requireText` applies unchanged, so a *partial* configuration still
   refuses to boot (fail closed on a typo, not silently dropped). The raw bean keeps `defaultCandidate = false` and
   its qualifier either way (M15), and its drop counter stays reachable by concrete type.
2. `onPackageLoaded` is called from `CatalogService.load` **inside** the CP transaction, so the CP-local
   `AfterCommitEventPublisher` defers it to after commit.

- [x] **Steps:** plan 007 Task 5a Steps 1–6, plus `aPackageLoadInTheProductionBootPublishesOneCatalogPackageEventToALocalBroker()`
  in `launcher/.../CatalogEventIT.java`, gated behind the existing `broker.host` property exactly as the dp-core
  broker ITs are (skipped with its reason when unset — never silently green); plus `MeshBridgeConfigurationTest`
  cases `disabledNeedsNoBrokerPropertiesAndDropsAndCounts()`, `enabledWithAMissingHostRefusesToBoot()`, and
  `MeshDisabledBootIT.everyExistingLauncherBootStillStartsWithNoMeshProperties()`. Mutation: default `enabled` to
  `true` → the disabled-boot IT red; accept a partial enabled configuration → the refusal test red.
- [x] **Commit.** `feat(cp-core): CatalogEventEmitter — the CP publishes catalog.package after its own commit`

**Results (T3.3, 2026-10-05).** `17e0f4f` (merged as `d90a727`) `CatalogEventEmitter`; `cp-core` depends on `mesh-events`; `CatalogEventsConfiguration` injects `@Qualifier(meshEventPublisher)`; `CpTransactionBoundary` requires the `sm_control` resource bound; `sm.mesh.enabled` defaults to `false` (counted drops, one WARN); a partial enabled configuration refuses to boot; `CatalogEventIT` passed against a local spike broker (the customer broker was never contacted). Decision `BE-D33` (allocated at integration); `packageVersion` is the canonical version text.

---

**S3 slice review — dispositions (Opus, 2026-10-05; fixed on `p010/merge2`, red first).**

- **MAJOR NUL in a package string — FIXED.** U+0000 passed both gate-1 implementations and then failed the `jsonb` cast with PostgreSQL's CONTEXT line quoting the document (a 500 that logged a value). `CatalogService.parse` now refuses any key or string containing it as a gate-1 failure before any database call. `CatalogServiceIT.aNulCharacterIsAGateOneRefusalThatWritesAndQuotesNothing` (red first; red again with the check off). The schema-side fix (forbid NUL) is the contracts/validation owner's: see the open item below.
- **MAJOR AuthorizationIT red — FIXED** (same fix as S2's first item).
- **MINOR `[1, 1.0]` `uniqueItems` escape — FIXED.** `UniqueItemsGate` checks the three version arrays under Python `==`; three new `MutantParityTest` mutants (packageVersion, component version, field version) were red first.
- **MINOR newline divergence — PINNED.** `MutantParityTest.aTrailingNewlineInSolutionIsRefusedHereAndAcceptedByThePythonByDesign` (Java refuses, Python accepts).
- **MINOR `loadPackage` 200 — FIXED** by a reviewed contract change: `contracts/openapi/console.yaml` declares `200` (identical package already loaded). Run through `./contracts/run-checks.sh --strict`.
- **MINOR T3.0 records — FIXED.** M1 filled; the decision draft `BE-D«next»` (library, version, licence, `BE-D10` unaffected, the two library gaps, `stateVersion = max(load_seq)`, no CP trigger) is in `docs/design/backend/decisions.md` for the integrator to number.
- **MINOR `documentOf` — FIXED.** Javadoc says to re-canonicalise before comparing with the digest; `CatalogServiceIT` asserts `digest(canonical(parse(documentOf))) == digest`.
- **OPEN, for the contracts/validation owner (one item):** the Python gate accepts (a) U+0000 in strings, (b) a trailing newline under `$` (`"cache\n"`), and keeps two quirks the port reproduces on purpose (a bool is an int, `True == 1`). Tighten the schema pattern and forbid control characters, or switch the Python to full-match semantics; the Java side is already the stricter one.

- **Full gate after both fix rounds (`p010/merge2`):** `mvn -o -f backend/pom.xml clean verify` BUILD SUCCESS. Surefire + failsafe per module: cp-api 48; mesh-events 160; cp-core 274 + 253; dp-core 360 (1 skipped) + 516 (4 skipped); console-gateway 195; launcher 5 + 46 (1 skipped); demo-launcher 4; arch-tests 43. Skips: `InMemoryRuntimeAdapterContractTest` 1 (assumption), `CacheApplyWalkthroughIT` 2 (needs `-Dsm.demo.cache=true`), `JcsmpEventPublisherIT` and `MeshLifecycleIT` 1 each and `CatalogEventIT`'s broker case 1 (need `-Dbroker.host`; no broker was contacted). `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `git diff --check 6db8a0d..HEAD` clean. A first full run was red in dp-core on two ITs the new trigger/functions broke (`SmDataBootstrapIT` function and trigger inventory; `DeclaredInstanceGuardIT` abandoned an ACTIVE instance, now unreachable lawfully, so it bypasses the trigger with `session_replication_role = replica` in one superuser transaction); both fixed.

---

## S4 — Declare (OR-27, OR-41, OR-40)

**The protocol** (recorded by T4.1 as `«next free BE-D»`, built across T4.2–T4.4). The DP mints the id (OR-27); the
CP holds the governance node; there is no distributed transaction, so the order is chosen so that every crash
window leaves a state a sweep can finish:

| Step | Plane | Commits | Crash here leaves | Recovered by |
|---|---|---|---|---|
| 1 | gateway | — | nothing | — |
| 2 | CP: `instance.create` @ environment; `instance.bindTarget` @ environment; `target.use` @ the target's ownership scope; entitlement **pre-check** | nothing | nothing | — |
| 3 | DP `reserve` | `instance_state` (`pending`) + `placement` | a `pending` DP row, no CP node | sweep: `pending` older than T and not registered → `abandon` |
| 4 | CP `register` | solution node (first declare in the env, OR-41), `instance`, `scope` rows; **quota enforced here under `FOR UPDATE` of the entitlement row** (OR-40) | a CP node + a `pending` DP row | sweep: `pending` **and** registered → `activate` |
| 5 | DP `activate` | `lifecycle = active` | — | — |

An over-quota refusal at step 4 abandons the DP row synchronously (and the sweep would too); `abandon` retires the
row's placements in the same transaction, so a refused declare frees its container name, directory and health port
(M7(c)). The CP never deletes a node; an abandoned DP row never becomes visible (pending/abandoned rows are excluded
from every read, T2.5).

**Where the sweep runs — tenant-scoped, no background loop (B4).** The sweep is **lazy**: it runs inside the
requests whose correctness a stale `pending` row can affect, for that request's tenant only — before `reserve` in
every `declareInstance` (a stale row holds names and, once registered, quota), and before the read in
`listInstances`/`getInstance` (a registered-but-pending instance must become visible). A stale row in a tenant that
never declares or lists again is invisible and harmless (it holds names only against that tenant's own future
declares, which sweep it first). So the sweep needs **no tenant enumeration** and is not gated on Q-T.

### Task T4.1 — Record the declare protocol (docs only)

**Wave:** W3. **Depends on:** OR-27/OR-40/OR-41 only. **Parallel with:** everything in W3.

**Files:** Modify `docs/design/backend/decisions.md` (one `«next free BE-D»` row: the five-step table above, the
sweep's two rules, the per-tenant sweep constraint in T4.2, and that the CP never deletes a governance node);
`docs/design/DECISIONS.md` gets its register row from the integrator at merge.

- [x] **Step 1:** write the record, citing OR-27, OR-40, OR-41 and `I7`. **Step 2:** links resolve. **Step 3: Commit.** `docs(backend): the two-phase declare protocol (OR-27)`

**Results (T4.1, 2026-10-05).** `b99bfcb` (merged as `6c4508c`) the declare protocol recorded in `docs/design/backend/decisions.md` (first under a `«next free BE-D»` placeholder, allocated `BE-D32` at integration, `54ccbc3`), citing `OR-27`, `OR-40`, `OR-41`, `OR-71`, `OR-82`, `I7`; five-step table with crash windows; seven rules; sweep rules; PROPOSED.

### Task T4.2 — DP declare phases and the declare sweep

**Wave:** W4. **Depends on:** T2.5. **Parallel with:** T4.3, T2.7, T3.3.

**Files:** Modify `backend/dp-core/src/main/java/com/solace/sm/dp/inventory/InventoryService.java` and
`backend/dp-api/src/main/java/com/solace/sm/dp/api/inventory/InstanceInventoryService.java` (add the two sweep
methods below); Create `…/dp/inventory/DeclareSweepProperties.java` (`sm.dp.declare.abandon-after`, default 10 min);
Create `…/dp/inventory/DeclareSweepIT.java`.

**The sweep is composed by the gateway, per request, per tenant (B4 — see the S4 note above).** It needs one CP
answer ("which of these instance ids did the CP register?"), and `dp-core` may not import `cp-api`
(`dpNeverImportsCp`). So the DP exposes two tenant-bound methods and the gateway — the one module permitted both
`-api`s (`gatewayUsesApisOnly`) — asks the CP in between (T4.4's `DeclareOrchestrator.settle`):

```java
List<UUID> pending(UUID organizationId, int limit);                                   // bounded, tenant-bound
/** Activates every pending id in `registered`; abandons (and retires placements of) every other pending id older than the window. Idempotent. */
void settlePending(UUID organizationId, Set<UUID> registered, java.time.Duration abandonAfter);
```

No background loop, no port bean, no `ObjectProvider`-inert phase, **no tenant enumeration** — so nothing here
widens OR-44 and nothing waits on Q-T. A registered-but-pending row is activated at any age (the CP already holds
its node — activation is always safe); an unregistered row is abandoned only after the window (it may be a declare
still in flight).

- [x] **Step 1: Failing tests.** `anUnregisteredPendingRowOlderThanTheWindowIsAbandonedAndItsPlacementsRetired()`,
  `aRegisteredPendingRowIsActivatedAtAnyAge()`, `aFreshUnregisteredPendingRowIsLeftAlone()`,
  `everySweepStatementBindsTheTenant()` (a tenant-B row is never touched by tenant A's settle),
  `settleIsIdempotent()`.
- [x] **Steps 2–4:** fail → implement → `VERIFY dp-core`, `VERIFY arch-tests`.
- [x] **Step 5: Prove they fire.** Activate unregistered rows → the first test red. Revert. *(Proved in the S4 review: `settlePending` activating unregistered rows turned `DeclareSweepIT.anUnregisteredPendingRowOlderThanTheWindowIsAbandonedAndItsPlacementsRetired`, `aRetriedDeclare…` and `settleIsIdempotent` red; restored byte-for-byte, green. Dropping `tenant_id` from the lifecycle UPDATE turned `everySweepStatementBindsTheTenant` red.)*
- [x] **Step 6: Commit.** `feat(dp-core): the declare phases and the sweep that finishes an interrupted declare (OR-27)`

**Results (T4.2, 2026-10-05).** `60f2c0d` (merged as `c1c6e24`) `InstanceInventoryService.pending` + `settlePending` per `BE-D32` (activate a registered pending row at any age; abandon + retire the placements of an unregistered row older than `sm.dp.declare.abandon-after`, default 10 min; tenant-bound, no loop, batch 500); `DeclareSweepIT` 9 (red first), `DeclareSweepPropertiesTest` 3; `dp-core` surefire 340, failsafe 505 on the lane. Open: a registered id whose DP row is already abandoned is ignored (its CP node is orphaned) — `BE-D32` is silent. `OR-88` then made the name reusable after an abandon (`DeclareSweepIT` +1). Step 5's mutation was not reported by the lane and stays unticked.

### Task T4.3 — CP governance registration: instance node, solution node on first declare, quota

**Wave:** W4. **Depends on:** T2.0, T3.2. **Parallel with:** T4.2, T2.7.

**Files:** Create `backend/cp-api/src/main/java/com/solace/sm/cp/api/governance/{InstanceGovernance,GovernanceRegistration,QuotaExceededException}.java`;
Create `backend/cp-core/src/main/java/com/solace/sm/cp/governance/{GovernanceService,GovernanceConfiguration}.java`,
`…/cp/persistence/JdbcGovernanceRepository.java`; Modify `ControlPlaneConfiguration.java` (one import);
Create `cp-core/.../GovernanceServiceIT.java`.

```java
public interface InstanceGovernance {
    /** Step 4. One CP transaction, audit INSTANCE_DECLARED. Creates the env's Solution node on first declare (OR-41). */
    GovernanceRegistration register(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
            UUID environmentId, UUID catalogSolutionId, UUID instanceId);      // throws QuotaExceededException
    Set<UUID> registered(UUID organizationId, Collection<UUID> instanceIds);   // bounded batch, tenant-bound
    /** Pre-check only (step 2); the binding check is inside register(). */
    boolean withinQuota(UUID organizationId, String solution);
}
```

- [x] **Step 1: Failing tests** (as `sm_cp_runtime`): `theFirstDeclareInAnEnvironmentCreatesItsSolutionNode()`,
  `aSecondDeclareReusesTheNode()`, `twoConcurrentFirstDeclaresCreateExactlyOneNode()` (the unique index + retry),
  `theQuotaIsEnforcedUnderTheEntitlementRowLockSoTwoRacingDeclaresCannotBothPass()` (quota 1, two threads, exactly
  one succeeds), `anUnentitledSolutionIsRefused()`, `everyRowIsAuditedAsInstanceDeclared()`,
  `aDuplicateInstanceIdIsRefusedByTheGlobalUnique()`.
- [x] **Steps 2–4:** fail → implement → `VERIFY cp-core`.
- [x] **Step 5: Prove they fire.** Count without `FOR UPDATE` → the race test red (report the failure rate; force
  it with a latch if it does not fire). Revert. *(Proved in the S4 review: no `FOR UPDATE` in `JdbcGovernanceRepository.lockEntitlement` turned `GovernanceServiceIT.theQuotaIsEnforcedUnderTheEntitlementRowLock…` red on the first run; the `CyclicBarrier` makes it deterministic, 1 of 1. `twoConcurrentFirstDeclaresCreateExactlyOneNode` stays green without the lock: that test is held by the unique index and the `ON CONFLICT` backstop, not by the lock.)*
- [x] **Step 6: Commit.** `feat(cp-core): instance governance registration — solution node on first declare, quota under lock (OR-27, OR-40, OR-41)`

**Results (T4.3, 2026-10-05).** `d2b758e` (merged as `286798d`) `InstanceGovernance.register` in one transaction audited `INSTANCE_DECLARED`: entitlement row locked, quota counted, `EntitlementRefusedException` / new `QuotaExceededException`, solution node find-or-create (`ON CONFLICT`), instance + scope rows; `GovernanceServiceIT` 13 (12 red first); `registered` capped at `MAX_BATCH` 500. T4.4 must map `QuotaExceededException` to `409 ENTITLEMENT_REFUSED` and decide `DuplicateKeyException` handling. Step 5's mutations were not reported by the lane and stay unticked.

### Task T4.4 — `declareInstance`, `listInstances`, `getInstance` served

**Wave:** W5. **Depends on:** T2.7, T4.2, T4.3. **Parallel:** ⛔ alone.

**Files:** Create `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/{InstanceController,EnvironmentResolver,InstanceResolver,DeclareInstanceRequest,Instance,InstanceList,DeclareOrchestrator}.java`;
Create tests `console-gateway/.../InstanceControllerTest.java`, `launcher/.../{InstanceEndpointsIT,DeclareCrashWindowIT}.java`;
extend `SchemaMirrorLockTest`. (No `GovernanceLookup` adapter: T4.2's sweep is composed here, per request.)

**The declare body carries the instance's acquisition mode and no host** (owner rulings M-Q2/M-Q2b, `OR-71`; contract landed in T0.1
Step 8; address ruled by `OR-82`, 2026-10-05): `acquisition = {mode: direct-http | via-ssh}` — chosen by the operator, direct listed first, **no
automatic fallback**, **no host and no address of any kind in the body**. `direct-http` polls the **target's registered address**
(`target_connection.host`, fixed by T2.7's operator command) at the placement's `health_port` and package-declared `health_path`. The orchestrator
passes the mode to `reserve` as instance-level state (`instance_state.acquisition_mode`, T2.1 — there is no host column); the **mode** survives a re-target's
new placement (M-Q2) and the address follows the new target automatically; `Instance` returns the mode only, read-only. A body whose
`acquisition` carries any property besides `mode` is `422` (the contract's closed schema, `additionalProperties: false`); an unknown mode is `422` (the table's CHECK
backs it). Test: `declareBodyWithAnAcquisitionHostIsRefused422` — red against any build that reads a `host` out of the body.

`DeclareOrchestrator` runs the five steps in the table above, preceded by `settle(org)` — `pending` → CP
`registered` → DP `settlePending` (T4.2) — for the session's tenant only. `listInstances`/`getInstance` run the same
`settle` before reading. The secondary verbs are checked with `authorizationService.require` in the handler after
the body is read (§5.6 rule 1: still the one enforcement point): `instance.bindTarget` at the environment,
`target.use` at `ResourceRef.of(DEPLOYMENT_TARGET, targetId, …)` (resolved by T2.7's directory branch). The declared
package version must exist in the catalog for that solution (`SolutionCatalog.find`), else `422`.

**The probe comes from the package, the port may come from the body (M8).** The gateway reads the declared
package's component `health.probe` (`SolutionCatalog.documentOf`; `cache-2.4.package.json:491-495` declares
`{kind: actuator-health, path: /actuator/health, port: 8081}`, plus `strength: liveness` once T0.1a lands) and
builds `HealthProbeSpec(kind, path, hostPort, strength)`: `kind`, `path` and `strength` **only** from the package (a
path outside `HostProbe`'s grammar is `422`, a `broker-heartbeat`-only package gets a `null` probe), `hostPort` from
the body's `healthPort` or, when absent, the package's port. ⛔ No branch on the solution: whatever the package
declares is copied, for every MI alike (Global Constraints). The DP
never reads the package (T6.2), so this is the only place the probe can be resolved.

**The envelope (B2)** is checked by the DP's `reserve` (clean `422` naming the field) before any row on either plane
— the orchestrator calls `reserve` before `register`, so a refused placement writes nothing on the CP either.

**One organization across both planes (M2 — verified, not a gap).** The DP tenant is `Session.organizationId()`;
the CP node lands under the organization the path environment resolves to. They cannot differ once `require`
passes: the session's organization is the principal's (`JdbcSessionRepository.java:62-69`) and `require` answers
`404` for a resource in any other organization (`ScopeEvaluatingAuthorizationService.java:36`). The orchestrator
calls `require(instance.create, environment)` before either plane is written; the test below pins it.

`listInstances`: `x-sm-scope: reach` (S0 point 9/24): DP `listActive(org, catalogSolutionId)` →
`ReachAuthorization.authorizedSubset(principal, INSTANCE_VIEW, INSTANCE, ids)` → only those items.

- [x] **Step 1: Failing tests.**

```java
@Test void declareRequiresInstanceCreateAtTheEnvironment() { }
@Test void declareAlsoRequiresBindTargetAndTargetUseAndNamesWhichIsMissing() { }   // D10's two-sided rule
@Test void aTargetOwnedBySiblingScopeIsRefusedTargetUse() { }
@Test void aPlacementCollisionIs409AndWritesNothingOnEitherPlane() { }            // Review Focus 2
@Test void aConfigDirectoryOutsideTheTargetRootIs422AndWritesNothingOnEitherPlane() { }   // B2: …/.ssh + authorized_keys
@Test void aContainerOutsideTheTargetPrefixIs422() { }                            // B2
@Test void theProbeKindPathAndStrengthComeFromThePackageNeverTheBody() { }      // M8 + S0-10
@Test void theDeclareBodyCarriesTheAcquisitionModeAndItIsReturnedOnTheInstance() { }   // OR-71
@Test void aDirectHttpDeclareWithoutAHostOrAViaSshDeclareWithOneIs422() { }           // OR-71
@Test void aDeclareIntoAnotherOrganizationsEnvironmentIs404AndWritesNothing() { } // M2's pinning test
@Test void anEnvironmentBoundOperatorListsItsOwnEnvironmentsInstancesOnly() { }  // M1 + S0 point 24
@Test void overQuotaIs409EntitlementRefusedAndTheDpRowIsAbandoned() { }
@Test void anUnknownPackageVersionIs422() { }
@Test void aDeclaredInstanceIsListedAndFetchableWithItsPlacement() { }
@Test void aSiblingEnvironmentsInstanceIsNotListedAndIs404OnGet() { }            // §5.6 rule 4
@Test void everyInstanceItemCarriesItsOwnStateVersion() { }                      // §6.1 collections rule
@Test void aCrashBetweenReserveAndRegisterIsFinishedByTheNextDeclareInThatTenant() { }   // DeclareCrashWindowIT, after a restart
@Test void aCrashBetweenRegisterAndActivateIsFinishedByTheNextListInThatTenant() { }
@Test void declareListAndGetAreReachableInTheProductionBoot() { }                // InstanceEndpointsIT
```

- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY console-gateway`, `VERIFY launcher`, `VERIFY arch-tests`.**
- [x] **Step 5: Prove they fire.** Drop the `target.use` check → its test red. Reorder steps 3/4 (CP first) → the
  crash-window ITs red (a CP node with no DP row is unrecoverable by the sweep — which is why the order is what it is).
  Take the probe path from the body → the M8 test red. Skip `settle` before `listInstances` → the second crash-window
  test red. Revert each.
- [x] **Step 6: Commit.** `feat: declareInstance, listInstances, getInstance served — the two-phase declare with its sweep (OR-27, OR-29, OR-40, OR-41)`

**May claim:** the three **served**. **Not** agent auto-registration (AGW-D\*, unbuilt — the `registered`/`settle`
and quota path are what it will use), nor undeclare (open item 39d, `501`).

**Results (T4.4, 2026-10-05).** `9c7731f` `InstanceNameTakenException` (OR-88: a live-name collision is 409 `CONFLICT`); `6301023` `declareInstance`/`listInstances`/`getInstance` served: the gateway composes BE-D32 (tenant sweep, `bindTarget` + `target.use`, catalog + probe from the package, reserve, register, activate); quota -> 409 `ENTITLEMENT_REFUSED`. `DeclareCrashWindowIT` 4 (the declare-path windows - `stale`, `fresh`, `registeredPending` - are made in context A and finished after a real restart; the list-path and get-path tests make their register-then-crash rows in the RUNNING context B, so those two windows run in the restarted context but are not proven across the restart itself - the sweep holds no in-memory state); `InstanceEndpointsIT` 12; `InstanceControllerTest` 39 (red first). Mutations (each red, then restored): drop `target.use`, register before reserve, hardcoded probe, skip `settle`, drop the reach filter, drop the synchronous abandon, extra acquisition keys, ignore the sweep window, placements optional. "Reorder CP first" is proven at the unit level only. **Flags:** a `DuplicateKeyException` at register is a 500 and the DP row is left for the sweep; a CP registration that lands after the sweep abandoned the DP row is a 500 plus an orphaned CP governance node — **accepted as rare, `OR-90`** (no deadline guard; manual cleanup in `docs/runbooks/operations.md`); a missing connection record is 422 `targetId`; the first component with an HTTP probe supplies it, an HTTP probe without a path is 422, a broker-heartbeat-only package gets a `null` probe; the placement and health-path grammars mirror the DB CHECKs; the sweep window default is duplicated in the gateway; `ProblemBody` has a `CONFLICT` title; the `ENDPOINT-INVENTORY` served rows are T12.1's.


**S4 review dispositions (2026-10-05; verdict accept, no BLOCKER or MAJOR).** All six MINORs fixed:
1. *`declareInstance` answered 404 the contract did not declare.* `console.yaml` now declares `404 NotFoundOrUnauthorized` on `declareInstance`, and on `stageConfig` and `submitApply`, which had the same omission. New `SchemaMirrorLockTest.everyOperationScopedToAResourceDeclaresTheNotFoundTheServerAnswers` (every `x-sm-scope: environment | instance` operation declares 404): seen red on exactly those three, green after the contract edit.
2. *The over-quota IT only reached the step-2 pre-check.* New `launcher/.../DeclareRegisterTimeQuotaIT` wraps the real `InstanceGovernance` (a `BeanPostProcessor`, imported into the real application) so `withinQuota` always answers true while `register` stays real against an exhausted quota. Asserts 409 `ENTITLEMENT_REFUSED`, the DP row `abandoned` with 0 live placements and no `pending` row, no new CP instance node, and the same name/container/port redeclaring at once. Mutation: `abandonQuietly` removed from `DeclareOrchestrator.declare` turns it red; restored with `cmp`, green. (My first attempt wired the wrapper through a nested `@TestConfiguration`, which `@SpringBootTest(classes=…)` ignores: the pre-check refused, and the same assertion went red for the wrong reason. Fixed with `@Import`, and the mutation above is the proof that matters.)
3. *`BE-D32` rule 5 contradicted the contract on the health port.* Dated erratum added in `decisions.md` (the old wording is quoted, not silently edited): kind, path and strength from the package, the host-side port from the body's `healthPort`, no host or address from the body.
4. *Stale ratification-matrix text.* Corrected, and `OR-88`/`OR-90` named.
5. *T4.2 and T4.3 Step 5 unticked.* Ticked, citing the review's mutation evidence (above).
6. *`DeclareCrashWindowIT` overstated.* The T4.4 Results now say which windows cross the restart.


---

## S5 — Config of record (OR-33, OR-32, OR-34)

### Task T5.1 — The keyed config hash seam (OR-32)

**Wave:** W3. **Depends on:** T2.2 merged (M13: this task's `RenderedConfig` change touches 23 test files —
measured, `grep -rl 'RenderedConfig.of' backend/*/src/test` — most of them among T2.2's 35), T2.3 merged (the key's
shape); the key's *storage* is T2.4, consumed at wiring (T6.2). **Parallel with:** T2.5/T2.6, T5.2 — owns
`dp.apply.runtime` (incl. `ssh`), the engine test fixtures that build a `RenderedConfig`, and the demo files named
below.

**Measured — why the earlier draft could not work (B1):**
- The production transport **overrides** the interface default and hashes **on the customer host**: `statusSnapshot`
  runs `sha256sum -- <file>` over SSH and returns `"sha256:" + hex` (`SshjDockerTransport.java:44-66`). Changing only
  the default (`SshDockerTransport.java:19-24`) would leave production `status()` reporting an **unkeyed** digest of
  secret-bearing bytes (`SshDockerRuntimeAdapter.java:102-111` passes it straight into `RuntimeStatus.observedHash`),
  while the intent row binds a keyed one — every verify and resume comparison would disagree, and the unkeyed digest
  would keep landing in `apply_attempt.observed_hash`, which OR-32 forbids. An HMAC on the host would need the key on
  the host. **So the hash is computed in the DP, by the adapter, from bytes read back over SFTP.**
- Every other hashing site: `RenderedConfig` (`:24,33` — the record re-hashes to verify), `SshDockerRuntimeAdapter.hashOf`
  (`:178-180`: materialize read-back, rollback pre-image), `ConfigFileStore.hashOf` (`:99-101`, the local Docker
  adapter's store), and `SshDockerConfigEditor` (`:61`, `:83` — dp-core main code, used only by `demo-launcher`,
  `DemoConfiguration.java:33-36`).
- The spelling must fit the model grammar and V002's CHECK unchanged — `hmacsha256v<n>:<hex>` (T2.3 §5; the
  Migration allocation note).

**Files:** Create `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/{ConfigHasher,HmacConfigHasher,HashKeySource}.java`;
Modify `…/apply/runtime/{RenderedConfig,ConfigFileStore}.java`, `…/runtime/ssh/{SshDockerTransport,SshjDockerTransport,SshDockerRuntimeAdapter,SshDockerConfigEditor}.java`,
`…/runtime/docker/DockerAdapter.java`; Modify tests: the 23 files that call `RenderedConfig.of` (mechanical, through
one new test helper `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/TestHashers.java`, which holds the
**test-only** unkeyed `SHA256` hasher), `InMemoryRuntimeAdapter`, `AbstractRuntimeAdapterContractTest` (one new
case); Create `…/apply/runtime/{HmacConfigHasherTest,KeyedHashJournalCompatibilityIT}.java`,
`…/runtime/ssh/SshjDockerTransportCommandTest.java`; Modify `backend/demo-launcher/src/main/java/com/solace/sm/demo/DemoConfiguration.java`
and Create `…/demo/DemoUnkeyedConfigHasher.java` (the demo's own hasher — ⛔ no unkeyed hasher exists in dp-core
**main** code after this task; `VERIFY demo-launcher`).

```java
package com.solace.sm.dp.apply.runtime;
/** OR-32: the write-verification, crash-recovery and ladder hash. One owner (BE-D21), now keyed, per tenant (D-KEY §5). */
public interface ConfigHasher {
    String hash(TenantId tenant, byte[] bytes);                  // "hmacsha256v<n>:<64 hex>" for HmacConfigHasher
    boolean verifies(TenantId tenant, String hash, byte[] bytes); // any key version still loadable (D-KEY §5)
}
```

The changes, one per measured site:

1. **`SshDockerTransport.StatusSnapshot` carries bytes, not a hash:** `record StatusSnapshot(ContainerSnapshot
   container, byte[] configBytes /* null = absent; cloned */)`. The interface can then no longer *express* a remote
   hash. `SshjDockerTransport.statusSnapshot` keeps its one-connection shape (the reason for the override,
   `:48-50`) — `docker inspect` exec and an SFTP read (`readFile`'s own bounded path, `MAX_FILE_BYTES`) inside one
   `withClient` — and runs no hashing command at all.
2. **The adapter hashes:** `SshDockerRuntimeAdapter` takes a `ConfigHasher`; `status()` and `hashOf` hash with
   `placement.tenant()`. `ConfigFileStore`/`DockerAdapter` likewise.
3. **`RenderedConfig` becomes a final class** (no public canonical constructor) with one factory
   `of(TenantId, byte[], ConfigHasher)` and unchanged accessors `bytes()`/`hash()`, equality and `toString`: the
   one-owner rule (`BE-D21`) is kept **by construction** instead of by the record re-hashing with SHA-256 (which a
   keyed hash cannot do without the key).
4. **`SshDockerConfigEditor`** takes a `ConfigHasher` and a `TenantId`; `demo-launcher` supplies its own
   `DemoUnkeyedConfigHasher` (the demo is never a production authority, Global Constraints) and keeps compiling.

⚠️ **`I6` is still named by OR-32** — the keyed hash depends on resolved values; the javadoc says so and cites
OR-32's flag.

- [x] **Step 1: Failing tests.** `theSameBytesUnderTheSameKeyHashIdentically()`,
  `aDifferentKeyGivesADifferentHashForTheSameBytes()` (a guessed secret cannot be tested without the key),
  `twoTenantsNeverShareAHashForTheSameBytes()` (m5), `verificationAcceptsAnyStillLoadableKeyVersion()`,
  `theHashStringNamesItsKeyVersionButNeverTheKey()`, contract-kit case
  `theAdapterReportsBackTheHashItsOwnHasherComputes()`, `aResumedApplyVerifiesAHashBoundUnderAPreviousKeyVersion()`,
  and — the ones that fail on the **production** transport, not a fake (B1):
  `SshjDockerTransportCommandTest.theStatusCommandRunsNoHashingProgramOnTheHost()` (the built exec strings contain
  no `sha256sum`, `md5sum`, `shasum` or `openssl`; the command builder is extracted to a package-visible static so
  the test needs no SSH server), `KeyedHashJournalCompatibilityIT.theProductionSpellingIsAcceptedByTheModelGrammarAndTheV002Check()`
  (an `AttemptRow`, a `ReplicaObservation`, and an `INDETERMINATE_RESOLVED` resolution carrying `hmacsha256v1:<64 hex>`,
  written as `sm_dp_runtime`), and `aHashSpelledHmacDashSha256IsRefusedByBoth()` (pins why the spelling is what it is).
- [x] **Steps 2–4:** fail → implement → `VERIFY dp-core` (the **whole** existing engine suite is the regression proof
  that the seam changed no semantics), `VERIFY demo-launcher`, `VERIFY arch-tests`
  (`theRuntimeSeamNeverReachesBackIntoTheEngine` green).
- [x] **Step 5: Prove they fire.** Make `HmacConfigHasher` ignore the key → the different-key test red. Put
  `sha256sum` back into `SshjDockerTransport`'s status command → the command test red. Spell the hash
  `hmac-sha256:1:<hex>` → the compatibility IT red. Drop the tenant from the key lookup → the two-tenant test red.
  Revert each.
- [x] **Step 6: Commit.** `feat(dp-core): the keyed config hash, computed in the DP from read-back bytes (OR-32 — flagged: names I6)`

**Results (T5.1, 2026-10-05).** `f9fe821` (merged as `1a38076`) HMAC-SHA256 in the DP, spelled `hmacsha256v<n>:<hex>`; `StatusSnapshot` carries bytes; `RenderedConfig` has no public constructor; per-tenant `CONFIG_HASH_KEY` lazily minted in `sm_secret`, version 1, no rotation (`OR-85`). Mutations (key ignored, `sha256sum` back, `hmac-sha256:` spelling, tenant dropped from key lookup, wrong-tenant hashing, mint without `ON CONFLICT`) -> red. Note: main code was drafted before tests; proved by mutation, not by an initial red run.

### Task T5.2 — `config_generation` (V010) and `ConfigRecordService`

**Wave:** W3, **after T5.1 in the same agent** (its `content_digest` is computed with T5.1's `ConfigHasher`, and its
tests use T5.1's `TestHashers`). **Depends on:** T2.2, T5.1. The delete-trigger predicate is **ruled** (owner ruling
2026-09-29 (Q-R), `OR-50`). **Parallel with:** T2.5, T2.6 (owns `dp.config` + its repository).

**Files:** Create `…/sm_data/V010__config_generation.sql`; Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/config/{ConfigRecordService,StageCommand,StageResult,GenerationView,ConfigDraft,RenderedEntryValue,GenerationOrigin,StaleStateVersionException}.java`;
Create `backend/dp-core/src/main/java/com/solace/sm/dp/config/{ConfigRecordServiceImpl,Retention,ConfigRecordConfiguration}.java`,
`…/apply/persistence/JdbcConfigGenerationRepository.java`; Create `…/dp/config/{ConfigRecordServiceIT,RetentionIT}.java`.

```sql
ALTER TABLE sm_data.instance_state ADD COLUMN last_generation bigint NOT NULL DEFAULT 0
    CONSTRAINT last_generation_nonneg CHECK (last_generation >= 0);
ALTER TABLE sm_data.instance_state ADD COLUMN reached_rung text NULL CONSTRAINT reached_rung_known CHECK (
    reached_rung IN ('desired','rendered','persisted','restart-observed','effective-verified'));
    -- OR-37: the instance rung (lowest across live placements). Updating it stamps instance_state, which is the
    -- stateVersion instance.config-state carries (T6.4/T6.5). The runtime UPDATE grant gains (last_generation, reached_rung).
CREATE TABLE sm_data.config_generation (          -- OR-33: immutable, one per generation
    tenant_id uuid NOT NULL, instance_id uuid NOT NULL, generation bigint NOT NULL CHECK (generation > 0),
    package_solution text NOT NULL, package_version text NOT NULL,
    component_selection jsonb NOT NULL, draft_values jsonb NOT NULL, rendered_entries jsonb NOT NULL,
    content_digest text NOT NULL,                 -- keyed (OR-32) over the canonical content; drives guard 1
    origin text NOT NULL CHECK (origin IN ('edit','rollback','restore-runtime','accept-runtime')),
    derived_from_generation bigint NULL,
    created_by uuid NOT NULL, created_at timestamptz NOT NULL, state_version bigint NOT NULL,
    PRIMARY KEY (tenant_id, instance_id, generation),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES sm_data.instance_state (tenant_id, instance_id));
```

Grants: `SELECT, INSERT` and — for retention only — `DELETE`, guarded by a `BEFORE DELETE` trigger that enforces
what must **never** be deleted, whatever the configuration says: (a) the instance's latest generation
(`generation = instance_state.last_generation` — a floor of 1, so the Designer can always rebuild, OR-34), and (b)
every generation named by **any** `apply_request` row of that instance — owner ruling 2026-09-29 (Q-R), read
literally. ⚠️ "audit record": in `sm_data` the journal **is** the
DP audit record for apply/rollback/restart (D10 amendment §1.4, cited in the v2 paper G21); the journal check covers
it. The stamp trigger (T2.1 pattern) stamps on insert. Secret fields hold `{"secretRef": "<uuid>"}` only (`I6`).

**Retention, stated exactly (M12):**
- **N is configurable** (`sm.dp.config.retained-generations`, default 10, minimum 1 — OR-33's "configurable"),
  enforced by `Retention` in the service; the trigger enforces only the floor and the reference rule, because a
  trigger cannot read application configuration and must not hard-code N.
- **When it runs:** inside `stage`, in the stage transaction, for **that instance only** — after the insert, delete
  the generations outside the newest N that the trigger permits. Tenant-scoped, no background job, so no tenant
  enumeration (B4) and no Q-T dependency.
- **The effective bound, as ruled (Q-R (a)):** N + one generation per apply ever submitted for the instance
  (~2–5 KB each, per the ruling). That grows with the journal, which is never pruned today; this plan states it
  rather than calling it constant. Journal pruning is a later owner decision (Non-goals). Option (b), the narrower
  bound, was not chosen.

```java
public interface ConfigRecordService {
    /** OR-33 guard 1: content equal to the latest generation writes nothing and returns it with unchanged=true. */
    StageResult stage(UUID organizationId, UUID instanceId, StageCommand command);   // StaleStateVersionException → 409
    Optional<ConfigDraft> latestDraft(UUID organizationId, UUID instanceId);         // what getDesigner re-projects
    List<GenerationView> history(UUID organizationId, UUID instanceId, int limit);   // redacted: no values, keyed digest
    Optional<GenerationView> generation(UUID organizationId, UUID instanceId, long generation);
}
public record StageCommand(long baseStateVersion, String packageSolution, String packageVersion,
        String componentSelectionJson, String draftValuesJson, List<RenderedEntryValue> rendered,
        GenerationOrigin origin, Long derivedFromGeneration, UUID actor) {}
```

`baseStateVersion` is compared with `instance_state.state_version` under its row lock; a mismatch is
`StaleStateVersionException`. The generation is `last_generation + 1`, taken under the same lock.

- [x] **Step 1: Failing tests.**

```java
@Test void stagingAssignsTheNextGenerationUnderTheInstanceLock() { }
@Test void aStaleBaseStateVersionIsRefusedAndWritesNothing() { }                  // Review Focus 4
@Test void identicalContentWritesNoRowAndReturnsTheLatestWithUnchangedTrue() { }  // OR-33 guard 1
@Test void retentionKeepsTheNewestNPlusEveryProtectedGeneration() { }          // N from configuration, not a literal
@Test void retentionRunsInsideStageForThatInstanceOnly() { }                    // another instance's rows untouched
@Test void aProtectedGenerationCannotBeDeletedEvenOutsideTheNewestN() { }       // the trigger, as sm_dp_runtime
@Test void theLatestGenerationCanNeverBeDeleted() { }                            // the floor
@Test void aSecretFieldIsStoredAsAReferenceNeverAValue() { }
@Test void historyCarriesNoValueAndAKeyedDigest() { }
@Test void theRowIsImmutable() { }                                                // 42501 on UPDATE
```

- [x] **Steps 2–4:** fail → implement → `VERIFY dp-core`.
- [x] **Step 5: Prove they fire.** Drop the journal check from the delete trigger → its test red. Drop the floor →
  the latest-generation test red. Hard-code N = 10 in `Retention` and configure 3 → the N test red. Compare digests
  unkeyed → no test should notice semantics, but `historyCarriesNoValueAndAKeyedDigest` red. Revert each.
- [x] **Step 6: Commit.** `feat(dp-core): config of record — immutable bounded generations (OR-33)`

**Results (T5.2, 2026-10-05).** `ba25e77` (merged as `749c00c`) `V010` `config_generation` + `instance_state.last_generation` + `reached_rung`; the retention trigger refuses the latest generation and any generation an `apply_request` names; N from `sm.dp.config.retained-generations`; `ConfigRecordServiceIT` 22, `CanonicalContentTest` 8, `RetentionTest` 1; mutations (journal check, floor, hard-coded N, non-HMAC digest, stale check, guard 1, prune ignoring journal-named generations, prune not instance-scoped) -> red. Downstream: T6.3 admission must verify the generation still exists under the instance lock. `V010` anchor after push. Composed into the aggregator at integration (see T2.6).

### Task T5.3 — `getDesigner`, `stageConfig`, `getConfigHistory`, ladder `desired`/`rendered`

> **From S1's review (2026-10-05):** the byte identity proven in T1.2 is a property of `ViewModelJson` (cp-core, package-private), which the gateway cannot reach (`gatewayUsesApisOnly`). A served `ViewModel` must be written through a `cp-api`-exposed writer port backed by `ViewModelJson`, or carry a byte-level test at the HTTP boundary against one fixture; Jackson over the `cp-api` records alone emits `ActionView` `enabled` before `visible` and has no `FieldValue` discriminator.

**Wave:** W6. **Depends on:** T1.2, T4.4, T5.1, T5.2. **Parallel with:** T6.2 (disjoint: gateway vs `dp.apply.production`).

**Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/config/{ObservedStateService,SecretReferences}.java`
(`SecretReferences.exists(org, secretRef)` over T2.4's store; `ObservedStateService` — the DP supplies
the `ObservedState` projection needs: version `VERSION_UNVERIFIED` for `dp-remote`, config state, replicas — empty
until health, T7.2); Create `backend/dp-core/src/main/java/com/solace/sm/dp/config/{ObservedStateServiceImpl,ConfigLadderReader}.java`;
Create `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/{DesignerController,StageConfigRequest,Designer,StagedConfig,ConfigHistory,ConfigLadder,GovernanceController}.java`;
Create tests `console-gateway/.../DesignerControllerTest.java`, `launcher/.../DesignerEndpointsIT.java`; extend `SchemaMirrorLockTest`.

**The stage flow (OR-34), in the gateway, in this order:** read the latest generation's `stateVersion` context →
fetch the package document (`SolutionCatalog.documentOf`) → fetch `ObservedState` (DP) → **re-project and check**
with the landed `DesignerStageValidator` (CP, T1.2 — wired as a bean by T1.2's remainder) → refuse `422` if any
`block` validation, or on any `StageRefusalReason` (`UndeclaredDraftKey`, `ValueMismatch`, `MissingFromClaim`,
`UnexpectedInClaim` — a structured comparison, not a `hashInput` byte compare; T1.2 records why `hashInput` is not
ported) → call `ConfigRecordService.stage` with **the server's** result (`StageOutcome.Accepted.serverResult`).
A secret field carrying a literal value is `422` (the fixture `secret-literal-is-refused-and-never-rendered`); a
`secretRef` must resolve to an existing `sm_secret` row of purpose `CONFIG_FIELD` (`SecretStore.exists`, via a
`dp-api` port), else `422`.

`getDesigner`: re-project the latest `ConfigDraft` with `DesignerProjector` (or an empty draft for generation 0) and return `{stateVersion,
generation, viewModel}`. `getConfigLadder` (partial): `desired` = latest generation, `rendered` = the same
generation's rendered set with its keyed digest; `persisted`/`restart-observed` report `present: false` with
`availability` degraded "apply wiring lands in S6" **until T6.5** — declared, never silently absent.

- [x] **Step 1: Failing tests.**

```java
@Test void theDesignerIsTheServersProjectionAndCarriesTheInstanceStateVersion() { }
@Test void theDesignerAfterAReloadRebuildsTheSameBranchesFromTheStoredDraft() { }   // the reason OR-34 stores draftValues
@Test void aRenderedMismatchIs422AndWritesNothing() { }
@Test void anUndeclaredKeyIs422() { }
@Test void aBlockingValidationIs422() { }
@Test void aSecretLiteralIs422AndIsNeverEchoed() { }
@Test void aStaleBaseStateVersionIs409() { }                                        // Review Focus 4
@Test void reStagingIdenticalContentReturnsUnchangedTrue() { }
@Test void historyIsRedactedServerSide() { }                                        // I6
@Test void theLadderDeclaresPersistedAndRestartObservedDegradedUntilApplyIsWired() { }
@Test void designerStageHistoryAndLadderAreReachableInTheProductionBoot() { }
```

- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY console-gateway`, `VERIFY launcher`, `VERIFY arch-tests`.**
- [x] **Step 5: Prove they fire.** Store the client's `rendered` instead of the server's → the undeclared-key test
  red. Skip re-projection (call `ConfigRecordService.stage` without `DesignerStageValidator`) → the mismatch test
  red. Revert.
- [x] **Step 6: Commit.** `feat: getDesigner, stageConfig, getConfigHistory served — the server re-projects and stores its own rendered set (OR-34)`

**Results (T5.3, 2026-10-05).** Commit `0a3a4b7` on branch `p010/t53` (off `p010/merge3`), cherry-picked here as `2cbfa69`: `feat: getDesigner, stageConfig, getConfigHistory served — the server re-projects and stores its own rendered set (OR-34)`. Served: `getDesigner`, `stageConfig`, `getConfigHistory`, `getConfigLadder`. A new `cp-api` `DesignerCodec` writes the view model through `ViewModelJson`; the HTTP IT compares 15 or more cache fixtures against the TS bytes, key order included, and only `status` by shape (the observed state differs). Stage never writes to a host, and `apply_request` stays 0.

*Commands (all green on the lane).* `console-gateway -am verify`: 272 unit tests (`DesignerControllerTest` 32; `SchemaMirrorLockTest` 25, 3 new). `cp-core,dp-core -am verify`: cp-core 160 plus `ControlPlaneConfigurationIT` 11; dp-core 372 (new: `DesignerCodecTest` 36, `ObservedStateServiceImplTest` 7, `SecretReferencesImplTest` 5). `arch-tests,launcher -am verify -Dit.test=…`: `DesignerEndpointsIT` 16, `InstanceEndpointsIT` 12, `ProductionAuthenticationIT` 4 (route-set assertion updated), arch-tests 43. `contracts/run-checks.sh --strict` passes.

*Mutation proofs (each red, then restored with `cmp`).* Store the client's `rendered` instead of the server's: 4 gateway tests red. Ignore the validator's refusal: mismatch and undeclared-key tests red. Hidden secret literal, literal check removed, blocking check removed, reference resolution removed: red. Draft read before version: red. 409 handler removed: red. `CONFIG_FIELD` purpose ignored: unit test red and `DesignerEndpointsIT` red. Tenant filter in `SecretReferencesImpl`: did not fire at first because the filter was redundant; a leaky-port test was added and it now fires. Codec tree sorted: 33 fixtures red. Records serialized by Jackson instead of the codec: unit test and the HTTP byte test red. Ladder `reached`/`present` mutations: red. A `ConfigHistory` mirror mutation: red. Test-first: `DesignerCodecTest` and the dp-core tests were seen red on missing classes; the gateway code was drafted before its tests (as for T5.1), so those are proven by the mutations above.

**Flags (open for the owner).** (1) ~~Pre-existing: `Availability.state` is written as `DEGRADED` but `console.yaml` says lowercase~~ — fixed in the S5 review round below. (2) T6.5 must reset or own `reached_rung` on stage: the ladder now derives `reached` from the generation alone. (3) ~~T5.2's digest includes layout~~ — fixed in the S5 review round below. (4) Beyond the plan: duplicate instance keys and unprojectable selections are refused with 422 (`SELECTION_UNRESOLVED`); a secret reference must be a `CONFIG_FIELD` secret of the organization. (5) History `by` is the principal id, not a username. (6) The options catalog is passed empty, so `CATALOG_UNRESOLVED` diagnostics appear for fields with an `optionsSource`. (7) Postgres containers ran while other lanes had theirs up; not serialized with them.

**S5 slice review: disposition (2026-10-05; 3 MAJOR, 4 MINOR; review verdict `changes-required`).** Each fix was written test-first and seen red before the code change (the ssh test was compile-red on the missing seam).

- **MAJOR 1, secret reference in a hidden or virtual field: FIXED.** `DesignerOrchestrator` now accepts a non-empty value in ANY masked field (visible, hidden or virtual) only as exactly `secretRef://` plus a canonical 8-4-4-4-12 UUID (a prefix alone, or any other string, is a 422 and is never quoted), and resolves EVERY such reference with `SecretReferences.exists(organization, uuid)` before anything is staged, not only the references of rendered entries. A rendered secret entry must be one of those resolved references. Tests: `DesignerControllerTest` (hidden `secretRef://hunter2` gives 422, nothing staged, store not asked; hidden and virtual well-formed UUID that resolves to nothing give 422 and the store was asked in the session's organization) and `DesignerEndpointsIT` (both hidden cases over HTTP, nothing containing `hunter2` stored). Mutations, each red then restored with `cmp`: resolve nothing (4 tests red, including the existing visible-reference tests); drop the UUID shape check (the prefix-only case and the not-a-UUID case red, as 500s).
- **MAJOR 2, layout in the digest: FIXED (ruled as the reviewer's primary fix, UI-D20 stands).** `CanonicalContent` digests a copy of the selection with every `layout` removed; the stored `component_selection` keeps the full selection. A layout-only save returns `unchanged=true`, writes no row and draws no stamp, so that save's layout is not persisted (generations are immutable). Tests: `CanonicalContentTest.presentationLayoutIsNotContentButTheSelectionStillStoresIt` and `ConfigRecordServiceIT.aLayoutOnlyRestageIsUnchangedAndWritesNoRowAndDrawsNoStamp`. Mutation: digest the whole selection again, `CanonicalContentTest` red. If the owner prefers every layout change persisted, that is a ruling plus a `console.yaml` description change.
- **MAJOR 3, `Availability` wire values: FIXED.** `AvailabilityState` and `AvailabilityMode` carry explicit lower-case wire values through `@JsonValue wireValue()` (as `TargetKind` does); the four assertions in `DesignerControllerTest`, `TargetControllerTest`, `DesignerEndpointsIT` and `TargetEndpointsIT` now expect `degraded`; `SchemaMirrorLockTest.availabilityEnumsSerializeAsTheContractsLowerCaseValues` compares each enum's serialized values with `Availability.state` and `Availability.mode` in `console.yaml`. Mutation: remove `@JsonValue`, the lock test red.
- **MINOR 1, `rendered` rung digest: DOCUMENTED, not changed.** Until T6.5 replaces it, `rendered.configHash` is the generation content digest (the same value as `desired`); `ConfigLadderView` and `ConfigLadderReader` say so, and T6.5 below now states that it changes the meaning and that S6 must not compare it with the keyed file-bytes hash.
- **MINOR 2, fractional layout: FIXED.** `selectionJson` stores the layout numbers exactly as sent; `StateModel.Layout` stays int (a projection input the projector does not use for layout), so only the in-memory projection truncates. Test: `aFractionalLayoutPositionIsStoredAsTheClientSentItNotTruncated`.
- **MINOR 3, status exec guard: FIXED.** `SshjDockerTransport.statusSnapshot` now runs its host exec only through the package-visible `readStatusContainer(placement, RemoteExec)`; `SshjDockerTransportCommandTest.everyExecStringTheStatusSnapshotRunsIsRecordedAndNoneRunsAHashingProgram` records every command and refuses any hashing program. Mutation: add `sha256sum` beside the inspect, test red. A command added to `statusSnapshot` directly (bypassing the seam) is still not caught; the seam is the convention, not an enforcement.
- **MINOR 4, test-after process deviation: kept as disclosed.**

---
## S6 — Production wiring, the apply API, and the apply-outcome event (OR-46's first E2E slice)

**Results (OR-96, 2026-10-05; lane `p010/s6` `bce79e5`, integrated here as `4ba71df4`).** A layout-only designer save is persisted outside config history. `sm_data` **V017** (`V017__designer_layout.sql`; V011 to V014 are plan 010's, V015 and V016 plan 011's, so V017 is the next free number): `designer_layout(tenant_id, instance_id, layout jsonb, updated_by, updated_at)` with no stamp trigger, no DELETE and a column-scoped UPDATE. `StageCommand` gained a nullable `layoutJson` (null leaves the record alone, so a copy-forward moves no boxes), `ConfigDraft` carries it, generations hold no layout at all, `Designer` gained a required `layout`, and `console.yaml` and `ENDPOINT-INVENTORY.md` are updated. Authorization is unchanged: `config.edit` to save, `config.view` to read.

*Lane counts.* dp-core 410 unit (1 skipped, pre-existing) and 201 IT; console-gateway 283 unit; `DesignerEndpointsIT` 18 (2 new), `InstanceEndpointsIT` 12, `ProductionAuthenticationIT` 4, arch-tests 43; `contracts/run-checks.sh --strict` passed. Red first: the new tests failed to compile against the missing API, then a real defect surfaced (the first CHECK let `{"c":[]}` through because lax mode unwraps arrays, so it became three strict/lax paths).

*Mutation proofs (each red on a named test, restored with `cmp`).* Skip the save, or save only on the changed-generation path; store layout in the generation; drop the `IS DISTINCT` guard; drop the stale-base check; leave the readiness expectation at 3; drop the strict non-object CHECK path or grant DELETE (`DesignerLayoutSchemaIT`, `PrivilegeTopologyIT`); in the gateway, pass no layout, return an empty layout, let `stageConfig` need only `config.view`, drop `Designer.layout` from `required`, or drop the contract text naming OR-96. Moving the layout save before the stale check did not go red (an equivalent mutant: the 409 rolls the transaction back either way).

**Flags (open for the owner).** (1) "Outside `config_generation`" is read strictly, which reverses S5's "the stored selection keeps layout" and changed those tests. (2) A layout-only save draws no `stateVersion` stamp, so a second browser does not see a moved box until it reloads. (3) `Designer.layout` is a new property beyond the descriptions named. (4) Boxes saved before V017 are not migrated and reset once. **Integration:** V017's header said V015 and V016 were plan 008's; corrected to plan 011's (`4b822305`; the file is unpublished).

### Task T6.1 — The transport: credential by reference, an in-memory SSH key, a bounded probe, the root preflight

**Wave:** W5. **Depends on:** T2.4, T5.1. **Parallel with:** T4.4 (owns `dp.apply.runtime.ssh` and the demo files
below).

**Files:** Modify `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/ssh/{SshDockerPlacement,SshjDockerTransport,SshDockerTransport,SshDockerRuntimeAdapter,SshDockerDiagnostics,SshDockerMetrics,SshDockerConfigEditor}.java`
(constructor plumbing only in the last three);
Create `…/runtime/ssh/{SshCredentialSource,HostProbe}.java`; Modify tests `…/ssh/{SshDockerRuntimeAdapterContractTest,SshDockerPlacementTest}.java`;
Create `…/ssh/SshjDockerTransportCredentialTest.java`;
Modify `backend/demo-launcher/src/main/java/com/solace/sm/demo/{DemoConfiguration,DemoInstanceService}.java` and
Create `…/demo/DemoKeyFileCredentialSource.java` (M5: the demo keeps its file-backed key through the new seam;
`VERIFY demo-launcher`).

Measured today: `withClient` opens a **new** `SSHClient` per call, loads the key **from a file path**
(`SshjDockerTransport.java:297-304`), the record carries that path (`SshDockerPlacement.java:16`,
`Path privateKeyFile`), and `demo-launcher` builds the record with a materialized key file
(`DemoInstanceService.java:193-197`). Changes:

1. **`SshDockerPlacement` replaces `Path privateKeyFile` with `UUID credentialRef`** and gains the target's
   `allowedConfigRoot`; its javadoc premise (`:12-13`, "never the browser") is rewritten to the truth after B2:
   *"container and paths may originate in a declare body, inside the target's envelope, which three enforcers
   check."* (Moved here from T2.5 so the record, its transport and the demo that builds it change in one commit, M5.)
2. `SshCredentialSource` (one method: `SecretMaterial privateKey(TenantId, UUID credentialRef)`) is injected;
   `withClient` authenticates from memory with the overload **M8** confirms, then zeroises. ⛔ No temporary key file.
   The demo's `DemoKeyFileCredentialSource` serves its vault-materialized key through the same seam.
3. A `HostProbe` operation — `probe(SshDockerPlacement, int port, String path, Duration timeout)` — builds the
   **fixed** command `curl --silent --show-error --max-time <n> --output - --write-out '\n%{http_code}'
   http://127.0.0.1:<port><path>` from an `int` port and a path matching `^/[A-Za-z0-9/_.-]{0,255}$`; nothing else
   reaches `session.exec` (`SM-D11a` **P**: no shell composition from data). It returns
   `ProbeResult(int exitCode, int httpStatus, byte[] body /* bounded */)`. ⚠️ **No `--fail` (MINOR 11):** with
   `--fail`, a `503` from a `DOWN` actuator and a refused connection would both exit non-zero, and a `DOWN` would be
   indistinguishable from a transport failure. **M19** confirms the exit codes and `--write-out` before this is
   written. It is used by the **health poller** (T7.2) and — owner ruling 2026-09-29
   (S0-10) — by the **`rolling-health-gated` gate** through the adapter's status read (T6.9); both are bounded reads,
   never a mutation. The existing `cacheStats`/`cacheSize`/`heapUsed` stay demo-only (T6.7's rule proves it).
4. **The root preflight (B2, third enforcer):** `verifyConfigDirectoryWritable` also canonicalizes the directory
   over SFTP (**M8**: `SFTPClient.canonicalize`) and refuses (`ssh.config.outside.root`) unless the canonical path
   equals the root or lies under `root + "/"` — so a symbolic link placed inside the root cannot redirect a write.

⛔ **Not changed in this task (M9): the adapter's `status()`.** The earlier draft rewired it to a port; measured, it
reads `docker inspect` health, not any port (`SshjDockerTransport.java:44-58`, `SshDockerRuntimeAdapter.java:102-111`),
and the hard-coded `8081` lives only in the metrics probes. **T6.9** (not this task) makes `status()`'s health
signal come from the placement's **package-declared** probe when one is declared — so `rolling-health-gated` gates
on it (owner ruling 2026-09-29 (S0-10)) — and keeps `docker inspect` for liveness and start time. Either way the
engine's gate stays **independent of the poller** (it never reads `placement_health`) — OR-39: "the apply engine's
health gate stays independent".

- [x] **Step 0: Fill M8** (in-memory `loadKeys`, session sharing for T7.1, `canonicalize` link resolution) **and
  M19** (the probe command's exit codes and `--write-out`).
- [x] **Step 1: Failing tests.** `theKeyIsNeverWrittenToDisk()` (a `SecurityManager`-free check: assert no file is
  created under `java.io.tmpdir` and that the credential source was closed), `aProbePathOutsideTheGrammarIsRefusedBeforeAnySession()`,
  `aCanonicalDirectoryOutsideTheRootIsRefusedBeforeAnyWrite()` (a fake SFTP canonicalizing to `/home/u/.ssh`),
  `statusStillReadsDockerInspectHealth()` (M9's pin), `theContractKitStillPassesOverTheNewConstructor()`,
  `aDownAnswerIsReportedAsItsHttpStatusNotAsATransportFailure()` (M19: a stub `503` gives `httpStatus = 503`, and a
  closed port gives a non-zero `exitCode` with no status).
- [x] **Steps 2–4:** fail → implement → `VERIFY dp-core`, `VERIFY demo-launcher`, `VERIFY arch-tests`.
- [x] **Step 5: Prove they fire.** Concatenate the path unvalidated → the grammar test red. Compare the
  un-canonicalized path to the root → the link test red. Revert each.
- [x] **Step 6: Commit.** `feat(dp-core): the SSH transport authenticates from the secret store by reference and refuses writes outside the target root`

**Results (T6.1, 2026-10-05; lane `p010/s6` `64a106a`, integrated here as `8b223944`).** `SshDockerPlacement` carries `credentialRef`, the tenant and the target's `allowedConfigRoot` in place of a key-file path; `SshCredentialSource` resolves the key in memory (sshj `loadKeys(String,String,PasswordFinder)`, M8) and closes it on every path, so no key file exists; `HostProbe` builds the one fixed `curl` command from an `int` port and a grammar-checked path (no `--fail`, M19) and returns exit code, HTTP status and a bounded body; the directory preflight compares the SFTP-canonicalized path with the root (`ssh.config.outside.root`); `demo-launcher` serves its vault key through the same seam (`DemoKeyFileCredentialSource`). Tests: `SshjDockerTransportCredentialTest`, `...ProbeTest`, `...RootTest`. M8 is filled in `docs/tasks/t7-health-measured-facts.md`, M19 (local half) in `docs/tasks/t6-9-measured-facts.md`; the demo-host repeat of M19 stays open for W9. The lane's own red/green and mutation record was not carried into a report; the integrated suite below is the evidence for this task.

### Task T6.2 — `ProductionApplyConfiguration`: a production adapter, renderer and executor, so the worker runs

**Wave:** W6. **Depends on:** T2.5, T2.6, T5.2, T6.1. **Parallel with:** T5.3.

**Files:** Create `backend/dp-core/src/main/java/com/solace/sm/dp/apply/production/{ProductionApplyConfiguration,ConfigGenerationRenderedConfigSource,YamlConfigRenderer,SecretStoreCredentialSource,SecretStoreHashKeySource}.java`;
Create `backend/dp-core/src/main/java/com/solace/sm/dp/inventory/TablePlacementCatalog.java` + `…/inventory/TablePlacementCatalogIT.java` (moved from T2.5);
Modify `…/dp/DataPlaneConfiguration.java` (one import); Modify `launcher/src/test/.../ApplyWorkerAbsenceIT.java`
(**inverted**, not deleted — it becomes `theProductionBootHasExactlyOneExecutorAndTheWorkerRuns`);
Modify `launcher/src/test/.../TwoSchemaTestDatabase.java` and the **seven** launcher ITs that `@Import` their own
executor configuration — `ApplyWorkerLifecycleIT`, `ApplyWorkerBoundedClaimIT`, `ApplyWorkerPoisonAndDrainIT`,
`GracefulShutdownWithinGraceLandsTerminalIT`, `NoNewClaimsAfterStopIT`, `SecondWorkerPoolRefusedBeforeSweepIT`,
`WorkerSingletonLockHeldOnUndrainedShutdownIT` (measured: each names `ApplyExecutor` and none is the absence IT) —
each gains the one property below (M4);
Create `…/production/{ConfigGenerationRenderedConfigSourceIT,YamlConfigRendererTest}.java`, `launcher/.../ProductionWorkerLiveIT.java`.

`TablePlacementCatalog implements SshDockerPlacementCatalog` (`SshDockerPlacementCatalog.java:7-8`) binds
**`(tenant, instance, placement)`** (the v2 paper's F20; plan 008's `find(TenantId, PlacementId)` was missing the
instance), refuses a placement whose owning instance is not the one named or that is retired, and builds each
`SshDockerPlacement` from `placement` + a **fresh** `target_connection` read (T2.1: mutable for rotation).

The beans: `SshDockerRuntimeAdapter(TablePlacementCatalog, SshjDockerTransport(SecretStoreCredentialSource), hasher)`,
`HmacConfigHasher` over `SecretStoreHashKeySource` (per-tenant `CONFIG_HASH_KEY` secrets, D-KEY §5),
`ConfigGenerationRenderedConfigSource`, and `ApplyExecutor` from the existing engine beans
(`ApplyEngineConfiguration.java:47-107`). With an `ApplyExecutor` bean present the worker and recovery become live
with **no code change** there (`ApplyWorkerConfiguration` javadoc: "A test configuration that DOES supply an
`ApplyExecutor` … makes both fully operational"). `RUNTIME_ADAPTER_IMPLEMENTATIONS` (`PlaneBoundaryTest.java:226-228`)
is unchanged — the class is the same one.

⚠️ **The production executor would clash with the seven worker ITs' own (M4, measured):** each declares its own
`RuntimeAdapter`, `RenderedConfigSource` and `ApplyExecutor` beans in a `@TestConfiguration`
(`ApplyWorkerLifecycleIT.java:60, 193-221`), and two `ApplyExecutor` beans make `ObjectProvider<ApplyExecutor>`
ambiguous. So `ProductionApplyConfiguration` is switched by one explicit property,
`sm.dp.apply.production.enabled` (default **true** — production needs nothing set), and those seven ITs set it
`false`: they keep testing the worker against their in-memory adapter, exactly as today. **M16** (ii) is measured
here, not assumed.

**Render** (`RenderedConfigSource.render(PlacementRef, generation)`, `RenderedConfigSource.java`): read the
generation's `rendered_entries` (tenant + instance + generation, bound), resolve each `secretRef` through
`SecretResolver` in memory, build the nested YAML under the package's `configPath` by `renderedPath`, serialize
with **sorted keys** and a fixed writer configuration, return `RenderedConfig.of(placement.tenant(), bytes, hasher)`. **No package read
on the worker path** — projection happened at stage (OR-34). Any exception propagates without a message the engine
would read (`RenderedConfigSource` javadoc: the engine classifies `render.failed` itself, `I6`).

- [ ] **Step 0: Fill M4** — as an **observation about the E2E fixture**, recorded for T6.6's evidence. ⛔ No code
  branches on it (Global Constraints: SM is generic): T6.5's ladder reports only what the journal proves
  (`persisted` vs `restart-observed`) for every MI alike, and the UI copy says "takes effect on restart" unless the
  journal has observed a restart. If M4 shows Cache hot-reloads, that is recorded, and a package declaration for it
  is **routed** (a later schema change), never a Cache branch here.
- [x] **Step 1: Failing tests.**

```java
@Test void renderIsByteIdenticalAcrossTwoFreshProcessesForTheSameGeneration() { }   // resume determinism
@Test void renderReadsOnlyTheGenerationRowAndTheSecretStore() { }                    // spies on both; nothing else is called
@Test void theSevenWorkerItsKeepTheirOwnExecutorWithProductionDisabled() { }         // M4/M16(ii): exactly one executor bean
@Test void theCatalogRefusesAPlacementOwnedByAnotherInstanceOrRetired() { }          // TablePlacementCatalogIT (from T2.5)
@Test void aSecretRefIsResolvedOnlyInMemoryAndNeverLogged() { }
@Test void aMissingSecretFailsTheRenderAndTheEngineLandsRenderFailed() { }
@Test void keysAreSortedSoAReorderedDraftRendersTheSameBytes() { }
@Test void theProductionBootHasExactlyOneExecutorAndTheWorkerRuns() { }              // the inverted absence IT
```

- [x] **Steps 2–4:** fail → implement → `VERIFY dp-core`, `VERIFY launcher`, `VERIFY arch-tests` (`CompositionBoundaryTest`
  green: the launcher still holds no `ApplyExecutor` or `RuntimeAdapter`).
(The earlier draft's `renderNeverReadsThePackage` spied a `cp-api` `SolutionCatalog`, which `dp-core` cannot see at
all — `dpNeverImportsCp` already makes a package read impossible, so the test was vacuous (m6). The replacement
asserts the positive: exactly two collaborators are read.)

- [x] **Step 5: Prove they fire.** Serialize with insertion order → the reorder test red. Log the resolved value in
  the failure path → the logging test red. Read the `placement` row in the renderer → the two-collaborator test red.
  Default `sm.dp.apply.production.enabled` to `false` → the inverted absence IT red. Drop the instance bind from the
  catalog lookup → its test red. Revert each.
- [x] **Step 6: Commit.** `feat(dp-core): production apply wiring — the durable worker runs against SSH-Docker in production`

**May claim:** the worker is live in production. **Not** yet that anything reaches it over HTTP (T6.3).

**Results (T6.2, 2026-10-05; lane `p010/s6` `d664083`, integrated here as `4873ed10`).** `ProductionApplyConfiguration` supplies the one `ApplyExecutor` over `SshDockerRuntimeAdapter`, a table-backed `TablePlacementCatalog` bound by (tenant, instance, placement) with a fresh `target_connection` read (T6.8 later adds the instance row, OR-71), a `SecretStoreCredentialSource`, and a `ConfigGenerationRenderedConfigSource` that rebuilds generation G from its `rendered_entries` row and the secret store alone (sorted keys, fixed YAML writer, secrets resolved in memory and closed). `sm.dp.apply.production.enabled=false` withdraws it for the ITs that bring their own executor (`TwoSchemaTestDatabase.provisionWithOwnExecutor`); `ApplyWorkerAbsenceIT` is inverted to prove the production boot has exactly one executor and a running worker, and `ProductionWorkerLiveIT` drives requests through the real chain to a classified outcome. **Step 0 (M4) stays open:** it is an observation about the E2E fixture, recorded for T6.6 on the demo host (W9). The lane's red/green and mutation record was not carried into a report.

### Task T6.3 — `submitApply`, `observeApply`, `stopInstance` served (plan 007 Tasks 4, 6, 6b)

**Wave:** W7. **Depends on:** T6.2, T4.4. **Parallel:** ⛔ alone.

**Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/apply/{ApplyService,SubmitApplyCommand,StopCommand,ApplyAcceptedView,ApplyObservationView,ApplyStepView,ApplyRefusedException,NotImplementedStrategyException}.java`;
Create `backend/dp-core/src/main/java/com/solace/sm/dp/apply/service/{ApplyServiceImpl,ApplyObservationReader,ApplyServiceConfiguration}.java`,
`…/apply/persistence/JdbcApplyObservationRepository.java`; Modify `DataPlaneConfiguration.java` (one import);
Create `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/{ApplyController,SubmitApplyRequest,StopRequest,ApplyAccepted,ApplyObservation,ApplyObservationList}.java`;
Create tests `console-gateway/.../ApplyControllerTest.java`, `launcher/.../{ApplyEndpointsIT,StopEndpointIT,ObserveBackfillIT}.java`; extend `SchemaMirrorLockTest`.

**Read plan 007 Task 4 (`007-…md:466-576`), Task 6 (`:994-1017`) and Task 6b (`:1063-1158`) in full** — their test
lists are this task's test lists, with four corrections this plan's rulings force:

1. `placementId` is **required** on submit (S0 point 10); it must be one of the instance's **active, live**
   placements (T2.5's bound lookup), else `404` — indistinguishable from an out-of-scope instance (§5.6 rule 4).
2. `generation` must exist in `config_generation` for the instance (T5.2), else `422 VALIDATION_FAILED`. The check
   runs **inside** admission, after the `apply_scope` lock and under the `instance_state` row lock (Global
   Constraints' order), so a concurrent stage's retention (T5.2, which holds the same row lock) cannot delete the
   generation between the check and the `apply_request` insert that then protects it.
3. `restart-all`, `rolling-no-validation`, `scheduled` → `501 NOT_IMPLEMENTED`; unknown strings → `400`.
   **`rolling-health-gated` is admitted** (owner ruling 2026-09-29 (S0-10), `OR-53` — this supersedes the
   plan's earlier "→ `501`"), requires `instance.restart` as well as `config.apply` (`x-sm-permission-also`), and
   runs through the engine's existing gate. ⚠️ Until T6.9 supplies a probe-backed health signal, the SSH-Docker
   adapter reports `docker inspect`'s signal, which is `NOT_DECLARED` for an image without a `HEALTHCHECK` — and the
   engine then refuses before writing a byte (`strategy.health.signal.absent` → `FAILED_NO_EFFECT`,
   `ApplyExecutor.java:550-557`). That is the measured fail-closed path, not a temporary `501`, and it is what this
   task tests. (An image that does have a Docker `HEALTHCHECK` would gate on it from W7 until T6.9 lands in W8a;
   T6.3's test uses an image without one, and T6.3 reports no `gateProved`. The window is between two unmerged-to-
   release waves and is closed by T6.9's Q-G1 rule.) ⛔ No branch on the solution anywhere in the admission path.
4. The stop observation is **REST, declared** (owner ruling 2026-09-29 (T0.2)): `observeApply?applyId=` answers a
   stop's own request, which the browser polls about every 2 s until terminal (T0.1 point 4).

`observeApply` is assembled **from the journal only** — `apply_request` + `apply_step_intent` + `apply_attempt` —
never from an adapter (plan 007 Task 6 step 3). `sinceStateVersion` returns rows with `state_version > since`,
ordered ascending, bounded by `limit`. The submission path calls `ApplyWorker.nudge()` after the admission commits
(plan 007 Results: "`nudge()` exists … nothing calls it in this slice") — latency only, never correctness.

- [x] **Step 1: Failing tests** — plan 007 Task 4's thirteen, Task 6's six, Task 6b's twelve, plus:

```java
@Test void anAbsentPlacementIdIs400AndAForeignOneIs404() { }
@Test void aGenerationThatWasNeverStagedIs422() { }
@Test void rollingHealthGatedIsAdmittedRequiresInstanceRestartAndFailsClosedWithNoEffectWhenNoSignalIsDeclared() { }
@Test void restartAllRollingNoValidationAndScheduledAre501() { }
@Test void observeByApplyIdAnswersAStopsOwnRequest() { }                  // the declared ~2 s stop poll (T0.2)
@Test void backfillNeverSkipsARowCommittedAfterAHigherStamp() {
    // Review Focus 1, end to end: two requests on one instance, the one stamped first commits last; a client that
    // observed the higher stamp and then backfills from it still receives the other's terminal transition, because
    // T2.1's commit-order rule makes that interleaving impossible — assert it cannot be produced, with a latch.
}
@Test void observeAnswersWhenTheHostIsUnreachable() { }                   // journal-only
@Test void applyObserveAndStopAreReachableInTheProductionBoot() { }
```

- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY dp-core`, `VERIFY console-gateway`, `VERIFY launcher`, `VERIFY arch-tests`.**
- [x] **Step 5: Prove they fire** — plan 007 Tasks 4/6/6b's mutations, plus: serve the adapter's answer in observe →
  the unreachable-host test red; answer `rolling-health-gated` with `501` → the admission test red; skip the
  `instance.restart` check for it → the same test red; accept `restart-all` → the `501` test red. Revert.
- [x] **Step 6: Commit.** `feat: submitApply, observeApply, stopInstance served — 202 before any adapter call, the journal is the only truth`

**Results (T6.3, 2026-10-05; lane `p010/s6` `5e210ac`, integrated here as `ec06f90c`).** `submitApply`, `observeApply` and `stopInstance` are served (`ApplyController`, `ApplyOrchestrator`, `ApplyServiceImpl`). Admission takes `GenerationBinding.MUST_BE_STAGED`, checked under the `instance_state` lock after the `apply_scope` lock; the one-argument `admit` stays `UNCHECKED` for the engine suites. A `Location` header was added to the 202 (not declared in `console.yaml`), and a `NOT_IMPLEMENTED` title in `ProblemBody`.

*Lane counts.* dp-core 412 unit (1 skipped, pre-existing) and 23 IT (`AdmissionGenerationBindingIT` 5, `ApplyServiceIT` 18); console-gateway 315 (`ApplyControllerTest` 27, 5 new `SchemaMirrorLockTest` cases); launcher 5 unit and 25 IT (`ApplyEndpointsIT` 10, `StopEndpointIT` 10, `ObserveBackfillIT` 5); arch-tests 43. Every new test failed to compile against the missing classes first.

*Mutation proofs (each red, restored byte-for-byte).* Skip the `instance.restart` check; rolling answers 501; accept `restart-all` (gateway and service); drop the placement-membership check (gateway and service); drop the observe bounds; stop with the wrong organization; put the outcome in the 202 body; strategy-vocabulary drift; generation check without the instance lock (red only after the IT was fixed: the first-ever scope insert had been queuing admission earlier, so the test proved nothing about the lock); `UNCHECKED` binding at the service; drop the worker nudge; observe in descending order; observe not bound to the instance; observe asks the adapter; remove the instance lock from the stamp function (in-test); add a `RequestState` the contract lacks.

**Flags (open for the owner).** (1) A stop's audit record is its append-only journal row; no separate audit log exists (open item 24), so plan 007's "audit line, HIGH" is not built. (2) Request-level classifiers such as `stop.no.known.placement` are not persisted: that stop lands `STOP_FAILED` with empty steps, so the contract's example `reason` cannot appear (a column would fix it). (3) `reconcileReason` and `gateProved` are in the wire records; T6.8 and T6.9 now fill them. (4) `ENDPOINT-INVENTORY` served rows are left to T12.1.

### Task T6.4 — `instance.apply-outcome`, `instance.rollout`, `instance.config-state` after commit (OR-4, OR-46, OR-37)

**Wave:** W8. **Depends on:** T0.2, T6.3, T3.3 (production mesh wiring). **Parallel with:** T6.7 only (T6.9 and
T6.5 edit `ApplyExecutor`/`OutcomeService` after this task merges — W8a).

**Files:** Create `backend/dp-core/src/main/java/com/solace/sm/dp/apply/events/{ApplyEventEmitter,ApplyEventsConfiguration}.java`;
Modify `…/apply/engine/{OutcomeService,StepIntentService,ApplyExecutor}.java` (the call sites at the journal's own
transitions — never from a controller, never from an adapter); Modify `DataPlaneConfiguration.java` (one import);
Create `…/apply/events/{ApplyEventEmitterTest,ApplyOutcomePublishIT}.java`.

**This is plan 007 Task 5 (`007-…md:801-851`), executed, plus the apply-outcome event it could not implement.**
Its eleven tests apply; add:

- **Enum parity (slice review, 2026-10-05):** a `dp-core` test asserting that `NotifyEventShapes`' `instance.apply-outcome` state enum equals `{s in RequestState : s.validFor(APPLY) && (s.isTerminal() || s == APPLY_INDETERMINATE)}` — `mesh-events` cannot import `dp-core`, so without it a new terminal state would go unnoticed. Plant a state in one set and see it red.

```java
@Test void aTerminalLandingPublishesExactlyOneApplyOutcomeAfterCommit() { }   // operation = apply only
@Test void aStopLandingPublishesNoApplyOutcome() { }                           // M10: incl. an instance-wide stop
@Test void applyOutcomeCarriesKindPlacementAndARequiredPlacementId() { }      // OR-4's const
@Test void applyOutcomeCarriesNoErrorDetail() { }                              // OR-4: no message, no ErrorClass
@Test void applyOutcomeStateVersionIsTheRequestsOwnStamp() { }                 // apply_request.state_version (T2.2)
@Test void applyIndeterminatePublishesAnOutcomeToo() { }                       // owner ruling 2026-09-29 (T0.2)
@Test void configStateFiresOnlyWhenTheInstancesLowestRungMoves() { }           // OR-37: single live placement in v1
@Test void aRolledBackTransactionPublishesNothing() { }
@Test void aBrokerOutagePublishesNothingAndTheApplyStillCompletes() { }        // drop + count; plan 007 Task 9 scenario 1
@Test void applyOutcomeIsObservedOnALocalBrokerWithTheDerivedTopic() { }       // ApplyOutcomePublishIT, broker.host-gated
```

⚠️ **The drop-counter *export*** (plan 007 Task 5 step 4a) needs a metrics stack nobody has chosen (plan 007
Measured facts row 3), and the launcher refuses management endpoint mappings (`ENDPOINT-INVENTORY.md` deployment
note). **Routed, not built**: the counter stays an in-process `AtomicLong`; this is stated in Results, not hidden.

- [x] **Steps 2–4:** fail → implement → `VERIFY dp-core` (broker IT skipped with its reason when `broker.host` is
  unset — run it once with a local broker and record the command), `VERIFY arch-tests` (`onlyMeshbridgeTouchesJcsmp`,
  `eventsNeverImportDp`, publisher inventory unchanged).
- [x] **Step 5: Prove they fire.** Publish before commit → red. Emit `entity.kind = "instance"` → the shape validator
  throws and its test red. Put the `ErrorClass` token in the payload → the negative-shaped test red. Revert.
- [x] **Step 6: Commit.** `feat(dp-core): the apply-outcome, rollout and config-state notices, after commit (OR-4, OR-37, OR-46)`

**Results (T6.4, 2026-10-05; lane `p010/s6` `b735abd`, integrated here as `177f19b4`).** `instance.apply-outcome` and `instance.rollout` are emitted after commit through `ApplyEventEmitter`, wired via `ApplyEventsConfiguration`; `AdmissionService` also announces the superseded elder (outside the plan's file list); `StepIntentService` needed no change. The drop counter stays an in-process `AtomicLong` (routed, not built).

*Lane counts.* `ApplyEventEmitterTest` 23 and `DataPlaneConfigurationTest` 3; dp-core ITs 98 including the live-broker `ApplyOutcomePublishIT` (skips with its reason without `-Dbroker.host`; run once against the local `sm-spike-broker`, taken down afterwards) and `ApplyEventsLandingIT` 9; launcher ITs 34 including `ApplyEventsProductionBootIT`; arch-tests 43; `contracts/run-checks.sh --strict` green. Red seen: the unit test against a stub (23 errors); `ApplyEventsLandingIT` under the unwired-engine mutation (6 of 9).

*Mutation proofs (each red, restored with `cmp`).* Publish before commit; `entity.kind = instance`; an `error` key in the payload; stamp minus 1; stops not excluded; config-state fired unconditionally; a planted `RUNNING` in the `mesh-events` enum; an ungated apply claiming restart-observed; no supersede notice; no resolution notice; a notice defect rethrown into a landing; an instance-wide apply published; engine wired to the silent emitter.

**Integration fix.** T6.5 made the landing also fire `instance.config-state` when the ladder's rung moves, so `ApplyEventsProductionBootIT`'s raw drop counter read 2 for one manual landing and its "exactly one" assertion failed at the T6.5 base commit (reported by T6.8). Fixed here (`0fcb2ecd`): the IT captures the disabled mesh's per-drop DEBUG line and asserts exactly one apply-outcome for the `applyId`, exactly one config-state, and nothing else. Failing run first: `expected: <1> but was: <2>`.

**Flags (open for the owner).** (1) Rollout steps move no stamped row, so rollout notices repeat the request's stamp; a console already holding that stamp discards them under the "stamp at or below the held one" rule. The terminal outcome is always strictly newer. Needs an owner ruling. (2) `restart-observed` is published only after a gate pass; ungated strategies publish `restart-requested` only. (3) Engine call sites log notice-building defects and continue, so such a bug cannot strand a landing; the emitter itself still throws. (4) An instance-wide apply or stop emits nothing (OR-4/M10). (5) `HealthEventEmitter` (T7.2) and `ApplyEventEmitter` coexist; both are built over the same after-commit publisher. **Fold-in for later:** T7.2 asked for `ApplyEventEmitter.onHealthTransition`; the integration keeps the two classes separate and working, and folding `HealthEventEmitter` into `ApplyEventEmitter` is a follow-up.

**OR-112 (1) note (2026-10-07, lane `p/or112a`; the ruling wins over the text above).** Flag (2) is closed in the direction the owner ruled: the ladder's `restart-observed` is no longer reachable only after a gate pass. `ApplyEventEmitter.onConfigStateReached` now also fires from outside a landing, when the health poller records a container start that moves the instance's lowest rung (`StartObserver`, see T6.5's note); it is still the ladder's one writer (`LadderRecorder.settle`) that decides, and it fires only on a movement, so `instance.config-state` carries each rung exactly once whichever path moved it (`ConfigLadderIT` `anUngatedApplyReachesRestartObservedWhen...AnnouncesItOnce`: persisted, then restart-observed, nothing on the repeat; `aLaterApply...EachExactlyOnce`: the fall and the next rise once each). `instance.rollout`'s `restart-observed` step is untouched: it still means a gate passed. A start observed on an ungated apply is a **ladder** fact, not a rollout step.

### Task T6.5 — The ladder's `persisted` and `restart-observed` rungs, from the journal

**Wave:** W8a, after T6.9 in the same agent. **Depends on:** T6.3, T6.4 (it edits `OutcomeService` after T6.4 did),
**T6.9** (the post-restart read-back this task's `restart-observed` consumes). **Parallel:** ⛔ none inside W8a (owns
`dp.config.ConfigLadderReader` + the `reached_rung` update point).

**Files:** Modify `backend/dp-core/src/main/java/com/solace/sm/dp/config/ConfigLadderReader.java`,
`…/apply/engine/OutcomeService.java` (**one** call: update `instance_state.reached_rung` in the landing
transaction; T6.4 edited the same class and has merged);
Create `…/config/ConfigLadderIT.java`.

⚠️ Until this task, the `rendered` rung's `configHash` is the generation's CONTENT digest (the same value as `desired`; S5 review MINOR 1). This task replaces it with the keyed hash of the rendered file bytes (OR-32), so the meaning changes here and nothing may compare the two.

`persisted` = the placement's latest request whose `MATERIALIZE` intent's bound hash equals the read-back hash
(`STAGED`/`APPLIED`/`INDETERMINATE_RESOLVED`-applied); `restart-observed` = a `restart-observed` step on a
restarting strategy **or** (after T7.2) a container start observed after the materialization; `effective-verified`
= `present: false` with the reason "effective-unverified for this package" — `SM-D22` (**P**) makes
effective-verified a per-package property, and `contracts/solution-package.schema.json` defines **no**
verification-signal declaration at all (measured 2026-09-29: no `$defs` entry for one), so no package can declare
one and the rung is **declared absent** for every package — never inferred, and never branched on the solution. `restart-observed` after a `rolling-health-gated` apply
additionally requires T6.9's post-restart read-back to match (owner ruling 2026-09-29 (S0-10)).

- [x] **Step 1: Failing tests.** `aManualApplyReachesPersistedAndStopsThere()`, `theInstanceRungIsTheLowestAcrossLivePlacements()`,
  `effectiveVerifiedIsDeclaredAbsentWithItsReason()`, `everyRungCarriesAKeyedHash()`, `theLadderNoLongerReportsTheS5DegradedNotice()`.
- [x] **Steps 2–5:** fail → implement → `VERIFY dp-core`, `VERIFY launcher` → mutation: report `persisted` from the
  intent alone without the read-back → red.
- [x] **Step 6: Commit.** `feat(dp-core): the config ladder's persisted and restart-observed rungs, from the journal (OR-37)`

**Results (T6.5, 2026-10-05; lane `p010/s6` `a9d6a6a`, integrated here as `8c0e9759`).** `ConfigLadder` derives `persisted` (a succeeded `MATERIALIZE` attempt whose read-back hash equals the bound hash) and `restart-observed` (also a gated, `APPLIED` request with a succeeded `LIFECYCLE` and a matching `VERIFY`, T6.9) from the journal; `effective-verified` is absent with the reason "effective-unverified for this package". `LadderRecorder`, called from the `OutcomeService` landing, writes `reached_rung` only when the rung moved and fires `instance.config-state` after commit. The S5 degraded notice is gone. `rendered` keeps the generation's content digest, as ruled (the plan's file-bytes hash would need secrets resolved, and a read must not resolve them; the file-bytes hash starts at `persisted`).

*Lane counts.* dp-core unit 480 (1 skipped, the broker IT); dp-core ITs 101 across `ConfigLadderIT` (13) and seven neighbours, 1 skipped (the broker IT); launcher `DesignerEndpointsIT` 18 and `RollingHealthGatedIT` 1; arch-tests 56. Red: `ConfigLadderTest`, `LadderRecorderTest` and `ObservedStateServiceImplTest` failed to compile against stashed main code.

*Mutation proofs (each red, restored with `cmp`).* Persisted from the intent alone; VERIFY not required; ungated strategies counted; an older generation counted; highest rung instead of lowest; the landing not settling the ladder; writing when unchanged. **Not mutation-covered:** the `SETTLED` state filter in the journal query.

**Integration fix.** The lane left `ProductionAuthenticationIT`'s expected route set without the three apply operations T6.3 serves (red before T6.5). Fixed here (`047d6657`), failing run first: the actual set carried `submitApply`, `observeApply` and `stopInstance`.

**Flags (open for the owner).** (1) `reached_rung` is only the event's "previous" memory: the ladder never reads it, so a stage after an apply drops the ladder back to `rendered` without a write. (2) Ungated strategies stay at `persisted` because nothing observes the restart; the container-start route waits for T7.2. (3) `ConfigLadderView` has no per-placement `placements[]`, and `console.yaml` and the inventory are untouched (T0.1, T12.1). (4) `CONFIG_DIVERGED` is not counted as persisted, following the plan's literal list of states. (5) `ConfigLadderIT` sits in `apply/persistence`, not `config`, because the engine harness is package-private.

**OR-112 (1) note (2026-10-07, lane `p/or112a`; built on `integration/dp-remote-demo` `391bc1c1`; the ruling wins over the text above).** Flag (2) ("ungated strategies stay at `persisted` because nothing observes the restart; the container-start route waits for T7.2") is **built**, and the sentence in this task's text that `restart-observed` needs a gated apply no longer holds. Three routes reach `restart-observed`, each only from `persisted`: (a) the gated journal rule above, unchanged; (b) **a gated apply confirmed by verify-then-retry**: `INDETERMINATE_RESOLVED`/`applied` where, per replica, the reconciler's own evidence says restarted onto the bound hash **and** the journal holds the succeeded `LIFECYCLE` attempt that `recordGateProof` writes only on a passed gate; (c) **an ungated apply** (`manual`, `rolling-no-validation`, `restart-all`, `scheduled`): a container start the data plane observed later than the end of every `MATERIALIZE` write of the request.
- *Where a start is observed.* The health poller's round (`HealthPoller` -> the new `StartObserver`): for a placement whose newest generation is `persisted` under an ungated strategy with no start since its write (`ConfigLadder.awaitsStart`, from the journal alone) it runs the collector's fixed `docker inspect` (`HostMetricProbe.inspect`, new `parseInspect`) over the pooled SSH session, whatever the instance's acquisition mode, and records the start in the new `sm_data.placement_start_observation` (**V018**, the lane's placeholder; one row per placement, the LATEST start, written only when it changes; `started_at` is the host's clock). No new tenant list: the observer rides the poller's own enumeration (`MonitoringBoundaryTest` green). The same transaction takes the instance's state row `FOR UPDATE` (as a landing does) and settles the ladder through `LadderRecorder`, so `reached_rung` and `instance.config-state` move together and once.
- *Both readers follow.* `ConfigLadder` (evidence gains `PlacementEvidence.startedAt` and `StepEvidence.writeBaseline` (first built as `endedAt`, replaced in the fix round), read by `JdbcLadderRepository`) and `JdbcRestartObservedGenerationRepository` (the SQL restated per generation for the three routes, NULL-safe: every predicate under a `NOT` is an `EXISTS` or a `coalesce`) read the one table; `RestartObservedGenerationIT` asserts the SQL answer and the Java ladder agree in every case.
- *(b): which option, and why.* Fable APPLY-03's text is not in this tree (the plan names the review as its source), so its two options are chosen here by content, not by label. Chosen: **read-side only**: the ladder and the SQL accept the reconciler's confirmed resolution when its evidence and its gate proof both hold; nothing the reconciler writes changes. The alternative, having `recordGateProof` also journal a `VERIFY` attempt so the unchanged journal rule fires, was not taken: it changes the reconciler's write path and `ReconciliationSchemaIT`'s expectations, and makes `observeApply` show a `VERIFY` step the host never ran after a restart (the reconciler read the file in the same observation as the gate, which is a confirmation, not a post-restart verify). An operator's own resolution with no gate proof in the journal stays at `persisted` (`anOperatorsOwnResolutionOfAGatedApplyWithNoGateProofIsNotObserved`, with a start after the write as well: a start is the ungated route and never stands in for a gated strategy's gate).
- *Two clocks, stated; **corrected in the final-wave fix round (finding `OR112-1-TWO-CLOCK`)**.* The first build compared a start (the Docker host's clock) with a write's end (the data plane's), which is the comparison the ruling's words suggest, and claimed no same-clock alternative existed without journalling a host-clock baseline at the write. **That claim was wrong, and so was the comparison:** the engine's own `MATERIALIZE` read-back (`adapter.status`) already returns the host's start baseline, so it is kept with the write (`apply_attempt.write_baseline`, **V020**, written in the same statement that closes the succeeded attempt; only `MATERIALIZE` may carry one) and `restart-observed` now means "the placement's observed start is later than the start the host reported at the write", both the host's clock (the same discipline as `HealthGate`/`restartedSince`, M-2). A host 60 s or an hour ahead cannot show a restart nobody did; one behind cannot hide one (`aHostClockAheadOfTheDataPlaneDoesNotPromoteAGenerationNoOneRestarted`, `aHostClockBehindTheDataPlaneStillObservesTheRestartOfEveryUngatedStrategy`, ±60 s and ±1 h, manual and `restart-all`/`rolling-no-validation`). The only tolerance is 1 ms (`ConfigLadder.START_TOLERANCE`, shared by the SQL), absorbing Docker's nanoseconds against the database's microseconds. No NTP obligation remains (`docs/runbooks/install-prerequisites.md` is corrected). A restart BEFORE the write never counts (`aStartObservedBeforeTheWriteDoesNotCount`).
- *A newer write the same start loaded (finding `OR112-1-ELDER-CREDIT`).* Route (c) credited an older persisted generation with a start that came after a NEWER write which itself never persisted (`CONFIG_DIVERGED`, `APPLY_INDETERMINATE`): the host then ran the newer, divergent file, yet the metrics active list switched to the elder's. `JdbcRestartObservedGenerationRepository` now also requires that no LATER apply of the placement, of another generation, whose state does not prove it left this file in place (`FAILED_NO_EFFECT` wrote nothing; `FAILED_ROLLED_BACK` restored and verified the pre-image), has a `MATERIALIZE` write the same start post-dates (the same baseline rule), or, for a write with no baseline, one that began before the start was first seen (`observed_at`: data plane clock against data plane clock, so still no skew). Pinned in `aLaterWriteThatNeverReachedPersistedShadowsTheElderGenerationAStartPostDates` and, through the poller with a third manual generation, `aPollerThatRecordsTheStartAFailedGatedWriteLoadedDoesNotCreditTheGenerationBeforeIt`; `aLaterWriteTheStartPrecedesLeavesTheElderGenerationObserved` keeps the legitimate case (a later write made while that start was still the one running). The Java ladder reads only the newest settled request, so it needs no such clause.
- *Left at `persisted` on purpose.* A request whose `MATERIALIZE` attempt left no write baseline (it never ended, failed or timed out, was resolved after a time-out, or its container had never started) gives no start to compare against, and `ConfigLadder.awaitsStart` stops asking the host about it (finding `OR112-1-ENDLESS-AWAIT`: before, the poller inspected such a placement every round for ever); a start observation also waits out its target's health backoff (finding `OR112-1-BACKOFF-BYPASS`, `aTargetInBackoffIsNotAskedForAContainerStartEitherUntilItsBackoffEnds`); a gated apply that was confirmed without a proved gate; a generation applied while `sm.dp.health.enabled=false` (nothing observes a start then). A `SUPERSEDED` elder (an ungated `STAGED` overtaken by a later request) counts for the metrics active list, not the ladder: a start between its write and its successor's is its file loaded, which is what "the generation in force" means.
- *Tests.* `ConfigLadderTest` 24 (11 new; 6 red against a stub that returned `false`/left the old rule), `RestartObservedGenerationIT` 17 (11 new; 4 red against the old SQL: the ungated, newer-write, gated-confirmed and two-placement cases), `ConfigLadderIT` 19 (6 new: observation once, before-the-write, failed reads, gated never awaited, the fall and the next rise, the instance lock), `StartObservationIT` 6 new (a poller round over a manual apply with no probe, the repeat asks, a staged-only placement never inspected, `direct-http` too, the push plan, the collector's active list). The three migration-count ITs (`SmDataBootstrapIT`, `SmMetricsMigratorIT`, `ApplyPersistenceConfigurationIT`) went 8 red on V018 (17 -> 18 migrations, 22 -> 23 tables) and were updated. **The observer, the poller wiring and the instance lock were written after the code**, so their proof is mutation, not a red-first run: sixteen mutations, each red and restored with `cp` + `cmp` (the time comparison dropped in Java and in SQL; a start rescuing a gated apply, in Java and in SQL; the confirmed route without the gate proof in Java and in SQL, and without the restarted flag in Java; the persisted rule without the read-back; `SUPERSEDED` removed; the ladder ignoring the table; the observer not settling; the observation always "changed"; the poller never observing, and observing every placement; `awaitsStart` for a gated apply; the instance lock removed). **Not mutation-covered:** the SQL route (b)'s `restarted == true` filter (the Java ladder's is: no IT can make the reconciler confirm a replica it did not restart), and the `V018` grants (exercised by every write, not isolated).

### Task T6.6 — The first end-to-end walkthrough, and the backend resilience scenarios

**Wave:** W9. **Depends on:** T6.3, T6.4, T6.5, **T6.9** (the gated strategy), **T6.8** (the reconciler — the ruled behaviour for an apply cut off after its write must be what the gate exercises). **Parallel:** ⛔ alone — **the OR-46 gate**.

**Files:** Create `backend/launcher/src/test/java/com/solace/sm/launcher/{SkeletonWalkthroughIT,MeshResilienceIT}.java`
(gated with `@EnabledIfSystemProperty`, as plan 005's `CacheApplyWalkthroughIT` is). ⚠️ **Measured: no SSH-Docker
test harness exists.** `CacheApplyWalkthroughIT` drives the **local** `DockerAdapter` (`CacheApplyWalkthroughIT.java:28-31`)
and needs `solace-cache-engine:local` plus `backend/demo/docker-compose.yml`, which carries no SSH daemon; the only
real SSH-Docker target is the demo's own host (`DemoInstanceService` builds its `SshDockerPlacement`). So the
walkthrough takes the target from system properties (`sm.walkthrough.ssh.host`, `.port`, `.user`,
`.fingerprint`, `.key-file`, `.container`) naming an **operator-provided** SSH-reachable Docker host running Cache;
⛔ no sshd-in-a-container harness is invented here. Without those properties the IT is **skipped with its reason** —
and then **the OR-46 claim is not made**; Create `docs/plans/backend/evidence/010-first-e2e-walkthrough.md`.

Two actors, as plan 007 Task 11 requires (`007-…md:1339-1350`), plus the operator commands: the **installing
operator** runs `EntitlementMain` (owner ruling 2026-09-29 (Q-E)) and `TargetConnectionMain`; the **bootstrap admin** loads the Cache package
over HTTP and runs `TargetRegistrationMain` with the target **owned at the walkthrough environment `[org, unit, env]`**
(S0 point 22, ruled — owner ruling 2026-09-29 (S0-22): an organization-owned target would be unusable by an environment-bound Operator) and an envelope
(`allowedConfigRoot`, `allowedContainerPrefix`) that contains the Cache container's directory and name;
**`demo-operator`**, bound `Operator` at `[org, unit, env]`, lists solutions and targets (the reach classification,
S0 point 24), declares one instance, stages a secret-free change, applies with `manual`, and observes to `STAGED`
by REST — while `instance.apply-outcome` for that `applyId` is received on the **local broker** (`sm.mesh.enabled=true`,
T3.3). Then the negatives: the sibling environment's instance is invisible over REST; a declare naming a directory
outside the envelope is `422` and writes nothing (B2, against the real host); live notify for the browser is
**skipped with its reason recorded** (open items 32/33/35).

⚠️ **This is the only test in the plan that drives the production `SshjDockerTransport`** against a real host, so it
is also the end-to-end proof of B1's fix: the observed hash in `apply_attempt.observed_hash` for the walkthrough's
apply must be spelt `hmacsha256v<n>:` and equal the intent's `config_hash` — asserted, and recorded in the evidence
file. (T5.1's command test is the SSH-free guard that stays green in every run; this is the live one.)

Backend resilience (plan 007 Task 9, `007-…md:1244-1267`): scenarios **1–3** (broker down before / mid-apply /
one dropped notice) and **11** (kill mid-apply, restart, the startup sweep lands it — **against the real SSH-Docker
adapter**, closing plan 005's third routed watch item), plus **R1 — verify-then-retry on the real host** (owner
ruling 2026-09-29 (verify-then-retry)): the walkthrough apply's connection to the host is lost after its write
lands, so the request goes `APPLY_INDETERMINATE`. The connection is then restored, and the test asserts that the
reconciler (T6.8) inspects the host, finds the intended file (keyed hash) and, under `manual`, **confirms with no
second host write**: the SFTP write count across the reconciliation is unchanged, recorded in the evidence file.
**The fault-injection mechanism (MINOR 16), named so nothing is invented at execution:** a **test-scope**
`BeanPostProcessor` in `SkeletonWalkthroughIT`'s own `@TestConfiguration` wraps the production `RuntimeAdapter` bean
in a decorator. For one switchable window it makes `status(...)` throw `RuntimeAdapterException` with a
transport classifier, as a dropped SSH session does. The window opens when `materializeConfig` returns for the
walkthrough's `applyId` and closes before the reconciler's first due inspection. The real SSH-Docker adapter
performs every real write; only the observation after the write is lost. This is an in-process decorator, not an
sshd harness, and T6.7's rules analyse production classes only. ⚠️ The engine's bounded retry may carry that
request into `BE-D22`'s exhaustion path instead of `APPLY_INDETERMINATE`
(`ApplyExecutor.java:594-618`: a `status` failure after the write is retried, and exhaustion routes to `BE-D22`).
If it does, the observed landing is recorded, and **R1 is skipped with that reason** rather than forced. The
walkthrough also runs one `rolling-health-gated` apply against the real host and records the `gateProved` value
the API reported (`liveness` for Cache, T0.1a). T6.9 has merged by W9 (W8a), so this line is not optional.

- [x] **Step 1: Write the walkthrough and the five scenarios (1–3, 11, R1) as failing tests.** **Step 2: Run; fail.** **Step 3:
  Make them pass** (fix only what they expose; a design contradiction stops the task — record it).
- [ ] **Step 4: Run green twice** (not order-dependent). *(Wave-2 integrator note, 2026-10-06: the rehearsals ran twice in different class order in the preparation lane; the live ITs ran green once, run 3, so this step is NOT ticked.)*
- [x] **Step 5: Evidence file** — exact commands, the observed topic and envelope (ids only), counts, every skip
  with its reason, Docker state before and after. Clean up only this worktree's containers.
- [x] **Step 6: Commit.** `test: the first dp-remote E2E slice — declare, stage, apply, observe, apply-outcome on a local broker (OR-46)`

**May claim (the first E2E slice):** declare → stage → apply → observe on one SSH-Docker Cache instance, REST
authoritative, `instance.apply-outcome` emitted after commit and received on a local broker. **Must not claim:**
browser live notify, plan 007's event-driven skeleton complete, B/D5's skeleton, multi-placement.

**Results (T6.6 PREPARATION, 2026-10-06; lane `p010/t66`). THE LIVE RUN IS PENDING AND THE OR-46 CLAIM IS NOT MADE.** The lane had no host and looked for none. It wrote
`SkeletonWalkthroughIT` and `MeshResilienceIT` (`@EnabledIfSystemProperty` on `sm.walkthrough.ssh.host`), the run script
`backend/launcher/scripts/run-e2e-walkthrough.sh`, and the evidence template
`docs/plans/backend/evidence/010-first-e2e-walkthrough.md`, and exercised every part that does not need the host.
*The SM mesh events go to a LOCAL Solace broker started by the test from the local image `solace-pubsub-enterprise:10.25.0.177`
(`LocalMeshBroker`: Testcontainers, fixed ports so a stopped broker comes back to the same publisher, SEMP-provisioned VPN and
two identities with per-run random credentials, never pulled).* The scenario bodies are written once (`AbstractSkeletonWalkthrough`,
`AbstractMeshResilience`) and run twice: live (gated) and as **rehearsals** (`SkeletonWalkthroughRehearsalIT`,
`MeshResilienceRehearsalIT`, always on, skipped with their reason when the image is absent) against a certified in-memory host behind
the same decorator, with the REAL renderer, keyed hasher, secret store, journal, worker, reconciler, emitter, REST and broker. The
rehearsals support **no claim about the SSH-Docker adapter or a Cache**.

*Skipped without the properties, with the reason (measured).* `-Dit.test=SkeletonWalkthroughIT,MeshResilienceIT`: 9 + 7 tests
**skipped**, reason `System property [sm.walkthrough.ssh.host] does not exist ==> plan 010 T6.6 needs an operator-provided SSH-Docker
host ... without it the OR-46 claim is not made`.

*Red first, then green.* `WalkthroughTargetTest` 10 tests, 10 red (6 failures, 4 errors) against a throwing stub, then green (11
with the rehearsal case); `FaultInjectingRuntimeAdapterTest` 8/8 red, then green; `LocalMeshBrokerIT` red as 10 unresolved symbols, then
3/3 green (first run 129 s: start, SEMP provisioning, publish and receive on the derived topic with `EventJson` bytes, stop and start on
the same ports, a SEMP disconnect of only the publisher); `SkeletonWalkthroughRehearsalIT` first run 1 of 9 red (the replica label
differs between the in-memory host and the adapter), then green; `MeshResilienceRehearsalIT` first runs were not red but HUNG (see
FLAG T6.6-1) and, once restructured, red on scenario 3 (the first notice offered after admitting an apply is the *previous* request's
`SUPERSEDED` outcome, not the staged rollout) before green. `EvidenceLogTest` (3) and `RunE2eWalkthroughScriptTest` (6, runs the script
with `--check`) were written together with their subject and are proved by mutation below.
**Final:** 28 unit tests green; ITs run twice in different class order (alphabetical, reverse): `LocalMeshBrokerIT` 3, the Skeleton
rehearsal 9 (1 skipped: the browser live notify), the resilience rehearsal 7 (2 skipped: scenarios 1b and 2, FLAG T6.6-1), the live ITs
16 skipped. Run 1 (alphabetical): 35 run, 0 failures, 19 skipped, 28 unit tests green. Run 2 (`-Dfailsafe.runOrder=reversealphabetical`, after the last edits): 35 run, 0 failures, 19 skipped, 28 unit tests green. One earlier run of `LocalMeshBrokerIT` hung because a synchronous `close()` of a publisher whose session JCSMP had just begun to reconnect parked forever (the same family as FLAG T6.6-1); that test now closes the severed publisher off the test thread with a bound.

*Mutation proofs (each planted, seen red, restored byte-for-byte).* MP1 the broker password accepted as a property: **survived the first
version of the test** (it still failed, for the missing environment variable), so the test was strengthened to set both and was then
killed; MP2 the evidence log stops refusing a registered secret: `EvidenceLogTest` red; MP3 a killed process comes back to life after its
first throw: red; MP4 the connection-loss window closes on a lost `status` instead of the first lost assessment: red; MP5 the run script's
write confirmation removed: 2 red; MP6 the rehearsal's renderer hashed unkeyed (`sha256:` spelling, the pre-B1 shape): the B1 assertion
red in both the manual and the gated apply; MP7 the final restore skipped: `@AfterAll` fails loudly ("THE HOST'S ORIGINAL CONFIGURATION
WAS NOT RESTORED BY THIS RUN"); MP8 `JcsmpEventPublisher.publish` rethrows (plan 007 Task 9 Step 5): scenario 1 red (the emitter's guard
logged "notice not published") and scenario 3 red (`submitApply` answered 500); MP9 `ReconcileDecision` re-applies an intended file: R1 red
(never resolved); MP10 the generation floor removed: red. **FLAG T6.6-1's gated scenarios were also proved correct, not merely written:**
with a temporary, non-committed patch that bounds the JCSMP send (300 ms, counted as a drop), scenario 2 (as designed) and scenario 1b
passed (drops counted 0 to 3 and 0 to 1, REST healed, `staged` delivered once before the kill) and the characterization test went RED
with its "FLAG ... IS FIXED" message, as intended; the patch was reverted (`cmp` identical to the original).

**Flags (read these before the live run).**
1. **FLAG T6.6-1 - RESOLVED by `fix(mesh): a broker outage never blocks a caller - bounded send, counted as a drop (T6.6-1)`.** `JcsmpTransport.accept`
   now hands every send to a small daemon pool and waits at most `sm.mesh.send-timeout` (default 400 ms); past the bound the parked send is
   cancelled (JCSMP's reconnect wait is interruptible), the notice is counted on `droppedCount()` and WARN-logged, and the caller returns; with
   every sender busy an offer is refused at once. No queue was added; publishing resumes by itself. `JcsmpSendBoundTest` proves it (mutation:
   bound removed, three tests fail by `@Timeout`), scenarios 1b and 2 are un-gated, and the characterization became the positive
   `aMidSessionBrokerOutageNeverParksTheRequestThreadAndPublishingResumesWhenTheBrokerReturns`. The test HTTP client now carries a 30 s request
   timeout. Closing a context against a dead broker still parks in `producer.close` (not bounded here; `E2eStack` abandons it after 90 s).
   *The original finding, kept for the record:* Design section 9
   says broker trouble is "never ... blocked publishes in `dp-core`" and that "all REST functions" stay unaffected (`BE-D1`). Measured on
   a real broker: when the broker dies while SM holds a session, `JcsmpTransport` (`reconnectRetries=-1`) leaves `producer.send` parked in
   `JCSMPBasicSession.waitUntilSessionReconnectDone`. Threads parked, by name: `apply-worker` and a Tomcat `http-nio-...-exec` thread. So
   `POST /apply` returns **no response at all** (its after-commit publish blocks the REST thread; the row is committed and open), the worker
   that claims the row blocks in the same wait, and closing the application context parks as well (`JcsmpEventPublisher.close` ->
   `producer.close`). Everything resumes when the broker returns. A broker that is down BEFORE SM starts is handled as designed
   (scenario 1 passes: drops counted). Plan 007 Task 9 scenarios 2 and 1b (as designed) are therefore kept but gated behind
   `-Dsm.walkthrough.known-defect-fixed=true`, and the repository's own idiom (`StopDuringApplyIT`) pins what is true today:
   `knownDefect_...` is green now and goes red the day the publisher stops blocking. Suggested direction, for the owner to decide: an
   asynchronous bounded hand-off in `JcsmpEventPublisher` (drop and count when full). This is a **second reason**, beyond OR-46, not to
   rely on notices for liveness.
2. **R1's window also loses `assessRollback`** (the plan's decorator loses `status` only). With only `status`, the engine's exhaustion path
   assesses the host, restores the pre-image and lands `FAILED_ROLLED_BACK` (BE-D22's own path), never `APPLY_INDETERMINATE`; a dropped
   SSH session fails the assessment too. The window opens when `materializeConfig` returns and closes on the first lost assessment, i.e.
   before the reconciler's first inspection. R1 is skipped with the observed landing if the request never lands `APPLY_INDETERMINATE`
   (decided from the reconciliation ledger row, because the reconciler resolves it within one poll).
3. **"SFTP write count" is the adapter's config-writing operations** (materialize, conditional rematerialize, restore): the SSH transport is
   package-private to `dp-core`, and those are exactly the operations that issue the write. In the rehearsal: 3 writes (the engine's bounded
   retry writes the same bytes again, really) at the moment the connection is restored and 3 after the reconciliation.
4. **Scenario 3's "exported metric" is skipped with its reason:** no exported metric exists (`droppedCount()` is a counter; its javadoc says
   the metrics plan must expose it). The injection is `ThrowOnceMeshPublisher`, a TEST class in `launcher/src/test` placed in `dp-core`'s own
   package because the seam (publisher over a transport and a shared counter) is package-private; production code is unchanged. It puts one
   throw in front of the real JCSMP transport; the swallow-and-count catch is the production one.
5. **Scenario 1 is the broker down before SM starts** (never connected); scenario 2 and the other half of 1 are flag 1. **Scenario 11** in the
   rehearsal: killed after the write with 1 open attempt, restart sweep lands `STAGED`, journal `PREFLIGHT#1, MATERIALIZE#1 (settled),
   PREFLIGHT#2, MATERIALIZE#2`, attempts within the budget of 3. Whether the SAME shape holds on the SSH-Docker adapter is exactly what the
   live run is for.
6. **Generation floor.** A retained pre-image is named by generation only and a fresh database counts from 1, so a second run against the same
   host would find the first run's generation-1 pre-image, keep it and "restore" a file that is not its own original. Each run therefore
   raises `instance_state.last_generation` (as the database owner, test setup) to a per-run value; asserted > 1e9.
7. **The restore.** The original keyed hash is read first (memory + evidence, never the repo) and the last step restores through
   `RuntimeAdapter.restore`, verifies by observation, restarts the container when the run restarted it (`restore.restart`, default on), and
   otherwise prints the exact manual restore; a run that does not leave the original **fails** (`@AfterAll`). SM's hidden `.<file>.sm-preimage-<gen>`
   files stay in the config directory.
8. **Operator-facing choices:** the run script requires `SM_E2E_CONFIRM_WRITE=yes` (it overwrites a config file and restarts a container);
   health is acquired `via-ssh` unless `SM_E2E_ACQUISITION=direct-http`; the Cache's own broker is staged only when `SM_E2E_BROKER_*` is
   given, its password only from `SM_E2E_BROKER_PASSWORD` (a `-D` property carrying one is refused), and only as a secret reference; the
   registered target pins the one fingerprint given (`TargetConnectionMain.register` is called directly, so the OR-89 host-key scan of the
   command's own `main` is not run); M4 (does Cache re-read `application.yml` without a restart) is an operator observation recorded in the
   evidence template, not something the journal can prove.
9. **Cost.** The two rehearsals plus `LocalMeshBrokerIT` add about 4 minutes and a 3 GB broker image to a full launcher IT run; they are
   tagged `broker` and skip themselves when the image is absent.

*Docker state.* Only this lane's containers were started (Postgres per class and the broker per class and per rehearsal); Testcontainers

**Results (T6.6 LIVE RUN, 2026-10-06; the integrator's run 3 on `integration/dp-remote-demo`; evidence `docs/plans/backend/evidence/010-first-e2e-walkthrough.md`, redacted of host coordinates).** *Run 3 passed:* `SkeletonWalkthroughIT` 9 run, 0 failures, 1 skipped (the browser live notify, open items 32/33/35) and `MeshResilienceIT` 7 run, 0 failures, 0 skipped (scenarios 1, 3, 11, R1 and, since FLAG T6.6-1 was fixed, 2 and 1b as designed, plus the bounded-publish positive test; R1 landed `APPLY_INDETERMINATE` and was confirmed with no second host write). *The original config was restored by the engine's own restore, and its keyed hash equalled the original, in both ITs.* *Runs 1 and 2 failed before any host write:* run 1 on a stale failsafe summary; run 2 because the evidence guard refused a line when the Cache broker's username equalled its password (fixed in `0e15cca7`). *Post-run observation, not a test result:* the host's ORIGINAL `application.yml` carried a stale broker password, so the Cache crash-looped (broker `401`) after the faithful restore; the owner chose to replace that one line with the current credential (done by the integrator; the original was backed up on the host). **OR-46 claim made:** declare, stage, apply and observe on one SSH-Docker Cache instance, REST authoritative, with `instance.apply-outcome` emitted after commit and received on a local broker. **Not claimed:** browser live notify, plan 007's event-driven skeleton complete, B/D5's skeleton, multi-placement, and M4 (not observed in run 3). Steps 1-3, 5 and 6 are ticked; **Step 4 is not**: the live ITs ran green once, not twice (the rehearsals ran twice in the preparation lane). Evidence sections the run did not capture (the operator's own `docker ps`/`ls -la`/`inspect` listings, §2, §9, §12) are marked "not captured" there, not filled.
removed them, a hung run was killed and its containers were reaped by Ryuk; nothing of other lanes or `oms-*` was touched.

### Task T6.7 — One apply path, enforced (plan 007 Task 12)

**Wave:** W8. **Depends on:** T6.3. **Parallel with:** T6.4 (owns `arch-tests` only).

**Files:** Create `backend/arch-tests/src/test/java/com/solace/sm/arch/ApplyPathBoundaryTest.java` and its planted
fixtures under `…/archfixture/`.

**Execute plan 007 Task 12 (`007-…md:1364-1412`) as written** — its four rules
(`onlyProductionCodeTouchesTheMutatingRuntimeAdapter`, `noHttpLayerHoldsATransport`, `theGatewayHoldsNoDatasource`,
`nothingProductionImportsTheDemoModule`) with their planted-violation proofs. Add a fifth, **scoped to what the
production tree actually contains (M5)** — measured, the demo probes are *called* from dp-core main code today:
`SshDockerMetrics.snapshot` calls `transport.cacheMetrics` (`SshDockerMetrics.java:26`), and the interface's own
default `cacheMetrics` calls the three single probes (`SshDockerTransport.java:70-71`). So the rule is two-part:

- `onlyTheMetricsFacadeCallsTheDemoCacheProbes` — no class **other than** `SshDockerTransport`, `SshjDockerTransport`
  and `SshDockerMetrics` calls `cacheStats`/`cacheSize`/`heapUsed`/`cacheMetrics` (the hard-coded `8081` probes,
  `SshjDockerTransport.java:218-247`);
- `nothingProductionUsesTheMetricsFacade` — no class in the analyzed (production) packages depends on
  `SshDockerMetrics` except `SshDockerMetrics` itself. Its only user is `demo-launcher` (`DemoConfiguration.java:43-46`),
  which `arch-tests` does not import, so the rule is green today and fires the moment production code reaches for it.

Each part gets its own planted fixture. The Files block stays `arch-tests` only — nothing in dp-core changes.

- [x] **Steps:** fixtures red → production green → prove each rule reports exactly its own fixture → commit
  `test(arch-tests): one apply path, no transport in the HTTP layer, no demo dependency`.

**Results (T6.7, 2026-10-05; lane `p010/s6` `85afee0`, integrated here as `433a346b`).** `ApplyPathBoundaryTest` in `arch-tests`: plan 007 Task 12's four rules plus M5's two, each with its own planted fixture; each proof requires the rule to report its own fixture and none of its siblings', and the real governed classes to be in the analysed set and not reported. Red first: 13 results, 7 failures (6 proofs that found no violation, plus the adapter rule red on today's tree). Counts at the lane: `ApplyPathBoundaryTest` 7 tests (6 rules plus 7 proofs, 13 results), `PlaneBoundaryTest` 20, `CompositionBoundaryTest` 5; arch-tests 56.

*Mutation proofs (each red, restored byte-for-byte).* Gateway exempted from the adapter rule; wiring-class exemption dropped; `net.schmizz..` dropped; `org.flywaydb..` dropped; launcher exempted from the demo rule; `heapUsed` dropped from the probe pattern; `SshDockerMetrics` dropped from the allowed callers; facade rule retargeted; the name marker removed from `ExcludeArchFixtures`.

*Deviations (measured).* The adapter rule exempts `ProductionApplyConfiguration` by class (T6.2 builds the adapter and the executor there) and the module's own `com.solace.sm.arch..` classes; `SshDockerTransport` is package-private, so its probe fixture sits in the `...ssh` package and `ExcludeArchFixtures` now also skips class files named `*ArchFixture*.class`.

**Flags.** Only three of the five datasource roots have planted proofs (`javax.sql`, `org.springframework.jdbc`, `org.flywaydb`); `jakarta.persistence` and `org.springframework.data` cannot be planted from this classpath. The retained demo module is outside the analysed set until plan 009 deletes `demo-launcher`.

### Task T6.8 — Verify-then-retry: the engine reconciles `APPLY_INDETERMINATE` against a fresh host observation (owner ruling 2026-09-29 (verify-then-retry))

**Wave:** W8b. **Depends on:** T6.2 (the production adapter and executor exist, so the worker is live), T6.4 (the
emitter — a give-up publishes `instance.apply-outcome`), **T6.9** (the restart-only row is gated exactly as its
strategy requires, and Q-R2's gate observation uses T6.9's probe-backed signal), T5.1 (the keyed hash, computed in
the DP from read-back bytes), T6.1 (the transport), T2.2 (the request stamp). ✅ **Owner questions
answered** (owner Step 7, batch 3, `OR-63`, all (a)): Q-R1, Q-R2, Q-R3, Q-R4 ([second review](#owner-questions-raised-by-the-second-review--answered-at-t01-step-7));
the text below is the ruling, not a recommendation. **Parallel:** ⛔ alone — it edits `ApplyExecutor`,
`OutcomeService` and the `RuntimeAdapter` seam, and it changes the engine's recovery contract.

**⚠️ What this supersedes — flagged, not ratified.** Plan 005's rule that recovery never touches
`APPLY_INDETERMINATE` — measured: `ApplyExecutor.resume` skips it on purpose (*"resuming one would be the engine
confirming its own uncertainty"*, quoted in plan 007 Task 4b, `007-…md:737`; the skip is documented at `ApplyExecutor.java:788-790`),
part of `BE-D12`/`BE-D16` (both **PROPOSED**) — and plan 007 Task 4b's planned
`aTenantWhoseOnlyNonTerminalRowIsApplyIndeterminateIsEnumeratedAndThenLeftAlone` (`007-…md:760`). ⚠️ **That test was
never written** (measured: no `LeftAlone` anywhere under `backend/*/src`; plan 007's Results list its "then left
alone" half as still open, `:1880`), so this task **writes it new** with the new expectation. It does not "change"
it. The ruling supersedes both on the owner's authority (OR-1, OR-59). This task records a **new** `«next free
BE-D»` that supersedes exactly that part of `BE-D12`/`BE-D16` (never an in-place edit, `I3`), cites the ruling, and
says it supersedes a PROPOSED decision by ruling, not by ratification.

**Why the old concern is answered, not ignored.** The old rule forbade the engine *confirming its own uncertainty*.
The ruled reconciler never does: every decision rests on a **fresh observation of the host** — the keyed hash of
the config file (OR-32, computed in the DP, T5.1), the adapter's **retained pre-image**, and the container's start
baseline — and every branch either proves the state from that observation or refuses to act. Content SM did not
write is **never overwritten**.

**The fresh observation, defined (B-2, M-2).** One inspection reads, for the request's placement and generation,
outside any database lock:
- **current** = the keyed hash of the config file now, or *absent*;
- **prior** = the keyed hash of the adapter's **retained pre-image** for this generation, read from the host now
  (`preimagePath(p, generation)`, the file `assessRollback`/`restore` read: `RuntimeAdapter.java:79-109`,
  `SshDockerRuntimeAdapter.java:114-176`), **or** *absent-recorded* when the adapter's absence marker
  (`absencePath`) is present instead, **or** *unknown* when neither exists (the cut came before the pre-image was
  retained). ⛔ **Not** `PreBaseline`: that is the container start record `(startedAt, processRef)`
  (`PreBaseline.java:26-29`), carried only by `LIFECYCLE` attempts (`baseline_only_for_lifecycle`), and it says
  nothing about file content;
- **restarted by this request** = decided **exactly as `HealthGate` decides it** (`HealthGate.java:82-85`): the
  replica's current start baseline is present **and differs** from the `pre_baseline` of this request's latest
  `LIFECYCLE` attempt. Only host-clock values are compared, never a DP timestamp. No `LIFECYCLE` attempt means the
  replica was **not** restarted by this request. A replica whose baseline cannot be read (not running, or
  mid-restart — **M18**) is **undecided**: the inspection counts as failed and is retried, and the reconciler
  never restarts it. This is the same rule `ResumePlanner` applies on resume (§2.2 rule 3).

Hashes are compared with the keyed verifier (`ConfigHasher.verifies`, D-KEY §5, T5.1). "intended" = the request's
`MATERIALIZE` intent's bound hash.

**The decision table** (OR-59, made exact):

| Fresh host observation | Action | Lands |
|---|---|---|
| current = intended; strategy `manual` | **confirm** (Q-R4 (a)): a matching file is the whole proof; ⛔ no host write | `INDETERMINATE_RESOLVED` (applied), evidence = the observation; rung `persisted` (T6.5) |
| current = intended; restarting strategy; restarted by this request | `rolling-health-gated`: **one read-only gate observation** (`status()` on T6.9's declared-probe signal; Q-R2 (a)). `HEALTHY` → **confirm**, recording `gateProved`; anything else → no action. Other restarting strategies (none are built in v1: they are `501`) → confirm. ⛔ No host write | `INDETERMINATE_RESOLVED` (applied); or stays `APPLY_INDETERMINATE`, `reconcile.gate.unproven`, alert |
| current = intended; restarting strategy; **not** restarted by this request | **re-drive the restart only** (the `LIFECYCLE` step, gated exactly as the strategy requires, T6.9), then T6.9's post-restart `VERIFY` | the normal landing |
| current = prior (the retained pre-image's hash), **or** current *absent* and prior *absent-recorded* (Q-R1 (a)) | **re-apply** — `MATERIALIZE` through the new **conditional** write (below), then `LIFECYCLE` if restarting, then `VERIFY` | the normal landing |
| anything else: current matches neither, prior *unknown* and current ≠ intended, or the retained pre-image has vanished or no longer matches its own recorded hash | ⛔ **do not overwrite**; alert | stays `APPLY_INDETERMINATE`, `reconcile_class = reconcile.foreign.content` |
| host unreachable, a read fails, or restart undecided | **bounded retry with backoff**. After the bound, alert | stays `APPLY_INDETERMINATE`, `reconcile_class = reconcile.exhausted` |

**Bounds and give-up (M-3; Q-R3 (a)).** Four configurable settings, each with its default:
- `sm.dp.apply.reconcile.max-attempts` (**5**) counts **failed or undecided inspections**.
- `.initial-backoff` (**30 s**) and `.max-backoff` (**5 min**) set the spacing, which doubles up to the maximum.
- `.max-redrives` (**1**) counts **re-drives**, whether restart-only or re-apply. It is separate from the
  inspection counter.
- `.max-redrive-age` (**1 h**) runs from the request's first `APPLY_INDETERMINATE` landing. Past it the reconciler
  may still **confirm**, which is a read, but never re-drives; it gives up with `reconcile.redrive.too.late`.

A re-drive that is cut off again lands `APPLY_INDETERMINATE` and is inspected again. Once `max-redrives` is spent,
the next "would re-drive" gives up with `reconcile.redrive.exhausted`. When `AttemptService`'s per-intent cap fires
on a re-drive's attempt (`RetryCapExceeded`), that is also a give-up, with `reconcile.redrive.cap`.
Every give-up is **permanent**: `apply_reconciliation.gave_up_at` is set, and a row is due only when
`gave_up_at IS NULL AND next_attempt_at <= now()`. So a given-up request is never polled again, and a tenant that
OR-44 keeps enumerating costs one indexed read per poll. The human exit is unchanged: the operator's
`resolveIndeterminate`. The runbook line (below) states the latest time a re-drive can occur.

**Safety rules beyond the table.**
1. **A later request forbids any write.** A stop may be admitted while an apply is indeterminate (measured:
   `AdmissionFencesIT.aStopIsAdmittedWhileAnApplyIsIndeterminateWhileASecondApplyIsRefused`; stops are exempt from
   the fence, `AdmissionService.java:31-35`). So if any request newer than this one exists on the scope, the
   reconciler may still **confirm** (read-only) but never restarts or re-applies. It gives up with
   `reconcile.superseded.by.later.request`.
2. **Never hold a database lock across SSH (M-4).** The reconciler works in three phases:
   - **Observe**, with no lock held.
   - **Decide and transition**, in one short transaction under admission's order
     (`apply_scope → instance_state → (row)`, Global Constraints). It re-checks rule 1 and that the row is still
     `APPLY_INDETERMINATE` with an unchanged `state_version`, then moves `APPLY_INDETERMINATE → RUNNING`, or
     confirms, or gives up. The scope lock is released before any host call, so the operator's emergency stop is
     never blocked behind a probe timeout.
   - **Act**, through the engine's normal step loop, with the request `RUNNING`.

   A stop admitted after the decision runs **alongside** the re-drive. The re-drive then meets the stopped replica
   by **observation** and ends through its normal failure path, `lifecycle.replica.not.running` → `CONFIG_DIVERGED`
   (`ApplyExecutor.java:422-450`). That is the design's §3.3 rule. Obligation (r) as a whole stays **UNPROVEN**, as
   plan 005 recorded it (`ApplyExecutor.java:397-412`), and this task does not claim to close it.
3. **The re-apply write is conditional (M-4).** Today `materializeConfig` writes without re-checking its target
   (`SshDockerRuntimeAdapter.java:73-85`), unlike `restore`, which re-reads the pre-image and refuses
   `rollback.preimage.altered`/`vanished` (`RuntimeAdapter.java:94-109`). The seam therefore gains **one**
   operation, recorded in its own `«next free BE-D»` beside `BE-D25`'s precedent of adding an operation:
   `rematerializeIfCurrent(TargetReplica, long generation, RenderedConfig, String expectedCurrentHash /* nullable = absent */)`.
   It re-reads the file immediately before the write, writes **nothing** and throws `reconcile.target.moved` if the
   file no longer matches the observation, and otherwise behaves as `materializeConfig`. ⚠️ It narrows the window
   exactly as `restore` does, but it is **not** atomic against a concurrent writer on the host: SFTP has no
   compare-and-swap. The residual window, re-check to write, is recorded in the decision. The seam-set guard
   (`RuntimeSeamTypesTest`) and the adapter contract kit go red first, then are updated. `DockerAdapter` implements
   it too.
4. **Only the reconciler takes `APPLY_INDETERMINATE → RUNNING`**, and only from `APPLY_INDETERMINATE`. The claim
   remains the only `REQUESTED → RUNNING` (`ApplyClaimRepository.java:18`). The operator's `resolveIndeterminate`
   against a row the reconciler has moved to `RUNNING` is refused cleanly, as it is for any non-indeterminate row
   (measured in Step 0). A reconciler transition that loses the race to an operator resolution finds the row no
   longer `APPLY_INDETERMINATE` and drops its bookkeeping (`gave_up_at` set, reason `resolved.by.operator`). No host
   call is made (MINOR 10).
5. **No new cross-tenant read.** Due reconciliations are found through **OR-44's existing**
   `tenantsWithNonTerminalWork()`, which already yields a tenant whose only open row is `APPLY_INDETERMINATE`
   (`TenantEnumerationRepositoryIT.enumeratesATenantWhoseOnlyNonTerminalRowIsApplyIndeterminate`).
6. **Startup enqueues, never blocks (MINOR 9).** Recovery hands each `APPLY_INDETERMINATE` row to the reconciler by
   **upserting its `apply_reconciliation` row** with `next_attempt_at = now()` and returns. The worker's poll runs due
   reconciliations. Boot never waits on a host.
7. **The alert, on every give-up (M-1).** The give-up goes through a new `OutcomeService.recordReconcileGiveUp(t, id,
   ErrorClass)`, which writes `apply_request.reconcile_class` (V011). T2.2's trigger stamps it, because it is not
   the compaction fold. The same method calls T6.4's emitter after commit: one `instance.apply-outcome`
   (`APPLY_INDETERMINATE`, the new `stateVersion`; OR-58). `observeApply` exposes the classifier as
   `reconcileReason` (T0.1 point 3), read through T6.3's `ApplyObservationReader`. One WARN line carries ids and
   the classifier only (`I6`). The request is **also** listed in home attention (`reason: apply-indeterminate`), but
   that is **T10.5's** (W11), tested there, not here (MINOR 6). No new REST operation.

**Step 0 — measure before designing the mechanics (record each answer in the decision draft):** (a) confirm the
second review's measurement that `APPLY_INDETERMINATE → RUNNING` is legal in the DDL (V002's
`state_fits_operation`, V006's `forbid_terminal_regression`), and find what `OutcomeService`'s Java transition guard
allows (`OutcomeService.java:139-144`); (b) whether the intent/attempt journal can hold a second
`MATERIALIZE`/`LIFECYCLE` attempt on the same request within `BE-D22`'s invariants (`ensureIntent` is idempotent;
the per-intent cap counts the original run's attempts); (c) whether `resolveIndeterminate` can record an automated
confirmation — `Resolution.resolvedBy` is documented as "the principal who confirmed — never a display name"
(`Resolution.java:28`), so a reconciler identity is a **new meaning**, flagged in the decision draft. The
alternative is `OutcomeService`'s existing **direct** `APPLY_INDETERMINATE → APPLIED` transition with the resolution
columns NULL (the agent-replay path, `OutcomeService.java:139-144`), which carries no evidence columns and so cannot
hold the observation. Record which is chosen and why. (d) what `resolveIndeterminate` does on a `RUNNING` row
(rule 4). **Recommended mechanics:** re-drive **the same request** (same `applyId` — one fleet member, one journal
line, one apply-outcome per stamp), recorded as new attempts; confirm through `OutcomeService.resolveIndeterminate`
with a fixed, non-principal resolver token named in the decision. If a same-request re-drive would break a
`V006`/`BE-D22` invariant, **stop and record it** — no second request shape is invented without the integrator.

**Files:** Create `…/sm_data/V011__indeterminate_reconciliation.sql`:
- `apply_reconciliation(tenant_id, apply_id, inspections, redrives, next_attempt_at, first_indeterminate_at,
  gave_up_at, last_class)`, with runtime `SELECT, INSERT, UPDATE` and no `DELETE`. It is **not** a stamped owning
  row: the counters are bookkeeping, and the observable change is the request's classifier. This reading is flagged
  in the decision draft.
- `apply_request.reconcile_class text NULL`, with a CHECK mirroring `ErrorClass`'s grammar (as V002 mirrors
  `ValueGrammar`) and `GRANT UPDATE (reconcile_class)` to the runtime role.
- No state-CHECK change unless Step 0 (a) finds one needed.

Create `backend/dp-core/src/main/java/com/solace/sm/dp/apply/reconcile/{IndeterminateReconciler,ReconcileDecision,HostObservation,ReconcileProperties,ReconcileConfiguration}.java`
and `…/apply/persistence/JdbcReconciliationRepository.java`.

Modify:
- `…/apply/engine/ApplyExecutor.java`: recovery enqueues `APPLY_INDETERMINATE` to the reconciler instead of skipping
  it; the javadoc of `resume` and `strand` cites the supersession.
- `…/apply/engine/OutcomeService.java`: `recordReconcileGiveUp`, and the re-drive transition if Step 0 needs it.
- `…/apply/persistence/ApplyRequestRepository.java`: the column.
- `…/apply/worker/ApplyWorker.java`: the poll also runs due reconciliations.
- `…/apply/runtime/{RuntimeAdapter,ssh/SshDockerRuntimeAdapter,docker/DockerAdapter}.java` and the adapter contract
  kit: `rematerializeIfCurrent`.
- `…/apply/service/ApplyObservationReader.java`, `…/apply/persistence/JdbcApplyObservationRepository.java`, and
  `console-gateway/.../ApplyObservation.java`: `reconcileReason`.
- `…/dp/DataPlaneConfiguration.java`: one import.

Modify the tests that pin the old skip. Find them with `grep -rn 'APPLY_INDETERMINATE' backend/dp-core/src/test backend/launcher/src/test`
(measured: 17 files; at least `ResumeIT`, `ResumeRunningIT` and `TenantEnumerationRepositoryIT`). Each
is **changed to the new expectation, never deleted or weakened**, with before/after counts recorded.

Create:
- `…/reconcile/{ReconcileDecisionTest,IndeterminateReconcilerIT}.java`
- `launcher/.../IndeterminateReconciliationIT.java`
- plan 007 Task 4b's never-written case, **new**:
  `aTenantWhoseOnlyNonTerminalRowIsApplyIndeterminateIsEnumeratedAndThenReconciled`.

Modify `docs/design/backend/decisions.md` (the two `«next free BE-D»` bodies: the supersession, and the seam
operation). Modify `docs/runbooks/install-prerequisites.md` (the latest re-drive time, from Q-R3's settings).

- [ ] **Step 0:** the four measurements above, recorded; fill **M18**.
- [x] **Step 1: Failing tests.**

```java
@Test void everyDecisionIsMadeFromAFreshHostObservation() { }                  // never from the journal alone
@Test void priorIsTheRetainedPreImageReadFromTheHostNotThePreBaseline() { }    // B-2
@Test void intendedFileUnderManualIsConfirmedWithoutAStartTimeCheck() { }      // Q-R4 (a)
@Test void intendedFileRestartedByThisRequestIsConfirmedWithNoHostWrite() { }  // spy adapter: 0 materialize, 0 lifecycle
@Test void restartedIsDecidedAgainstTheLifecyclePreBaselineOnHostClockOnly() { }  // M-2: DP clock skewed ±1 h changes nothing
@Test void aReplicaMidRestartIsUndecidedAndRetriedNeverRestarted() { }        // M-2 / M18
@Test void gatedConfirmTakesOneGateObservationAndRecordsGateProved() { }      // Q-R2 (a)
@Test void gatedConfirmWithoutAHealthyObservationStaysIndeterminate() { }     // reconcile.gate.unproven
@Test void intendedFileNotRestartedReRunsOnlyTheRestartThenVerifies() { }      // exactly 1 lifecycle, 0 materialize
@Test void priorContentIsReAppliedThenVerified() { }                           // and absent-recorded (Q-R1 (a))
@Test void anUnknownPriorWithForeignContentIsNeverOverwritten() { }           // cut before the pre-image was retained
@Test void foreignContentIsNeverOverwrittenAndStaysIndeterminateWithAnAlert() { }   // 0 writes; classifier; 1 event after commit
@Test void foreignContentWrittenBetweenInspectionAndWriteIsNotOverwritten() { }    // M-4: rematerializeIfCurrent refuses
@Test void anUnreachableHostIsRetriedWithBackoffUpToTheBoundThenStaysAndAlerts() { } // exactly max-attempts inspections, spacing grows
@Test void reDrivesAreBoundedSeparatelyAndAGiveUpIsPermanent() { }            // M-3: flapping host; never due again
@Test void aReDriveHittingThePerIntentCapGivesUp() { }                        // reconcile.redrive.cap
@Test void noReDriveHappensPastTheMaxRedriveAge() { }                          // Q-R3 (a)
@Test void theRetryBoundSurvivesARestart() { }                                 // the counters are persisted, not in memory
@Test void noLockIsHeldWhileTheHostIsObserved() { }                           // M-4: a stop is admitted during a slow probe
@Test void aLaterRequestOnTheScopeForbidsAnyWriteButStillAllowsConfirm() { }   // a stop admitted while indeterminate
@Test void aStopAdmittedDuringAReDriveEndsItByObservation() { }               // CONFIG_DIVERGED, lifecycle.replica.not.running
@Test void operatorResolutionOfARunningReDriveIsRefused() { }                 // MINOR 10
@Test void aReconcilerLosingTheRaceToAnOperatorResolutionDropsItsBookkeeping() { }
@Test void onlyTheReconcilerMovesIndeterminateToRunning() { }                  // and only from APPLY_INDETERMINATE
@Test void aGiveUpStampsTheRequestPublishesOneEventAndIsObservable() { }      // M-1: reconcile_class, stateVersion, event, reconcileReason
@Test void theReconcilerReadsNoTenantOutsideOr44sEnumeration() { }
@Test void anOperatorResolutionStillWorksAfterTheReconcilerGaveUp() { }
@Test void noLogLineClassifierOrEventCarriesFileContentOrAnUnkeyedHash() { }   // I6, OR-32
@Test void startupRecoveryEnqueuesIndeterminateRowsAndDoesNotBlockBoot() { }  // MINOR 9: boot completes with the host unreachable
@Test void aTenantWhoseOnlyNonTerminalRowIsApplyIndeterminateIsEnumeratedAndThenReconciled() { }  // plan 007 Task 4b, new
```

- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4:** `VERIFY dp-core`, `VERIFY launcher`, `VERIFY demo-launcher`
  (the seam gained an operation), `VERIFY arch-tests` (T6.7's rules green: the reconciler is engine code on the one
  apply path; no controller reaches it).
- [x] **Step 5: Prove they fire.** Each mutation below turns the named test red; revert each.

  | Mutation | Red |
  |---|---|
  | Treat "anything else" as "prior" (overwrite) | the foreign-content test |
  | Read "prior" from `PreBaseline` | the pre-image test |
  | Compare `StartedAt` with the attempt's DP timestamp | the skewed-clock test |
  | Restart a replica whose baseline is unreadable | the mid-restart test |
  | Drop the start-baseline conjunct (restart even when already restarted) | the confirmed-no-write test |
  | Skip the gate observation on a gated confirm | the `gateProved` test |
  | Remove the inspection bound | the inspection-bound test |
  | Remove the re-drive bound | the flapping-host test |
  | Leave `gave_up_at` unset on give-up | the permanent-give-up test |
  | Keep the counters in memory | the restart-survival test |
  | Hold the scope lock across the observation | the no-lock test |
  | Call `materializeConfig` instead of `rematerializeIfCurrent` | the between-inspection-and-write test |
  | Write the classifier nowhere (today's `land()`) | the give-up-observable test |
  | Compare an unkeyed digest | the decision tests (nothing matches) |
  | Act despite a later request | the scope test |
  | Run reconciliations inline at startup | the boot test |
- [x] **Step 6: Commit.** `feat(dp-core): verify-then-retry — APPLY_INDETERMINATE reconciled from a fresh host observation, never overwriting foreign content`

**May claim:** an indeterminate apply is reconciled from a fresh host observation, with bounded inspections, bounded
re-drives and an observable, stamped alert on give-up (event + `reconcileReason`). **Must not claim:** that
`BE-D12`/`BE-D16` or their supersession are ratified (flagged); that the re-apply write is atomic against a
concurrent host writer (it narrows the window, as `restore` does); that obligation (r) is closed; that the give-up
reaches home attention (T10.5).

**Results (T6.8, 2026-10-05; lane `p010/s6` `3c54a42`, integrated here as `9dc60df4`).** `IndeterminateReconciler` and `ReconcileDecision` (`dp/apply/reconcile`); the seam's ninth operation `rematerializeIfCurrent` (implemented by `SshDockerRuntimeAdapter` and `DockerAdapter`); `sm_data` V011 (`apply_reconciliation`, `apply_request.reconcile_class`); `ApplyWorker` runs due reconciliations and `ApplyStartupRecovery` enqueues them; `reconcileReason` is served through `observeApply` only while the request is `APPLY_INDETERMINATE`. Step 0 is recorded in the decision draft, and `ReconciliationSchemaIT` pins (a) and (b): the `APPLY_INDETERMINATE -> RUNNING` walk is already legal in the DDL, so V011 adds only the table and the column. The two decisions are allocated `BE-D34` (verify-then-retry, superseding by owner ruling `OR-59`/`OR-63`/`OR-64`, not by ratification) and `BE-D35` (`rematerializeIfCurrent`), both PROPOSED. Runbook section added to `docs/runbooks/install-prerequisites.md`.

*Lane counts.* dp-core 513 unit and 651 IT (0 failures, 5 skipped); launcher 51 of 52 selected (the one failure was `ApplyEventsProductionBootIT`, fixed under T6.4); arch-tests, demo-launcher and console-gateway green. Red first: `ReconcileDecisionTest` 19 of 22, `ReconciliationSchemaIT` 9 of 11 (V011 removed), `IndeterminateReconcilerIT` 34 of 34 on the skeleton. `ReconcilePropertiesTest` (3) and `ReconcilerWiringIT` (2) were never seen red; the Docker contract kit and the other launcher tests were not red-first. The Docker contract kit caught a real bug: `DockerAdapter`'s re-write captured a pre-image (fixed with `ConfigFileStore.rewrite`).

*Mutation proofs.* All 16 listed mutations turned the named tests red and were restored byte-for-byte (M01-M10, M12-M16); the lock mutation M11 fails the no-lock test with its assertion message alone, and the full-class run deadlocks; extras M17, M19 and M20 also fire. M15 mutates both the decision and `beginRedrive`, since either check alone covers the other.

*Deviations.* `HostObservation` sits in `engine`, not `reconcile`, to avoid a package cycle; the IT sits in the `persistence` test package (package-private harness); an unreadable probe is retried (a failed read), not treated as a give-up; `ApplyItSupport.RoutingAdapter` was extended.

**Step 0, M18:** measured locally with a throw-away `alpine` container (`StartedAt` moves on restart and on stop then start). **Still open for W9** (demo host): container state while a restart is in progress. **Flags.** Obligation (r) is not closed by this task; the give-up reaching home attention is T10.5.

### Task T6.9 — `rolling-health-gated` gates on the package-declared probe, and reports which strength it proved (owner ruling 2026-09-29 (S0-10))

**Wave:** W8a, first in the T6.9 → T6.5 agent, after T6.4 merges. **Depends on:** T0.1a (the schema field), T2.1
(`placement.health_strength`), T6.1 (`HostProbe`), T6.2 (`TablePlacementCatalog`), T6.3 (admission accepts the
strategy), **T6.4** (it edits `ApplyExecutor` first). ✅ **Owner questions answered** (owner Step 7, batch 3,
`OR-64`, both (a)): Q-G1, Q-G2 — the text below is the ruling, not a recommendation. **Parallel:** ⛔ none. It owns the SSH-Docker adapter's status health path,
`SshDockerPlacement`'s probe fields, the post-restart `VERIFY` in `ApplyExecutor`, the observation reader's
`gateProved`, the gateway `ApplyObservation` record's `gateProved`, and the runbook note.

**Measured — the engine does need a change (M-7; the earlier "no change" was wrong).** The gate (`HealthGate`,
`SM-D15`) is a policy over `RuntimeAdapter.status` observations: it requires *both* a moved baseline *and* `HEALTHY`
(`HealthGate.java:82-88`). A runtime with no health signal reports `NOT_DECLARED`, and `rolling-health-gated` is
then refused before any write (`ApplyExecutor.java:550-557`). That half needs no engine change. The adapter's
signal simply comes from the placement's copy of the package declaration, the same way for every MI. **But OR-53's
read-back is not there.** The forward gated path closes the `LIFECYCLE` attempt with `readBack(replica)` and lands
`APPLIED` without comparing anything (`ApplyExecutor.java:504-507`). `VERIFY` exists only on the restore path
(`:718-729`). So this task adds a `VERIFY` step to `ApplyExecutor`'s restarting path.

1. `SshDockerPlacement` carries the placement's probe `(kind, path, hostPort, strength)`; `TablePlacementCatalog`
   (T6.2) passes them from the immutable `placement` row.
2. `SshDockerRuntimeAdapter.status()`: when the placement declares a probe **SM executes** (the HTTP kinds
   `actuator-health` and `http-status`), `health` comes from `HostProbe` (instance acquisition mode `via-ssh`) or a direct HTTP read
   (`direct-http`, host from the **target's** registered address `target_connection.host` (`OR-82`), port from the placement's `health_port`) — the mode is read at
   **instance** level (owner rulings M-Q2/M-Q2b, `OR-71`), never per placement. The mapping, per **M3** and **M19**:
   - HTTP `200` with `status: UP` → `HEALTHY`.
   - A `DOWN`/`OUT_OF_SERVICE` answer (by HTTP status and body; no `--fail`, T6.1) → `UNHEALTHY`. The gate then
     reports `gate.unhealthy`, not a transport classifier.
   - A transport failure (non-zero exit, no status) → the adapter's unreadable path, and the gate keeps polling to
     its deadline.

   ⛔ **No fallback:** when an executable probe is declared, `docker inspect`'s health is **never** used as the
   signal. Liveness and `StartedAt` still come from `docker inspect`, so "the baseline moved" is unchanged.
   **With no probe SM executes** (none declared, or only `broker-heartbeat`, which v1 does not run),
   `health = NOT_DECLARED` **whatever the image's `HEALTHCHECK` says**, and pre-flight's `healthSignalDeclared`
   (from `prepare`, which the refusal at `ApplyExecutor.java:555-557` reads) is `false`. So `rolling-health-gated`
   fails closed before any write (Q-G1 (a); T0.1 point 26). `healthSignalDeclared` is `true` exactly when an
   executable probe is declared. (Q-G1 (b) — keep `docker inspect`'s signal and leave
   `gateProved` absent — was **not** chosen.)
3. **After the restart, SM reads the file back and verifies it** with OR-32's keyed hash (OR-53). For each replica
   on a restarting strategy, once its `LIFECYCLE` attempt succeeds (and, under `rolling-health-gated`, once its gate
   passes), the engine opens one **`VERIFY`** intent bound to the `MATERIALIZE` intent's hash. It reads the file
   back and closes the attempt with the observed hash. A match continues; a mismatch lands **`CONFIG_DIVERGED`,
   classifier `verify.hash.mismatch`**, with no automatic rollback (Q-G2 (a), the same as a failed gate,
   `ApplyExecutor.java:497-502`). Step 0 measures whether `V004`/`V005`'s step/operation predicates admit a `VERIFY`
   intent on an `apply` request's forward path. If they do not, the task **stops and records it**; no migration is
   allocated for it. The ladder's `restart-observed` (T6.5, next in this agent) requires this `VERIFY` to have
   matched.
4. **Report the strength.** The passed gate step in `observeApply` carries `gateProved`. It equals the placement's
   `health_strength` **only when** the placement declares an executable (HTTP) probe. By point 2, that probe was
   then necessarily the gate's signal, because there is no fallback; this is deterministic from the immutable
   placement row, and a test pins the "no fallback" property it rests on. It is absent otherwise (M-6).
   `getInstance`'s placements carry `probeStrength`, the **declaration** (T0.1 point 26). ⛔ Nothing ever reports
   `readiness` for a probe declared `liveness` or for a probe that never ran, and nothing upgrades a declaration.
5. **Runbook** — add to `docs/runbooks/install-prerequisites.md` (or the operator runbook the repo keeps; find it
   first): *"Test and verify restarting strategies in dev first. A `rolling-health-gated` apply restarts the MI and
   trusts the package's declared health probe. With a liveness-only probe (Cache's, as declared), the gate proves
   only that the process came up — not that it is serving. Verify in a dev environment that the declared probe
   behaves as declared before using a restarting strategy in production."*

**Files:** Modify `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/ssh/{SshDockerPlacement,SshDockerRuntimeAdapter}.java`,
**`…/apply/engine/ApplyExecutor.java`** (the post-restart `VERIFY`, point 3), `…/dp/inventory/TablePlacementCatalog.java`,
`…/apply/service/ApplyObservationReader.java`,
`backend/console-gateway/src/main/java/com/solace/sm/gateway/http/ApplyObservation.java`,
`backend/demo-launcher/src/main/java/com/solace/sm/demo/DemoInstanceService.java` (it builds `SshDockerPlacement` —
M5: the demo keeps compiling, with no probe); `docs/runbooks/install-prerequisites.md`; Create tests
`…/runtime/ssh/ProbeBackedHealthTest.java`, `…/engine/PostRestartVerifyTest.java`, `launcher/.../RollingHealthGatedIT.java`; extend
`SshDockerRuntimeAdapterContractTest`, `RollingApplyIT` and `SchemaMirrorLockTest`.

- [ ] **Step 0: Fill M3, M12, M18 and M19**, and measure point 3's `VERIFY`-on-apply legality.
- [x] **Step 1: Failing tests.**

```java
@Test void aDeclaredProbeIsTheGatesHealthSignal() { }            // docker inspect NOT_DECLARED, probe UP -> gate passes
@Test void aDeclaredProbeIsNeverReplacedByDockerInspectHealth() { }  // M-6: HEALTHCHECK healthy, probe DOWN -> gate fails
@Test void aProbeDownFailsTheGateAsGateUnhealthyAndLandsConfigDiverged() { }  // MINOR 11: gate.unhealthy, not a transport class
@Test void noExecutableProbeFailsClosedEvenWithAHealthyDockerHealthcheck() { } // Q-G1 (a): strategy.health.signal.absent, FAILED_NO_EFFECT
@Test void aBrokerHeartbeatOnlyPackageFailsClosedAndReportsNoGateProved() { } // M-6
@Test void theObservationReportsLivenessForALivenessDeclaredProbe() { } // Cache's fixture
@Test void aNonCachePackageDeclaringReadinessIsReportedReadiness() { }  // generic: same code, another declaration
@Test void afterTheRestartTheFileIsReadBackAndVerifiedWithTheKeyedHash() { }  // a VERIFY intent per replica
@Test void aReadBackMismatchAfterTheRestartLandsConfigDiverged() { }   // Q-G2 (a): verify.hash.mismatch, no rollback
@Test void theProbeCommandIsTheFixedHostProbeCommand() { }       // SM-D11a: nothing else reaches session.exec
@Test void rollingHealthGatedIsServedInTheProductionBoot() { }   // RollingHealthGatedIT
```

- [x] **Steps 2–4:** fail → implement → `VERIFY dp-core`, `VERIFY console-gateway`, `VERIFY launcher`,
  `VERIFY demo-launcher`, `VERIFY arch-tests`.
- [x] **Step 5: Prove they fire.** Each mutation below turns the named test red; revert each.

  | Mutation | Red |
  |---|---|
  | Map `DOWN` to `HEALTHY` | the probe-down test |
  | Fall back to `docker inspect` health when the probe is unreadable | the no-fallback test |
  | Keep `docker inspect`'s signal for a placement with no executable probe | the fail-closed test |
  | Derive `gateProved` from the declared strength whatever the kind | the broker-heartbeat test |
  | Hard-code `gateProved = liveness` | the readiness test |
  | Hard-code `gateProved = readiness` | the liveness test |
  | Skip the post-restart `VERIFY` (today's code) | the verify test |
  | Land `APPLIED` on a mismatch | the mismatch test |
  | Use `--fail` in the probe command | the gate-unhealthy classifier assertion |
- [x] **Step 6: Commit.** `feat(dp-core): rolling-health-gated gates on the package-declared probe, verifies the file after the restart, and reports the strength it proved (S0-10)`

**May claim:** `rolling-health-gated` served for any package that declares an executable (HTTP) probe, with the
post-restart read-back verified and the proved strength reported. **Must not claim:** that any gate proved
*readiness* for a liveness-declared package (Cache's); that a `broker-heartbeat` probe is executed (v1 does not).

---

**Results (T6.9, 2026-10-05; lane `p010/s6` `1e0fc4e`, integrated here as `6ff6c90c`).** Gate health comes only from the package-declared probe, with no fallback to Docker's `HEALTHCHECK`: a placement with no executable probe reports `NOT_DECLARED` and the gated strategy fails closed (`strategy.health.signal.absent`); an unreadable probe gets `HealthSignal.UNREADABLE`, the gate keeps polling, then reports `gate.health.unreadable`. Each replica gets a `VERIFY` intent after its restart and gate; a mismatch lands `CONFIG_DIVERGED` (`verify.hash.mismatch`) with no rollback; resume no longer sends a forward `VERIFY` to the rollback tail. `observeApply` reports `gateProved` on a passed gated lifecycle step, read from the placement row. No migration was needed (V004/V005 admit `VERIFY` on an apply's forward path). Runbook section "Restarting strategies: test them in dev first".

*Lane counts.* `dp-core,console-gateway -am verify`: surefire 461, failsafe 586 (5 skipped), gateway 316; launcher ITs (`RollingHealthGatedIT`, `ApplyEndpointsIT`, `InstanceEndpointsIT`, `StopEndpointIT`, `ObserveBackfillIT`, `ProductionWorkerLiveIT`, `ApplyEventsProductionBootIT`) 42; arch-tests and demo-launcher pass (`SshDockerPlacement` keeps its old 13-argument constructor). Red: `ProbeBackedHealthTest` 9 of 12, `PostRestartVerifyIT` 7 of 8, `ApplyServiceGateProvedTest` 2 of 5; the catalog, `DirectHttpProbe`, gateway and launcher tests were proved only by mutation.

*Mutation proofs (each red, restore checked by SHA-256).* DOWN read as HEALTHY; fallback on an unreadable probe; the Docker signal kept for a no-probe placement; three `gateProved` variants; skipped `VERIFY`; landing `APPLIED` on a mismatch; `--fail` in the probe command (red in the command-string tests, not in a gate classifier assertion); redirects followed; the catalog ignoring the acquisition mode; direct mode falling back to SSH; resume routing `VERIFY` to the rollback tail; the gateway dropping `gateProved`.

**Step 0:** M3 filled; M12's local half, M18 and M19 filled in `docs/tasks/t6-9-measured-facts.md` (Cache's Spring Boot is 3.5.16, not the 3.3.5 `CLAUDE.md` says). **Open for W9:** M12's `PortBindings` and `curl` check on the demo host, and the M18/M19 repeats there; `StartedAt` during a restart in progress was not measured.

**Flags (open for the owner).** (1) The `http-status` probe is read as "any 2xx is healthy"; the schema does not define it. (2) Resume re-enters `restartAndGate` from the top, so a crash after the verify can restart a replica again. (3) A crash between a gated lifecycle step succeeding and its `VERIFY` could leave `gateProved` out of step with the gate (the same resume gap). (4) The plan named `PostRestartVerifyTest` in `engine`; it is `PostRestartVerifyIT` in the persistence package (package-private journal harness).
## S7 — Health, polled by the DP and persisted on change (OR-39)

### Task T7.1 — One reused SSH session per target, bounded, kept alive

**Wave:** W10. **Depends on:** T6.1 (and M8's session-sharing answer). **Parallel with:** T8.1, T9.1, T9.2, T10.1, T10.3.

**Files:** Create `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/ssh/{SshSessionPool,SshSessionPoolProperties}.java`;
Create `…/ssh/SshSessionPoolTest.java`.

OR-39's detail: one reused session per **target** (not per instance), a bounded session cap with idle close, a
bounded probe pool, keep-alive detecting a dropped session between polls → immediate reconnect and re-probe. The
pool serves the **poller in this plan** (plan 011's `MetricsCollector` shares it under M-Q3's per-target reserve, `OR-72`); the apply path keeps its per-operation connection (T6.1) so an apply never
queues behind a probe. The target's connection record is **re-read per session** (T2.1: `target_connection` is
mutable for rotation).

- [ ] **Step 0: Fill M12** — measure where the Cache management port is reachable **from the host** on the demo
  target. If it is not published to the host's loopback, the via-SSH probe cannot reach it and every such placement
  reads `not_exposed`; record that rather than working around it with `docker exec` (a tool the image may not have —
  `I1`).
- [x] **Step 1: Failing tests.** `oneSessionServesEveryPlacementOnATarget()`, `theCapClosesTheLeastRecentlyUsedIdleSession()`,
  `aDroppedSessionIsDetectedByKeepAliveAndReconnectedBeforeTheNextProbe()`, `aRotatedCredentialIsUsedByTheNextSession()`,
  `theApplyPathNeverBorrowsFromThePool()`.
- [x] **Steps 2–5:** fail → implement → `VERIFY dp-core` → mutation: reuse a session past a rotation → red.
- [x] **Step 6: Commit.** `feat(dp-core): a bounded per-target SSH session pool for polling (OR-39)`

**Results (T7.1, 2026-10-05; lane `p010/s7` `b1e0394`, integrated here as `66425d2c`).** `SshSessionPool`: one reused session per target (identity includes the credential, so a rotation opens a new one), a session cap with least-recently-used idle close, bounded probes, keep-alive reconnect; the apply path keeps its per-operation connection and never names the pool (an architectural test pins it). **OR-89 residual:** `sm_data` V012 section 1 adds `target_host_key`, unique per tenant and fingerprint among live targets; `TargetConnectionMain` now records every key type the host offers, via `SshHostKeyScanner` or the operator's explicit `SM_TARGET_HOST_KEY_FINGERPRINTS`, and refuses a host it cannot ask when no list is given (a registration-command change, flagged). Red first: tests against a stub gave 14 of 15 red. *Mutation proofs (each red, restored):* ignore the credential in session identity; evict MRU instead of LRU; never replace a dead session; retry fresh sessions; the apply-path class names the pool; drop `addHostKeys`; drop `tenant_id` from the key index. **Step 0 (M12) stays open:** the published-port shape on the demo target is a W9 measurement (M8 is filled in `docs/tasks/t7-health-measured-facts.md`).

### Task T7.2 — The poller, `placement_health` (V012), `getHealth`, `instance.health`

**Wave:** W10, same agent after T7.1. **Depends on:** T7.1, T6.4, T2.4 Step 3b (the second tenant list). ✅ **No
longer gated** — owner ruling 2026-09-29 (Q-T), `OR-49`: the poller visits every tenant
`TenantEnumerationRepository.tenantsWithActiveInstances()` yields (T2.4 Step 3b — called, never re-spelt), then runs
a per-organization scoped loop. OR-44's `tenantsWithNonTerminalWork()` alone would yield only tenants with an
in-flight apply (B4) — which is why the ruling was needed.

**Files:** Create `…/sm_data/V012__placement_health.sql`; Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/health/{HealthService,HealthView,PlacementHealthView}.java`;
Create `backend/dp-core/src/main/java/com/solace/sm/dp/health/{HealthPoller,HealthPollerProperties,ProbeResultMapper,HealthServiceImpl,HealthConfiguration}.java`,
`…/apply/persistence/JdbcPlacementHealthRepository.java`; Modify `DataPlaneConfiguration.java` (one import),
`…/dp/apply/events/ApplyEventEmitter.java` (`onHealthTransition`, already in plan 007 Task 5's interface),
`…/dp/config/ObservedStateServiceImpl.java` (replicas from health); Create
`backend/console-gateway/src/main/java/com/solace/sm/gateway/http/{HealthController,Health}.java`;
Create tests `…/health/{ProbeResultMapperTest,HealthPollerIT}.java`, `launcher/.../HealthEndpointsIT.java`.

`placement_health(tenant_id, placement_id, instance_id, state, checked_at, changed_at, state_version)` — the stamp
trigger fires **only when `state` changes** (`NEW.state IS DISTINCT FROM OLD.state`); a probe that confirms the same
state updates `checked_at` only (OR-39: "only changes are persisted"). Defaults: interval 30 s (per-binding
override), timeout 5 s, jitter, per-host backoff (`sm.dp.health.*`). `ProbeResultMapper` maps (**M3** first):
HTTP 200 + `status: UP` → `GREEN`; a `DOWN` → `RED`; any SSH/transport failure → `unreachable` (S0 point 11 —
ruled, owner ruling 2026-09-29 (S0-11): OR-39's `UNKNOWN` **is** this sub-state); connection refused on the declared port → `not_exposed`. A mapping M3 does not
settle (e.g. `OUT_OF_SERVICE`) is **flagged in the commit**, not guessed. The probe is the **placement's own**
(`health_kind`, `health_path`, `health_port` — T2.1, copied from the package at declare, M8); the DP reads no
package. A placement with no probe (`health_kind IS NULL`) is recorded `NOT_APPLICABLE` once and never probed (owner ruling 2026-10-06, `OR-101`, which corrects this text: the contract's value, because a missing probe is a package property and not a placement fault; it is not `not_exposed`, which the designer reads as RED). **The acquisition mode is the instance's** (owner rulings M-Q2 and M-Q2b, `OR-71`, which amend OR-39's per-target choice):
the poller reads `instance_state.acquisition_mode` once per instance per round, **one setting
for health and metrics, no automatic fallback**. Via SSH (`via-ssh`) the poller calls `HostProbe` (T6.1) on the pooled
session; direct HTTP (`direct-http`, `OR-82`) polls the **target's registered address**: it calls
`http://<target_connection.host>:<health_port><health_path>` from the DP, reading `target_connection.host` fresh with the placement's target on each round, so a re-target
automatically follows the new target's address. **Nothing about the address comes from the browser** (B2 holds for this path), so there is no
host to validate, no allow or deny policy to build and no carried-forward value to re-check. (Later, not v1: an admin-registered
list of extra direct addresses per target — `OR-82`.) A target with no usable address is a registration problem (T2.7), not a polling one. `HealthPoller` iterates the
second tenant list (`OR-49`); plan 011's `MetricsCollector` is a separate component and a fourth, named caller of that
list (`OR-72`) — **this task builds and calls no collector**. The apply engine's health gate is untouched — it
stays on `docker inspect` (OR-39; T6.1's M9 note).

- [x] **Step 1: Failing tests.**

```java
@Test void anUnchangedStateUpdatesCheckedAtAndStampsNothing() { }
@Test void aChangedStateStampsAndPublishesOneInstanceHealthAfterCommit() { }
@Test void aBrokenPathReadsUnreachableNotRed() { }                       // Review Focus 3
@Test void aServiceDyingBetweenPollsIsSeenWithinIntervalPlusTimeout() { }  // the honest bound, OR-39
@Test void backoffSpacesProbesToAFailingHostAndResetsOnSuccess() { }
@Test void theProbeUsesThePlacementsKindPathAndPort() { }               // M8
@Test void thePollerFollowsTheInstancesAcquisitionModeAndNeverFallsBack() { }   // OR-71: direct-http fails -> unreachable, no SSH retry; via-ssh never goes direct
@Test void aDirectHttpInstanceIsProbedAtTheTargetsRegisteredAddress() { }  // OR-82: target_connection.host + the placement's health_port/health_path; no host from the body
@Test void aReTargetedDirectHttpInstanceIsProbedAtTheNewTargetsAddress() { }  // OR-82: no carried-forward address
@Test void aPlacementWithNoProbeReadsNotApplicableAndIsNeverProbed() { }   // OR-101 (was ...NotExposed...)
@Test void anIdleTenantIsPolled() { }                                   // B4: no in-flight apply, still polled (the second tenant list, Q-T ruling)
@Test void thePollerReadsNoTenantOutsideTheTwoRuledLists() { }          // no third cross-tenant read
@Test void getHealthReturnsPerPlacementItemsEachWithItsOwnStateVersion() { }
@Test void getHealthIsReachableInTheProductionBoot() { }
```

- [x] **Steps 2–5:** fail → implement → `VERIFY dp-core`, `VERIFY console-gateway`, `VERIFY launcher` → mutations:
  stamp on every probe → the unchanged test red; map transport failure to `RED` → Review Focus 3 red.
- [x] **Step 6: Commit.** `feat: DP health polling persisted on change; getHealth served; instance.health emitted (OR-39)`

---

**Results (T7.2, 2026-10-05; lane `p010/s7` `9744ec6`, integrated here as `72a33e93`).** `sm_data` V012 section 2 adds `placement_health`, whose stamp trigger moves `state_version` and `changed_at` only when the state does (a confirmation moves `checked_at` alone). The poller visits the tenants of OR-49's second list (OR-72's `MetricsCollector` is the ruled fourth caller, built in plan 011), follows the INSTANCE's acquisition mode with no fallback (OR-71): `via-ssh` over T7.1's pooled session, `direct-http` at the target's registered address and the placement's health port and path (OR-82, nothing browser-supplied, re-read each round so a re-target follows), with per-target exponential backoff. `instance.health` is published after commit with the row's own stamp. `getHealth` is served per placement with its own state version; a placement not yet polled is declared in availability; the designer's replicas now come from the persisted health. The poller is on in production and off in every launcher IT but `HealthEndpointsIT` (`TwoSchemaTestDatabase.HEALTH_POLLER`).

*Lane verification.* Full `mvn -o -f backend/pom.xml verify` BUILD SUCCESS on the lane, 0 failures; `contracts/run-checks.sh --strict` passed. Red: with the V012 `placement_health` section withheld, the ITs ran 24 tests with 22 red; the Java classes' tests were written first but not run before implementing (the mutations carry them). `HealthEndpointsIT` boots the real application and polls a local HTTP stub direct-http: GREEN, then RED, then `not_exposed`, and serves `getHealth` over HTTP. *Mutation proofs (each red):* stamp on every probe; transport failure mapped to RED; direct-http falling back to SSH; no backoff; OR-44's tenant list instead of OR-49's; an event on unchanged state; unreachable mapped GREEN in the designer; gateway without the instance 404; organization taken from a request field.

**Integration.** The migration, table, constraint and function counts in `SmDataBootstrapIT`, `RefusalShapesIT` and `ApplyPersistenceConfigurationIT` were re-derived on top of T6.8's V011 and OR-96's V017 (13 migrations, 14 tables, 67 named design constraints); `HealthConfiguration` builds the S6 three-argument `TablePlacementCatalog`; `ObservedStateServiceImpl` takes both the ladder reader (T6.5) and the health service (T7.2).

**Flags (open for the owner).** (1) Three mapping choices M3 does not settle (`docs/tasks/t7-health-measured-facts.md`): Boot `UNKNOWN` reads RED; 401, 403 and 404 on the health path read `not_exposed`; `missing` and `bootstrap` are never produced. (2) `HealthEventEmitter` publishes `instance.health` beside `ApplyEventEmitter`; fold it in later (see T6.4). (3) `ENDPOINT-INVENTORY.md`'s `getHealth` served row is T12.1's.

**Results (OR-101 correction to T7.2, 2026-10-06; lane `p010/t105`).** A placement whose package declares no probe is now `NOT_APPLICABLE` end to end. `HealthPoller` records `NOT_APPLICABLE` (never probed) instead of `not_exposed`, and a no-probe row an earlier build wrote as `not_exposed` is corrected on the next round (one state change, one `instance.health`; after that nothing moves, so `checked_at` stays still). `getHealth` and `instance.health` serve and publish the persisted value unchanged, so both now say `NOT_APPLICABLE`. `ObservedStateServiceImpl` leaves such a placement out of the designer's replicas: it is a package property and not an observation, so it is neither RED nor a measured GREEN (a package with no probe at all already rolls up `NOT_APPLICABLE` by `healthRollupOf`'s own first rule). No migration (V012's CHECK already admits `NOT_APPLICABLE`), no contract edit (`console.yaml` already says `NOT_APPLICABLE` for this case), no new dependency. *Red first:* `ObservedStateServiceImplTest` 11 run / 1 red (`aPlacementWithNoDeclaredProbeIsNotAReplicaObservationAndSoNeverRed`: a RED replica for the no-probe placement); `HealthPollerIT` 18 run / 2 red (`aPlacementWithNoProbeReadsNotApplicableAndIsNeverProbed`, `aNoProbePlacementRecordedNotExposedBeforeOr101IsCorrectedOnTheNextRound`, both `expected NOT_APPLICABLE but was NOT_EXPOSED`); then green, 11/11 and 18/18 (with `ProbeResultMapperTest` 12/12 and `PlacementHealthIT` 7/7 unchanged). *Mutation proofs (each red, restored byte-for-byte, hashes checked):* poller writes `NOT_EXPOSED` for a no-probe placement, 2 red; designer keeps `NOT_APPLICABLE` as a replica, 1 red; poller records only when no row exists (no correction of old rows), 1 red. *Flags.* (1) `V007__instance_inventory.sql`'s comment and T2.1's SQL quote still say a no-probe placement "reads NOTFOUND not_exposed"; V007 is published and may not be edited, so the stale comment stays (it is a comment, not behaviour). (2) A refused port or a 401/403/404 on a declared probe still reads `not_exposed`: unchanged, those are placement findings. (3) `DemoDataPlaneController` already answers `NOT_APPLICABLE` for its no-probe case and needed no change.

**S6+S7 Opus review — dispositions (2026-10-06, branch `p010/s67`, review of `8beafed..bbe459f5`).** Three MAJOR, six MINOR, one of them an owner question.

- **MAJOR 1, fixed.** A re-drive still running past the 2-minute claim lease could have its bookkeeping closed by a second tick (`claimDue` ignored the request's state; `reconcile()` read `RUNNING` as "an operator got there first"). Two layers: `claimDue` claims only rows whose request is not `RUNNING`, and `reconcile()` returns `RETRY_SCHEDULED` for a `RUNNING` request. Test `reviewAReDriveInFlightPastTheClaimLeaseIsNotDroppedByASecondTick` (red first: `bookkeeping must stay open ==> expected: <null> but was: <...>`). Mutation: removing either layer alone leaves it green (each is sufficient); removing both turns it red.
- **MAJOR 2, fixed.** The age bound's origin is now the first `APPLY_INDETERMINATE` landing: `ReconciliationLedger.recordIndeterminateLanding` (abstract, no default) is called from `OutcomeService.land` in the landing transaction and keeps an existing row, so a re-drive cut off again does not move it. Rows the poll or startup sweep creates (a landing the ledger did not witness) still use their own clock. V011's comment corrected (V011 is unpushed). Test `reviewTheRedriveAgeRunsFromTheLandingNotFromTheFirstSighting` (red first: `expected: <GAVE_UP> but was: <REDRIVEN>`); mutation: with the landing write removed it is red. `ReconcilerFixture` now gives `OutcomeService` the shared `TestClock`, and a switch (`ledgerWitnessesLandings`) lets two tests (`aRunDueTickAlsoPicksUpAnIndeterminateRequestNobodyEnqueued`, `startupRecoveryEnqueuesIndeterminateRowsAndDoesNotTouchAHost`) model a landing nobody recorded, so they still test what they name. `BE-D34`'s "from the first sighting" now reads as "the first landing, where the data plane witnessed it"; the decision text is left as written.
- **MAJOR 3, fixed.** `console.yaml` `submitApply` declares the served `422 VALIDATION_FAILED` (a generation never staged); `contracts/run-checks.sh --strict` passes.
- **MINOR (`Location`), fixed.** Both 202s (`submitApply`, `stopInstance`) declare the `Location` header the gateway sends.
- **MINOR (V017 order), recorded, no code.** V017 lands before V013-V016, and Flyway `outOfOrder` is off. **Every persistent `sm_data` that has applied V017 from this branch (the E2E host PostgreSQL included) must be recreated before V013-V016 merge**, or the application will not boot against it. Renumbering V017 while it is unpublished is the alternative and is the owner's call.
- **MINOR (pre-image not hash-checked), accepted residual.** T6.8's "pre-image no longer matches its own recorded hash" cannot be checked: no hash is recorded when the pre-image is captured. If both the pre-image and the config are altered to the same content, the request reads `PRIOR` and is re-applied over content SM did not write. Closing it needs the keyed hash recorded at capture (evidence or sidecar), which is a change to the capture path and T6.1's transport; not done here.
- **MINOR (two direct-http probes), deferred.** The gate's `DirectHttpProbe` and the poller's `JdkDirectHttpProbe` classify a non-resolving name differently (7 versus unreachable). Both read as not-healthy to the gate, so the gate's verdict is unaffected; unifying them crosses the `dp.apply.runtime`/`dp.health` package line and the gate's curl-exit-code vocabulary, so it is not a cheap fix.
- **MINOR (`beginRedrive` bound re-check), deferred.** The age and count bounds are decided in phase 1 with a `now` taken before the host observation, so the 1 h bound can overshoot by the observation's length (SSH timeouts). Re-checking under the lock means passing the bounds into `beginRedrive`; the second concurrent tick is already stopped by the `state_version` check.
- **MINOR, needsOwner, left as is.** A placement whose package declares no probe is persisted `not_exposed` (T7.2's text) while `console.yaml`'s rollup says `NOT_APPLICABLE`; the designer maps `not_exposed` to RED. Which value a no-probe placement carries is the owner's ruling. **Ruled 2026-10-06, `OR-101`: `NOT_APPLICABLE`; built by lane `p010/t105` (Results below).**

### OR-102 follow-up — the retained pre-image carries a recorded keyed fingerprint

**What it closes.** The S6+S7 review's accepted residual: nothing recorded a hash of the retained pre-apply copy, so a copy altered before the assessment read it was hashed *by the assessment*, matched itself at restore, and went live (and the reconciler would read a file equal to that copy as `PRIOR` and re-apply over it). Owner ruling `OR-102`.

**What was built (no migration; no `sm_data` change).** The pre-image is a file on the host (a bind-mounted directory for the Docker adapter, an SFTP path for the SSH adapter), so the fingerprint is kept beside it rather than in a table the adapters have no datasource to reach. `PreImageFingerprint` (pure, no I/O, shared by both adapters) records, under `OR-32`'s per-tenant `hmacsha256v<n>` hasher: the whole-file hash; a keyed digest per **declared non-secret** dotted path (or of its absence); a keyed digest of the remainder of the parsed document (so "other content changed" is told apart from a path-only change); and a keyed **seal** over those plus the file name and generation, so a record cannot be edited, or carried between generations or files, without the DP key. It carries paths and digests, never a value, and is handed only non-secret paths, so no secret-flagged path is ever digested on its own (`I6`). The declared paths travel on `RenderedConfig.nonSecretPaths()`: `ConfigGenerationRenderedConfigSource` lists exactly the rendered entries that carry a `value` (never a `secretRef`), and `SshDockerConfigEditor` lists the allowlisted fields it edited. At retention (`ConfigFileStore.capturePreImage`, `SshDockerRuntimeAdapter.capturePreImage`) the record is placed durably **before** the pre-image. Before the pre-image is used (`assessRollback` in both adapters, and `restore` in both, via `ConfigFileStore.checkPreImage` / `PreImageFingerprint.check`) it is re-hashed; a mismatch refuses to use it and reports `rollback.preimage.altered` with a `PreImageChange` (the declared non-secret paths that differ, sorted, plus an "other content changed" flag for any other difference, including a secret-flagged path, a comment, or a file that no longer parses). A copy with no trustworthy record (missing, garbled, wrong tenant key, another generation) is refused as `rollback.preimage.unrecorded` with no claim about its content. The engine reports the change (apply id, tenant, paths only) at WARN from `ApplyExecutor` on all three places the pre-image is used: the exhaustion assessment, the restore refusal (including resume), and the reconciler's `observeHost` (where a changed copy is no longer a prior, so a file that matches only it reads foreign and is never overwritten). `RollbackAssessment.Unknowable` gained an optional `change`, and `RuntimeAdapterException` an optional `preImageChange`; no seam operation changed.

**Tests.** `PreImageFingerprintTest` (16), `ConfigFileStoreTest` (7 new, 3 placement assertions updated), `SshDockerRuntimeAdapterContractTest` (6 new), `SshDockerConfigEditorTest` (1 new), `DockerLifecycleIT` (2 new), `ConfigGenerationRenderedConfigSourceIT` (1 new), `ManualApplyIT` (3 new), `IndeterminateReconcilerIT` (1 new).

**Results (OR-102, 2026-10-06; lane `p010/or102`).** *Red first:* `PreImageFingerprintTest` 16 errors (stub); `ConfigFileStoreTest` 5 failures + 2 errors against the old store; `SshDockerRuntimeAdapterContractTest` 4 failures + 1 error; `DockerLifecycleIT` 2 failures (adapter assess change withheld); `SshDockerConfigEditorTest` 1 failure; `ManualApplyIT` 2 failures. `ConfigGenerationRenderedConfigSourceIT`'s new test and `IndeterminateReconcilerIT`'s were not seen red before implementing (the first because surefire failed first in the same run, the second because the engine report was written in the same step as `ManualApplyIT`'s); both are pinned by the mutations below instead. *Mutation proofs (each red, restored byte-for-byte):* restore skips the fingerprint check (2 failures in `ConfigFileStoreTest`); the seal is never verified (2 failures); the remainder digest ignored (1); SSH capture records no fingerprint (6 failures + 3 errors); Docker assess skips the check (2 failures); secret-flagged paths listed as declared (1); `observeHost` does not report (1); the restore refusal not reported (1 failure in `ManualApplyIT`). *Deviations and flags.* (1) **No `V014`**: the narrowest persistence that keeps the adapter seam unchanged is a keyed record beside the copy; a table would have needed a datasource in the adapters or a new seam parameter. (2) **What a host-side actor can still do** is delete the record (read as `unrecorded`, refused, never trusted) or move a valid pre-image and record pair from another generation or file into place (refused: the seal binds the file name and generation). A DP-side record would also survive deletion; the owner may want that later. (3) **Pre-images retained by an earlier build have no record** and are now refused as `rollback.preimage.unrecorded` (fail closed): an apply stranded across the upgrade needs the operator's `resolveIndeterminate`. (4) **Where the paths are reported is the log, not the API**: `apply_attempt.error_class` is a dotted token by design and the console contract carries no field for paths, so surfacing them to the operator in the UI needs a contract change (not made; out of this lane's Files). (5) The remainder digest covers secret-flagged content exactly as `OR-32`'s whole-file hash does (`OR-32` names `I6`); no secret path is digested on its own. **Ruled `OR-113` (5), 2026-10-07: kept as built.** The remainder digest includes secret-flagged values on purpose, so a secret edit in the retained pre-image reads as "other content changed"; it adds no exposure because the digest is keyed and sits beside a pre-image that already holds the plaintext. The "What was built" paragraph's "no secret-flagged path is ever digested on its own" stands: nothing digests a secret path by name; the secret's value is inside the remainder digest. Evidence: `PreImageFingerprintTest.aSecretEditAlongsideADeclaredPathChangeIsStillOtherContentChanged` (added under `OR-113`; red when the secret path is stripped from the remainder, green restored; the older secret-only test passes even then, through the "nothing else explains it" fallback, so it does not pin the digest). (6) A `yaml`-typed non-secret path whose value embeds a secret is open item 22's territory and is not handled here. Full reactor build not run, per `OR-70`; targeted `dp-core` tests above were run with `-am`.

### OR-105 follow-ups

Four residuals the S6+S7 review deferred, scheduled by `OR-105` and executed on lane `p010/fu`, one commit each.

**Results (b, 2026-10-06) - `beginRedrive` re-checks the bounds under the scope lock.** `OutcomeService.beginRedrive` takes a `RedriveBounds(maxAge, maxRedrives)` (the 3-argument form is gone, so no caller can skip them) and, after the existing state, stamp and later-request checks and before the walk to `RUNNING`, reads the request's bookkeeping through a new `ReconciliationLedger.redriveBook` (the landing instant and the re-drives spent, read in the same transaction) and returns `TOO_LATE` or `EXHAUSTED` without changing anything. The age is counted from the landing (`first_indeterminate_at`, OR-105's "from the landing"), against the engine's clock read inside the lock. `IndeterminateReconciler.redrive` maps the two results to the same classifiers the decision uses (`reconcile.redrive.too.late`, `reconcile.redrive.exhausted`). A request with no bookkeeping row (the journal-only engine suites' `ReconciliationLedger.none()`) has no bound to check. Tests first, in `IndeterminateReconcilerIT`: a host probe that advances the clock 61 minutes during the observation (the reconciler's `now` is stale; it must give up, spend nothing, touch no host), a re-drive spent by another tick during the observation, and the two refusals at the seam. Red with the check disabled: 3 of 39 failed (`REDRIVEN` and `STARTED` where `GAVE_UP`, `TOO_LATE` and `EXHAUSTED` were expected); green with it: `IndeterminateReconcilerIT` 39 and `OutcomeTransitionsIT` 18, 0 failures. Mutation proofs, each restored byte-for-byte: count check removed, 2 red (the stale-decision test and the seam test); age check removed, 2 red (the slow-observation test and the seam test).

**Results (c, 2026-10-06) - one event emitter.** `onHealthTransition(TenantId, UUID instanceId, UUID placementId, HealthState, long stateVersion)` moved verbatim into `ApplyEventEmitter` (the home plan 010 T7.2 named); `dp.health.HealthEventEmitter` and its bean are deleted, `HealthRecorder` and `HealthConfiguration` take the one `ApplyEventEmitter` bean (`ApplyEventsConfiguration`), and `HealthPollerIT` builds its recorder over an `ApplyEventEmitter`. The wire event is unchanged: the existing `HealthPollerIT` event assertions (`instance.health`, `dp-core`, placement entity, `{state: RED}`, the row's own stamp, published only after commit) pass untouched, and a new `ApplyEventEmitterTest` case pins the same shape under Spring's real after-commit dispatch and through `NotifyEventShapes.validate`. Red: the new test did not compile (`cannot find symbol onHealthTransition` on `ApplyEventEmitter`). Green: `ApplyEventEmitterTest` 24, `DataPlaneConfigurationTest` 3, `HealthPollerIT` and the apply-event ITs (`ApplyEventsLandingIT`, `ApplyOutcomePublishIT`) 27 failsafe tests, 0 failures. Mutation proof, restored byte-for-byte: renaming the payload key `state` to `status` turns the new test red (`payload.state is required for this event`).

**Results (d, 2026-10-06) - one direct-http probe.** `dp.health.DirectHttpProbe` and `JdkDirectHttpProbe` are deleted; the poller runs `dp.apply.runtime.ssh.DirectHttpProbe` through `DirectHttpObservations` (new, package-private in `dp.health`), which maps its `ProbeResult` to the poller's `Observation`: exit 0 `Answered`; 7 `REFUSED`; `EXIT_UNREACHABLE` (6) `UNREACHABLE`; 28 and any other exit `SILENT`; `http.probe.output.too.large` `SILENT`; any other adapter failure `UNREACHABLE`; a thread interrupt during the probe `DEFERRED` (a shutdown is not persisted as a red service). A grammar refusal (`http.probe.refused`) is not mapped and propagates: the poller already logged and skipped a placement whose declaration cannot be probed (it threw `IllegalArgumentException` before), so nothing new is recorded for it. **One change to the apply probe, because the poller's measured behaviour has to survive the merge:** the JDK client throws the same message-less `ConnectException` for a refused port, an unresolved name and a missing route, so the apply probe mapped all three to 7 while the poller's probe told them apart (a refusal is `not_exposed`, the other two `unreachable`). The apply probe now resolves the name and makes the plain TCP connect first (the poller probe's technique), and reports a refusal as 7 and everything that cannot be reached (unresolved, no route, connect timeout) as the new `DirectHttpProbe.EXIT_UNREACHABLE = 6` (curl's value for an unresolved host). The apply path's health gate treats every non-zero exit as `UNREADABLE`, so its verdict is unchanged, and the existing `DirectHttpProbeTest` cases (200/503 answers, refusal 7, stalled server 28, redirect never followed, oversize, grammar) pass untouched. The poller probe's real-socket cases moved to `DirectHttpObservationsTest` over the real apply probe, with the mapping table pinned over a scripted probe; `HealthPollerIT` now scripts the apply `DirectHttpProbe` (a `ProbeResult` instead of an `Observation`). Red: the new and moved tests did not compile (no `DirectHttpObservations`, no `EXIT_UNREACHABLE`). Green: `DirectHttpProbeTest` 8, `DirectHttpObservationsTest` 13, `ProbeBackedHealthTest` 12, `ProbeResultMapperTest` 12, `SshDockerRuntimeAdapterContractTest` 27, `DataPlaneConfigurationTest` 3, `HealthPollerIT` 17, 0 failures. Mutation proofs, each restored byte-for-byte: an unresolved name reported as 7, 2 red (probe and mapping); 7 mapped to `SILENT`, 2 red; the interrupt check removed, 1 red; `EXIT_UNREACHABLE` mapped to `SILENT`, 2 red.

**Results (e, 2026-10-06) - published `sm_data` and `sm_metrics` migrations are frozen.** `backend/scripts/check-frozen-migrations.py` now walks three directories (`sm_control` under `cp-core`, `sm_data` and `sm_metrics` under `dp-core`) in both modes: an anchor commit freezes every migration and every manifest line each directory held at that commit, and base mode compares all three; messages name the schema (`sm_data/V005__apply_attempt.sql`). `CHECKSUMS.sha256` was created for `sm_data` (V001-V013, 13 lines) and `sm_metrics` (V001), in `sm_control`'s `<sha256>  <file>` format, and `DataPlaneMigrationChecksumsTest` in `dp-core` verifies them at build time the way `cp-core`'s `MigrationChecksumsTest` does (pinned bytes match, none missing, none on disk unpinned). The origin tip `f0ade57c5939ac0eb53ce48568b13c8d21e651c5` (contains `sm_data` V001-V013, `sm_metrics` V001 and `sm_control` V001-V014; it is `origin/integration/dp-remote-demo`) is appended to `frozen-migration-anchors.txt` with a comment; the worktree's three directories are byte-identical to it. `contracts/run-checks.sh` step 12 and the pre-push hook needed no logic change (step 12's title names all three schemas); step 12b's self-test gained cases (i)-(m) and a second hook case. Red: the new self-test cases failed 7 of 17 (a rewritten, deleted or anchored `sm_data` or `sm_metrics` migration passed the check), and `DataPlaneMigrationChecksumsTest` failed 4 of 4 (no manifest). Green: self-test `a-m` all pass; `DataPlaneMigrationChecksumsTest` 4; `SmDataBootstrapIT` and the metrics ITs (21 failsafe tests) still migrate with the manifests on the classpath; `./contracts/run-checks.sh --strict` ALL CHECKS PASSED (with a temporary `node_modules` symlink, removed). Planted-violation proofs on the real repository, each restored byte-for-byte: an appended line in `sm_data` V005 and in `sm_metrics` V001 makes the checker exit 1 naming the file and the anchors that froze it; an edited `sm_data` V003 and an unpinned `sm_metrics` V002 each turn `DataPlaneMigrationChecksumsTest` red. **Integration note:** any lane that adds an `sm_data` or `sm_metrics` migration (and the integrator's renumbering) must add its manifest line, or the build test fails - the same rule `sm_control` has always had.

## S8 — Operator rollback (OR-42 — flagged: names `B/D12` 🔒)

### Task T8.1 — Copy-forward rollback

**Wave:** W10. **Depends on:** T6.3, T5.2. **Parallel with:** S7, T9.x, T10.1 (see the W10 note on `ConfigRecordService`).

**Files:** Modify `backend/dp-api/src/main/java/com/solace/sm/dp/api/config/ConfigRecordService.java` (add
`copyForward(org, instance, fromGeneration, GenerationOrigin, actor)`), `…/dp/config/ConfigRecordServiceImpl.java`;
Create `…/dp/config/RollbackTargets.java`, `backend/dp-api/src/main/java/com/solace/sm/dp/api/apply/RollbackCommand.java`;
Modify `…/apply/service/ApplyServiceImpl.java`, `console-gateway/.../ApplyController.java` (the rollback handler);
Create tests `…/config/RollbackTargetsIT.java`, `launcher/.../RollbackEndpointIT.java`.

Eligible targets (OR-42, verbatim): generations **actually written to that placement** — the placement's request for
that generation reached `STAGED`, `APPLIED`, or `SUPERSEDED` after staging. ⚠️ `INDETERMINATE_RESOLVED` with outcome
`applied` also raises the watermark (`V003:11-12`) but **the ruling does not list it**; this plan excludes it and
flags it for the owner rather than adding it. With `applyId`: the target is the highest eligible generation **below**
that apply's generation on the placement; with `generation` (the landed contract's name — T0.1 point 12): it must be eligible, else `409 CONFLICT`. ✅ **`placementId` is required** on `rollbackApply` (owner Step 7, batch 1, `OR-60`): a body without it is refused `400` before anything is read, as v1 refuses instance-wide apply (`BE-D23`); the eligibility rule is evaluated for **that placement**. The new
generation copies the target's `component_selection`, `draft_values` and `rendered_entries` with `origin =
'rollback'`, `derived_from_generation`; then a normal submit (`config.rollback`, + `instance.restart` for a
restarting strategy — `rolling-health-gated`, generic and package-driven, owner ruling 2026-09-29 (S0-10)) with
`BE-D22`'s pre-image safety net. The only-goes-up fence
is untouched: the copy has a new, higher generation number. ⛔ **Two transactions** (Global Constraints, m3): the
copy-forward commits (it holds `instance_state` only), then admission takes `apply_scope → instance_state`. A crash
between leaves a rollback generation with no apply; the operator's retry copies the same content, which OR-33 guard 1
returns as `unchanged` — so a retry never mints a second rollback generation.

- [x] **Step 1: Failing tests.** `rollbackMintsAHigherGenerationWithTheTargetsContent()`,
  `aGenerationNeverWrittenToThatPlacementIsNotEligible()`, `resolvedAppliedIsExcludedAsTheRulingListsOnlyThree()`,
  `rollbackRequiresConfigRollback()`, `rollbackUnderManualEndsStaged()`, `aRollbackWithoutAPlacementIdIsRefused()` (`OR-60`), `rollbackIsReachableInTheProductionBoot()`.
- [x] **Steps 2–5:** fail → implement → `VERIFY dp-core`, `VERIFY launcher` → mutation: re-admit the old generation
  number → `GENERATION_NOT_ABOVE_WATERMARK` (`AdmissionService.java:103-109`) and the first test red.
- [x] **Step 6: Commit.** `feat: operator rollback as copy-forward (OR-42 — flagged: B/D12 reading)`

**Results (T8.1, 2026-10-06, lane `p010/s8`).** Committed as `feat: operator rollback as copy-forward (OR-42 — flagged: B/D12 reading)`.
*Shape.* `ConfigRecordService.copyForward(org, instance, fromGeneration, GenerationOrigin, actor)` is a `REQUIRES_NEW`
transaction that locks `instance_state`, reads the source row (`ConfigGenerationRows.find`, new), rebuilds a
`StageCommand` from it (`CanonicalContent.renderedFrom` types the stored rendered set back; a secret stays a `secretRef`,
I6) and runs the SAME tail as `stage` (guard 1, insert `last + 1`, advance the head, prune), with `origin`,
`derived_from_generation`, no layout. `RollbackTargets` (rule) over `RollbackTargetRows` (port; JDBC in
`JdbcRollbackTargetRepository`) holds the eligibility rule: an `apply` request for that placement in `STAGED`, `APPLIED` or
`SUPERSEDED` (V006 allows `SUPERSEDED` only from `STAGED`, so it was staged). `ApplyService.rollbackApply` +
`RollbackCommand` (dp-api), `ApplyServiceImpl.rollbackApply` (strategy served? placement live? executor present? target
resolved? copy forward, then `admit(..., MUST_BE_STAGED)` of the COPY), `ApplyRefusedException.Reason.ROLLBACK_TARGET_NOT_ELIGIBLE`
(`409`, fixed detail, echoes nothing), `RollbackRequest`, `ApplyController.rollbackApply`, `ApplyOrchestrator.rollback` (501
before any other verb, `instance.restart` for a restarting strategy, then the 404s). `ApplyServiceImpl`'s constructor took two
collaborators; `ApplyServiceIT`, `ApplyServiceGateProvedTest`, the three `ConfigRecordService` fakes and `FakeApply` were updated.
*Red first, and where it was not.* `RollbackTargetsIT` (12 tests, dp-core) was run against a stub that threw: **12 of 12 red**
(5 failures, 7 errors), then green 12/12. The service and gateway layers were written after the rule and the copy; there the
red evidence is the mutations below, not a stub run (`ApplyServiceRollbackIT` 7, `ApplyControllerTest` +10 rollback cases =
39, `RollbackEndpointIT` 9). *Mutations, each red then restored byte-for-byte (`cmp`):* (1) apply the TARGET's number instead of
the copy (the plan's named mutation) → `GENERATION_NOT_ABOVE_WATERMARK`: `ApplyServiceRollbackIT` 4 of 7 red and
`RollbackEndpointIT` `rollbackMintsAHigherGenerationWithTheTargetsContent` red (409) plus the applyId case; (2) drop
`SUPERSEDED` from the eligible set → 6 red (`RollbackTargetsIT` 2, `ApplyServiceRollbackIT` 4); (3) add `INDETERMINATE_RESOLVED` →
`resolvedAppliedIsExcludedAsTheRulingListsOnlyThree` red; (4) eligibility ignores the placement → 2 red; (5) rollback skips
the `instance.restart` check → `aRestartingStrategyAlsoRequiresInstanceRestart…` red.
*Counts.* dp-core targeted: `RollbackTargetsIT` 12, `ApplyServiceRollbackIT` 7, with `ApplyServiceIT` 18,
`ConfigRecordServiceIT` 30, `ConfigLadderIT` 13, `ConfigGenerationRenderedConfigSourceIT` 7 unchanged and green; launcher
`RollbackEndpointIT` 9; all reactor unit tests (`*Test`, which include `arch-tests`, `SchemaMirrorLockTest` and
`ApplyControllerTest`) green.
*Flags (for the owner).* (a) **`B/D12` 🔒** — "re-apply a prior generation" is read as re-applying its content (OR-42's own
flag); built as ruled. (b) **`applyId` wording.** The plan and this build read it as "undo this apply" — the target is the
highest eligible generation BELOW that apply's generation; `console.yaml`'s description says "the earlier apply request whose
generation to copy forward", which reads as that apply's OWN generation. The plan is authoritative and the contract is not
in this task's Files, so it is unedited; the description needs a one-line correction or the rule needs ruling.
(c) **`INDETERMINATE_RESOLVED` `applied`** stays excluded, as the plan says; the owner may list it. (d) **A rollback restores
references, not secret values** (OR-33/I6: history holds a `secretRef`): a secret whose value was replaced since the target
generation renders with the CURRENT value. (e) **Rolling back to content equal to the latest generation** returns it as
`unchanged`; if that generation was already applied the apply is `409` by the watermark — honest, but the operator sees a
conflict rather than "nothing to do". (f) The contract closes the body (`additionalProperties: false`); the gateway ignores a
stray key, exactly as `submitApply` does. (g) `RollbackTargetsIT` lives in `dp.apply.persistence`, not `dp.config`
(the engine harness is package-private; same reason as `ConfigLadderIT`). (h) **`OR-102` (the pre-image fingerprint) is NOT
built here**: it is registered with "built in the S8 lane" but is not a step of T8.1 or T10.2 and needs a migration and an
adapter change; it is reported, not done.

---

## S9 — Logs and log levels (OR-43)

### Task T9.1 — `readLogs` served

**Wave:** W10. **Depends on:** T6.1, T4.4. **Parallel with:** S7, S8, T10.x.

**Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/logs/{LogService,LogPageView,LogLineView}.java`;
Create `backend/dp-core/src/main/java/com/solace/sm/dp/logs/{LogServiceImpl,DockerLogLineParser,LogConfiguration}.java`;
Create `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/{LogsController,LogPage}.java`;
Create tests `…/logs/DockerLogLineParserTest.java`, `launcher/.../LogsEndpointsIT.java`.

A bounded **read** on the request thread (legal, Global Constraints) through the existing transport's
`logs(p, tail, after, before)` (`SshjDockerTransport.java:203-207`; **M11** first). `component` must equal the
instance's package component type, else `404`. Lines carry `at`, `message`, and `level` only when parseable;
`LogPage` carries no `stateVersion` (OR-38); an unreachable host → `200` with an empty page and `availability`
`unavailable` + reason — ⛔ never an empty page presented as "no logs". ⛔ A log line is not redacted by SM (it is
the MI's own output) — the endpoint is `logs.read`, and that trade-off is stated in the endpoint's description.

- [x] **Steps:** failing tests (`boundedByLimitAndTimeWindow`, `aSecondLivePlacementRequiresPlacementId`,
  `unreachableIsDeclaredNotEmpty`, `reachableInTheProductionBoot`) → implement → `VERIFY …` → mutation (drop the
  bound) → commit `feat: readLogs served — bounded docker logs over SSH`.

**Results (T9.1, 2026-10-06, on `p010/t91` off `integration/dp-remote-demo`).** Committed `feat: readLogs served — bounded docker logs over SSH`.
Files: `dp-api/.../logs/{LogService,LogPageView,LogLineView}`; `dp-core/.../logs/{LogServiceImpl,DockerLogLineParser,LogConfiguration}`;
`dp-core/.../apply/runtime/ssh/HostLogs.java` (new public read seam, see deviations) and `SshjDockerTransport` (`implements HostLogs`,
`logsCommand`, `read`); `console-gateway/.../http/{LogsController,LogPage}`; one `@Import` line in `DataPlaneConfiguration`; tests below;
`docs/tasks/t9-1-measured-facts.md` (M11). **Red first:** with only the tests written, dp-core failed to compile all four test classes
(`DockerLogLineParserTest` 10, `LogServiceImplTest` 9, `SshjDockerTransportLogsCommandTest` 6, the aggregator test's `LogConfiguration`
reference) and the whole new `dp-api.logs` surface was absent; after the implementation they ran 28/28 green first time. Gateway:
`LogsControllerTest` 14 and `SchemaMirrorLockTest`'s new `readLogs...` case (35 total) green; `ProductionAuthenticationIT`'s route set now
carries `readLogs` (4/4); `LogsEndpointsIT` 7/7 over real HTTP, PostgreSQL and the real transport (a closed loopback port, nothing leaves
the machine); gateway module 339/339; `PlaneBoundaryTest`, `ApplyPathBoundaryTest`, `CompositionBoundaryTest` and the ssh package tests
green. **Mutations, each red then restored byte for byte (`cmp`):** (a) `DockerLogLineParser.page` drops the limit trim -> `boundedByLimitAndTimeWindow`
and `theNewestLimitLinesComeBackOldestFirst` red (the task's named "drop the bound"); (b) `--tail` also sent with a `before` -> 2 command tests red;
(b2) `2>&1` dropped -> 4 red; (e) an adapter failure answered as an empty live page -> `unreachableIsDeclaredNotEmpty` and
`anOversizedWindowIsToldApartFromAnUnreachableHost` red; (c) component check disabled -> 2 red; (d) ambiguous placement silently takes the
first -> `aSecondLivePlacementRequiresPlacementId` red; (f) the `limit` bound dropped -> `theLimitDefaultsToTwoHundredAndIsBoundedToOneThousand` red;
`LogConfiguration` out of the aggregator -> `theAggregatorImportsEveryDataPlaneConfiguration` red.
**M11 measured (docs/tasks/t9-1-measured-facts.md) and it overturned the plan's premise that the existing `logs(p, tail, after, before)` could be used
as is:** on Docker 28.5.1 `--tail` is applied BEFORE `--until` (so `--until X --tail N` answers nothing unless the window is in the last N lines)
and the container's stderr is on the exec's stderr (the transport reads stdout only, and `--tail` counts both streams). The new `logsCommand` sends
no tail with a `before` (the host returns the window, capped at 512 KiB, and the data plane keeps the newest `limit`) and appends `2>&1`.
**Deviations:** (1) a new public `HostLogs` interface in `...ssh` (same shape as `HostProbe`), because `SshDockerTransport` is package-private and the
plan's file list names none in that package; the demo's `logs(...)` is left untouched (it has both measured defects; the demo is deleted by plan 009).
(2) The component check lives in `LogsController` (the gateway holds the catalog; the data plane does not), using `SolutionCatalog` + `DesignerCodec`.
(3) `LogsController`'s `limit`/`before`/`after` are parsed there (`400` on a bad value or a window with no interior); `limit` 1..1000, default 200.
(4) A read takes one of `sm.dp.logs.max-concurrent-reads` (8) slots, waiting at most `sm.dp.logs.acquire-timeout` (2s), else a declared `unavailable` page - not in
the plan; it stops a browser fanning out unbounded SSH sessions to a customer host. (5) `DataPlaneConfigurationTest` and `ProductionAuthenticationIT`'s route set
gained their one line each. **Flags:** (i) a `before` over a log larger than 512 KiB answers a declared `unavailable` (too large), not lines - paging backwards
through a very large log needs a different mechanism (a host-side `tail`, which the closed command set deliberately avoids); the owner may want to
rule on it. (ii) `component` is validated against the package's component TYPES only; in v1 a placement is one container, so a multi-component
package's second component would read the same container's logs - nothing in the placement says which component it hosts. (iii) the served-lines path
against a real host is not exercised in this lane (no SSH server in the test classpath); it is plan 010 T6.6's. (iv) M11's row in the plan's table is
not edited here (the lane edits only its own task); the integrator marks it filled from `docs/tasks/t9-1-measured-facts.md`. (v) the line `level` is read only
from a Spring Boot / logback shaped line; any other shape has none. No new third-party dependency, no migration, no `contracts/` change.

### Task T9.2 — D-LOG: live log levels without a network-exposed write surface (design, docs only)

**Wave:** W10. **Depends on:** M2, M12, M13. **Parallel with:** everything (docs only).

**Files:** Create `docs/design/backend/log-levels.md`; draft `«next free BE-D»` rows.

The critic's F12 stands measured: Cache has **no Spring Security** (v2 paper G9), so an exposed `loggers` endpoint
is an unauthenticated runtime write surface. OR-43 answers it with "management endpoints bound to 127.0.0.1 by
SM-written config and reached only via SSH" — and the design must now prove that answer holds **under Docker**:

1. **Reachability (M12).** `management.server.address=127.0.0.1` *inside a container* binds the container's
   loopback, which the host cannot reach through a published port. The options **consistent with OR-43** ("bound to
   127.0.0.1 … reached only via SSH") and Q11 ("SM never exposes the loggers port to the network"): **(a)** the
   customer's launch spec runs the container in the host's network namespace, so the container's loopback **is** the
   host's and a literal `127.0.0.1` bind is reachable by `curl` over SSH and by nothing else — a deployment
   prerequisite SM states and preflight **checks** (M12 measures how), never one it creates; **(b)** otherwise P1 is
   unavailable for that placement and it gets P2 only (like direct-HTTP placements). ⛔ **Struck (M11):** the earlier
   draft's option of writing `management.server.address` to the container's *network* interface and publishing the
   port to the host's loopback — it binds the unauthenticated loggers endpoint to an interface **every container on
   that Docker network can reach**, which is a network exposure OR-43 and Q11 forbid. If measurement shows neither
   (a) nor (b) is acceptable to the owner, that is a request to change OR-43, routed — not a design choice here. ⛔ No
   option may require a tool inside the image (`I1`).
2. **The SM-owned overlay.** The properties SM writes (`management.server.*`,
   `management.endpoints.web.exposure.include`, and in P2 `logging.level.<loggerPrefix>`) are **SM overlay entries**
   added at render, not package fields — so OR-34's "refuse an undeclared key" (a rule about *client-supplied*
   keys) is intact. Record that reading and flag it.
3. **The journaled operation.** OR-43 says the live SET is "an audited durable engine operation". Recommend a new
   `apply_request.operation = 'set-log-level'` with a `log_level_target jsonb` (logger, level, placement), a
   `RuntimeAdapter.setLogLevel(TargetReplica, String logger, String level)` operation added to the contract kit
   (`AbstractRuntimeAdapterContractTest`, `PlaneBoundaryTest.java:224-228` inventory unchanged), and the CHECKs it
   touches (`op_known`, `state_fits_operation`, `lifecycle_has_strategy`) in `V013` (renumbered 2026-09-29). ⚠️ The D10 amendment §1.4 names
   `apply_request` as the audit record for three verbs only (`config.apply`, `config.rollback`, `instance.restart`);
   `logs.setLevel` would be a fourth — **flag it** for the owner.
4. **"Runtime override active".** A live override is lost on restart; the design says how SM knows (a container
   start observed after the override — T7.2's observation) and what the UI is told.
5. **GET.** `getLogLevel` reads live via SSH (a bounded read), falls back to the configured level with
   `availability` degraded.
6. **Generic, not Cache-coded (owner direction with the S0-10 ruling).** The loggers endpoint and the overlay keys
   are Spring Boot Actuator conventions, not Cache facts. D-LOG says how a **package declares** that its MI speaks
   them (today's `logs` block — `logfile`, `baselineExposure`, `requiresConfig` — declares no loggers endpoint,
   measured 2026-09-29), and routes that declaration as a reviewed schema change if one is needed; a package that
   declares none gets neither P1 nor P2 and says so in `availability`. ⛔ No branch on the solution name.

- [ ] **Steps:** fill M2/M12/M13 → write → links resolve → commit `docs(backend): D-LOG — live-first log levels, reachable only through SSH (OR-43)`.

### Task T9.3 — P1: live log levels

**Wave:** W11. **Depends on:** T9.2 merged (and the owner's answer to its flags). **Parallel with:** T10.2, T10.4, T10.5, T11.2.

**Files (as D-LOG decides; the default below assumes its recommendation):** Create `…/sm_data/V013__log_level_operation.sql`;
Modify `…/apply/model/{Operation,ApplyCommand}.java`, `…/apply/engine/ApplyExecutor.java`,
`…/apply/runtime/RuntimeAdapter.java` + both adapters + `InMemoryRuntimeAdapter` + `AbstractRuntimeAdapterContractTest`,
`…/runtime/ssh/SshjDockerTransport.java` (the fixed `curl -X POST … /actuator/loggers/<logger>` with a validated
logger name and level enum); Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/logs/{LogLevelService,LogLevelView}.java`,
`…/dp/logs/LogLevelServiceImpl.java`, `console-gateway/.../{LogLevelController,LogLevel,SetLogLevelRequest}.java`;
Create tests per D-LOG, including `aLoggerNameOutsideTheGrammarIsRefusedBeforeAnySession()`,
`aDirectHttpOnlyPlacementIsRefused422ForLive()`, `aLiveSetIsJournaledAuditedAnd202()`,
`theOverrideIsReportedUntilARestartIsObserved()`, `logLevelsAreReachableInTheProductionBoot()`.

- [x] **Steps:** failing tests → implement → `VERIFY dp-core`, `VERIFY console-gateway`, `VERIFY launcher`,
  `VERIFY arch-tests` → mutations (unvalidated logger; skip the journal) → commit
  `feat: live log levels over SSH as a journaled operation (OR-43 P1)`. **Built as `OR-103` ruled instead (below): the task text above describes
  option Ra, which the owner did not choose for v1.**

**Results (T9.3, 2026-10-06, on `p010/t93` off `integration/dp-remote-demo`; commit `feat(dp): live log-level read over SSH, level changes through configuration (OR-43, OR-103)`).**
Built **as `OR-103` ruled (option Rd)**, which differs from the task text above in these ways, each recorded because the ruling wins: (1) **no journaled live-set operation** — no `V013`, no `Operation`/`ApplyCommand`/`ApplyExecutor`/`RuntimeAdapter`
change, no `setLogLevel` on the seam or the contract kit, no `curl -X POST` in `SshjDockerTransport` (the plan's `aLiveSetIsJournaledAuditedAnd202` and `theOverrideIsReportedUntilARestartIsObserved` have nothing to test and are not written); (2)
**the read** is `LogLevelServiceImpl` (`dp-core/.../logs`) through the existing `HostProbe` (the health poller's fixed `curl`, pooled when `healthSshSessionPool` exists, else a per-call transport), at the placement's declared host
port, with both grammars checked before any lookup or session (`aLoggerNameOutsideTheGrammarIsRefusedBeforeAnySession`, `aPathOutsideTheGrammarIsRefusedBeforeAnySession`) and `$` excluded from the logger grammar (the probe command does not quote the URL);
(3) **`setLogLevel` admits nothing**: `mode: config` and `mode: live` are both `501 NOT_IMPLEMENTED` with fixed details, `mode: live` on a direct-HTTP instance is the contract's `422`; `console.yaml`'s description and `501` text and
`ENDPOINT-INVENTORY.md` (§3 row, §8 item) are corrected to say so (no `ApplyAccepted` is returned today, and no fourth `op_known` member exists); `aDirectHttpOnlyPlacementIsRefused422ForLive` is `LogLevelControllerTest.aLiveLevelChangeOnADirectHttpInstanceIs422AsTheContractStates`
and `logLevelsAreReachableInTheProductionBoot` is `LogLevelEndpointsIT` (booted app, real HTTP, real PostgreSQL, the real Cache fixture; a via-SSH instance whose host refuses the connection answers `unavailable`, never an error); (4) **the overlay is
`OverlayKeys` (`BE-D37`) and it enters a generation at the stage, not at render time**: `ConfigGenerationRenderedConfigSource` has no package reader by design (a resumed apply must not change with the package), so the gateway passes only the package's
*fact* (`LoggerSupport`: `logs.baselineExposure`, new `StageCommand` member with a delegating constructor) and `CanonicalContent` appends the entries under the reserved key `@sm-overlay`; the renderer refuses an overlay-keyed entry at a path `OverlayKeys.owns` does not own;
accept-runtime's read-back (T10.3) must strip by `OverlayKeys.owns` (the draft's `forPlacement(...)` name became `forLoggerSupport` + `owns`); (5) **`logs.loggers` (`BE-D38`)** is in the schema, the classpath mirror (byte-identical), the Cache fixture, `negative/generate.py` (four negatives, all `gate01-*`: no
`loggerPrefix`, path outside the grammar, unknown member, no `baselineExposure`) and the Java parity test (23 negatives; the Python and Java validators are both schema-driven, so no gate code changed); two cross-field rules beyond the draft are schema-level (`loggerPrefix` and `baselineExposure` required beside it); the 19 existing negatives and
`overlap/cache-2.0` were regenerated from the new Cache fixture (derived files; **regenerate rather than hand-merge** against plan 011 P11-1.1's fixture changes). Draft decisions allocated: **`BE-D36`** (the ruling), **`BE-D37`** (L-2), **`BE-D38`** (L-5); drafts L-1, L-3, L-4 are not allocated (they describe Ra).
`architecture.md` §10's "no separate port to bind" sentence is corrected (Q-L6 a, residual kept) and `log-levels.md` carries the dated ruling note. **M12's open sub-row (a loopback bind behind `-p`) was not run**: it concerns option Ra only, and no remote host or broker was touched.
**Red first (counts):** `OverlayKeysTest` 6 / `CanonicalContentOverlayTest` 8 against stubs: 6 failures + 5 errors of 14; `OverlayRenderTest` 1 of 4 before the renderer check; `LogLevelServiceImplTest` 16 of 16 red against a stub (1 failure, 15 errors); `LogLevelControllerTest` 14 of 18 red against a stub (12 failures, 2 errors);
`DesignerControllerTest` 1 of the 4 new cases red; the Cache fixture fails gate 1 under the old schema and `PackageSchemaMirrorLockTest` is red on a stale mirror. **Mutations (each planted, seen red, restored byte-for-byte):** schema without the `then`, the path pattern, `additionalProperties:false` on `loggers`, `dependentRequired` (each leaves its negative accepted);
`OverlayKeys` without `loggers`, with `read-write`, without the baseline; `CanonicalContent` accepting a field at an owned path, a forged overlay-keyed path, and ignoring `loggerSupport` (the DB-backed IT); the renderer without its `owns` check; `LogLevelServiceImpl` without the prefix grammar, accepting `ROOT`, accepting `$`,
without the path grammar, reading a direct-HTTP placement over SSH, reporting a `404` as live, guessing a level outside the contract's set, quoting a body, and mapping an unresolvable placement to `unavailable`; the orchestrator without the `422`, without the placement check, inferring a logger from `loggerPrefix`, choosing silently among two placements; `loggerSupportOf` ignoring the selection and
accepting a missing baseline; the advice mapping `LIVE_NOT_BUILT` to `422`; the contract's `501` text reverted; `LogLevelConfiguration` withdrawn from the aggregator and the controller de-annotated (the launcher IT red). **One equivalent mutant, recorded:** removing `CanonicalContent`'s "already carries overlay entries and declares support" refusal stays green because the duplicate-path guard in `renderedArray`
refuses the same command (defence in depth, a clearer message). **Verified:** `./contracts/run-checks.sh --strict` passes (check 11 included); `cp-core` fixture-parity tests 68 (23 negatives); `dp-core` unit suite 613 (1 skipped, pre-existing) and failsafe `ConfigRecordServiceIT`, `ConfigGenerationRenderedConfigSourceIT` (8), `AdmissionGenerationBindingIT`, `ApplyServiceIT`, `InventoryServiceIT`, `TablePlacementCatalogIT`; `console-gateway` unit suite 349 (the new `LogLevelControllerTest` 18, four new `DesignerControllerTest` cases, three new `SchemaMirrorLockTest` cases; green once a `Void`/`void` slip in one new mirror assertion was fixed);
`arch-tests` 56 and the new classes broke no rule; `launcher` `LogLevelEndpointsIT` (7), `ProductionAuthenticationIT` (route set gains `getLogLevel` and `setLogLevel`), `ApplyEndpointsIT`, `DesignerEndpointsIT`, `InstanceEndpointsIT`, `CatalogEndpointsIT`, `CatalogEventIT`, `DeclareRegisterTimeQuotaIT`, `DeclareCrashWindowIT`, `HealthEndpointsIT`.
`HealthEndpointsIT.thePersistedRowsAreTheOnlyThingRead` failed once (1516 ms against its 1500 ms bound, a login included) when ten ITs shared the machine and passed alone: a load-sensitive bound, unrelated to this task. **Not done and not claimed:** that a real Cache starts with the overlay and answers the loggers endpoint as the framework probe did (the owner-gated E2E); `configuredLevel` (T9.4).

### Task T9.4 — P2: the level as configuration

**Wave:** W11, **after T10.2 in the same agent** (both edit `ConfigRecordServiceImpl`, M13) and after T9.3 merged.
**Files:** Modify `…/apply/production/{YamlConfigRenderer,ConfigGenerationRenderedConfigSource}.java`
(the overlay), `…/dp/config/ConfigRecordServiceImpl.java` (`copyForward` with an overlay change, `origin = 'edit'`),
`…/dp/logs/LogLevelServiceImpl.java`; tests `aConfigLevelIsACopyForwardGenerationAppliedNormally()`,
`itTakesEffectOnlyAfterARestartAndTheLadderSaysSo()`. **M13** first. Commit
`feat: log level as configuration through a normal apply (OR-43 P2)`.

**Results (T9.4, 2026-10-06, on `p010/t94` off `p010/s811`; commit `feat: log level as configuration through a normal apply (OR-43 P2)`).**
Built as `OR-103` ruled; differences from the task text above, each recorded because the ruling wins: (1) **no change to `YamlConfigRenderer`/`ConfigGenerationRenderedConfigSource`** - T9.3 already put the overlay in the generation at the stage, so the level is one more overlay entry from the shared `OverlayKeys` (`forLogLevel`, never a package field); (2) the level is **not** minted by `copyForward` (which refuses `edit`) but by a new `ConfigRecordService.copyLatestWithLogLevel` (origin `edit`, no derived-from, guard 1 applies) and a new `ApplyService.setLogLevel` (`SetLogLevelCommand`): scope pre-check, copy (own transaction), then a normal `manual` admission - so the level takes effect at the next restart and the ladder stays at `persisted` (M4); (3) **a designer or accept-runtime edit carries the configured level** (`stage` copies the latest generation's level entries under the instance lock when the package still declares logger support; `log-levels.md` section 9's hazard); (4) `getLogLevel.configuredLevel` is the latest generation's, reported even when the host cannot answer (`LogLevelServiceImpl.ConfiguredLevels`, fed by `ConfigRecordService.configuredLogLevels`); (5) **ownership is per generation in the read-back**: `OverlayKeys.owns` (the stage/renderer rule: any valid `logging.level.<logger>` may be SM's) would have turned an operator's own `logging.level.<other logger>` into `sm-owned-overlay` and broke T10.4's `undeclared-key` behaviour, so the read-back uses `ownsBaseline` plus the level paths the latest generation carries (`OverlayKeys.ownsIn`); (6) `console.yaml` (`setLogLevel` text, `409` added, `422` and `501` text) and `ENDPOINT-INVENTORY.md` corrected; the 202 is now observed at `/apply/observe` as an ordinary `apply`. **No migration.** **M13** (Boot `logging.level.<logger>` semantics) was already filled in `log-levels.md` section 2.2 (fetched 2026-10-05); not re-run.
**Red first:** against stubs `OverlayKeysTest`/`CanonicalContentOverlayTest`/`LogLevelServiceImplTest` 22 of 46 red (5 failures, 17 errors), `ApplyServiceSetLogLevelIT` 10 of 10, `LogLevelControllerTest` 6 of 25. **Green:** dp-core unit 72 (targeted) and `ApplyServiceSetLogLevelIT` 11, `ConfigRecordServiceIT` 30, `ConfigGenerationRenderedConfigSourceIT` 9, `RuntimeReadBackServiceImplTest` 16, `ReadBackMapperTest` 15; gateway `LogLevelControllerTest` 26, `SchemaMirrorLockTest` 40, `ApplyControllerTest` 47, `DesignerControllerTest` 44; launcher `LogLevelConfigEndpointIT` 7 (new, production boot), `LogLevelEndpointsIT` 7 (its config case is now `422`: nothing staged), `AcceptRuntimeEndpointIT` 17, `RestoreRuntimeEndpointIT` 8, `RollbackEndpointIT` 9, `DesignerEndpointsIT` 18, `ProductionAuthenticationIT` 4; `./contracts/run-checks.sh --strict` passes. (First launcher run caught the read-back ownership regression in item 5.)
**Mutations (planted, seen red, restored byte-identical with `cmp`):** `isLevelPath` always false; the root logger accepted; carried levels ignored in `CanonicalContent`; the stage not carrying levels; the old level of the same logger kept; the scope fence skipped; `configuredLogLevels` empty; the live read ignoring the configured level; the read-back ignoring configured level paths; `ownsIn` claiming any level path; origin `rollback` for a level change; the gateway not requiring package support; `mode: live` admitted; the organization not the session's; the logger taken from the component name instead of the package's prefix. **Not proven:** the copy/admission transaction split - `PROPAGATION_REQUIRED` made the IT hang (killed after 30 minutes), which is not a clean red; the same split is proven for restore by `ApplyServiceRestoreRuntimeIT`.
**Not done and not claimed:** that a real Cache applies `logging.level.*` from the written file and reports it after a restart (the owner-gated E2E); a second verb: `logs.setLevel` alone authorizes the change (it restarts nothing), the restart itself needs `instance.restart` as always.

---

## S10 — OR-45's five

### Task T10.1 — `POST …/config/secrets/{fieldKey}`

**Wave:** W10. **Depends on:** T2.4, T5.3. **Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/config/ConfigSecretService.java`,
`…/dp/config/ConfigSecretServiceImpl.java`, `console-gateway/.../{ConfigSecretController,SetConfigSecretRequest,ConfigSecretResult}.java`;
tests `…/ConfigSecretServiceIT.java`, `launcher/.../ConfigSecretEndpointIT.java`.

`fieldKey` must name a field the instance's package marks secret (the projection's `isSecret`, `project.ts:66`),
else `404`. The value is encrypted as purpose `CONFIG_FIELD` (T2.4), the request buffer zeroised. **Built to T0.1
point 17 as ruled (owner Step 7, batch 1, `OR-60`: the landed contract kept)** (`{value, baseStateVersion}` → `200 StagedConfig`): in the same request
the service stages a copy-forward of the latest draft with that field's value replaced by `{"secretRef": …}`
(origin `edit`), through the normal stage path (T5.3 — re-projection, `baseStateVersion` checked under the instance
lock, `409` on stale), so the version returned has a real source (the new generation) and no stored secret is ever
unreferenced; render (T6.2) resolves the reference. ⛔ The two-call shape (`{value}` → `{secretRef, secretPresent: true}`) was **not** chosen. ⚠️ With secrets
now renderable, the rendered file on the host contains the value (unavoidable — it is the MI's config); every hash
of it is keyed (OR-32, T5.1) and **history, designer and ladder never show it**.

- [x] **Tests:** `theValueNeverAppearsInAnyResponseLogOrRow()` (scan the response, the captured log, every
  `sm_data` column), `aNonSecretFieldIs404()`, `aStagedSecretRefRendersAndTheHistoryShowsOnlyPresence()`,
  `aStaleBaseStateVersionIs409AndStoresNoSecret()` (the secret row and the generation commit together
  or not at all), `requiresConfigEdit()`, `reachableInTheProductionBoot()`. Mutation: echo the value in the `422` detail → red.
  Commit `feat: config secrets entered through the console, stored envelope-encrypted (OR-30, OR-45)`.

**Results (T10.1, 2026-10-06, branch `p010/t101` off `integration/dp-remote-demo`).** Served: `setConfigSecret` (`POST /api/dp/instances/{instanceId}/config/secrets/{fieldKey}`), one call, `200` with the plain `StagedConfig` (`{stateVersion, generation}`; no `unchanged`, no value, no reference). New seam `dp-api` `ConfigSecretService.storeAndStage(org, instance, base, char[] value, actor, StageBuilder)` and `SecretValueRejectedException`; implementation `dp-core` `ConfigSecretServiceImpl` (its own class, `ConfigRecordServiceImpl` untouched) wired by a new `ConfigSecretConfiguration` (one `@Import` line in `DataPlaneConfiguration`, kept out of `ConfigRecordConfiguration` because the config-record ITs wire no secret store). Gateway: `ConfigSecretController`, `ConfigSecretOrchestrator`, `SetConfigSecretRequest`, `ConfigSecretResult`.

*How it is built.* The service takes the instance row lock, refuses a stale `baseStateVersion` BEFORE the value is touched, stores the value as `CONFIG_FIELD` (the secret store's JDBC template runs over the same data source, so `put` joins the transaction), asks the gateway's builder for the stage command naming the new reference, and writes the generation exactly as `stage` does (next number, keyed digest, head advance, prune). The builder runs under the lock but is pure CPU: package, observed state and draft are read before. The gateway copies the latest draft forward with the one field set to `secretRef://<new id>`, projects it, runs the same `DesignerStageValidator` and the same refusals as `stageConfig` (unprojectable, undeclared key, blocking validation), resolves every OTHER reference in the draft, and stores the SERVER's rendered set. A refusal anywhere after the insert rolls the secret back with it.

*Red-first.* `ConfigSecretServiceIT` was written against a throwing skeleton and seen red: 14 run, 5 failures plus 8 errors (13 red; the fourteenth, `aMalformedCommandRollsTheSecretBackToo`, passed vacuously on `assertThrows(RuntimeException)` and was tightened to `IllegalArgumentException` before the implementation). Green after: 14. ⚠️ Deviation, as for T5.3: the gateway and launcher code was drafted before `ConfigSecretControllerTest` and `ConfigSecretEndpointIT`; those two are proven by the mutations below, and the IT was seen red against two plants (L1, L2).

*Counts (all green).* `ConfigSecretServiceIT` 14; `ConfigSecretControllerTest` 23; `SchemaMirrorLockTest` 35 (one new method: the contract's one-call shape, no `unchanged`, write-only value, no `instanceKey`, no `organizationId`); `ConfigSecretEndpointIT` 10 (real Postgres, real HTTP, the production boot); `DataPlaneConfigurationTest` 3 (import set updated); regressions run: `DesignerControllerTest` 40, `GatewayHttpBoundaryTest` 40, `OperationCoverageValidatorTest` 12, `DesignerEndpointsIT` 18, `ConfigRecordServiceIT` 30, `ProductionAuthenticationIT` 4 (route set gains `setConfigSecret`), arch-tests `ApplyPathBoundaryTest` 7 + `CompositionBoundaryTest` 5 + `PlaneBoundaryTest` 20.

*Mutation proofs (each planted, seen red, restored; every restore checked byte-identical with `cmp`).* **Gateway:** echo the value in the `422` detail (via `DeclareValidationException(new String(value))`) → `aValueTheStoreRefuses…NeverEchoed` and `anEmptyValue…` red (2); drop the `masked` check → `aNonSecretFieldIs404` red; skip the validator → 4 red (blocking validation, dangling reference, other-reference, refusal-path set); ask the store about the reference just minted → 2 red; resolve no other reference → 2 red; do not zeroise → the zeroise test red; guess among two instances → the ambiguity test red; another organization on the data-plane call → 2 red (I5); the command ignores the caller's base → the staged-content test red; a literal in the draft instead of the reference → 10 red. **Data plane:** no stale check → 2 red; the secret committing in a transaction of its own → 5 red (`theSecretRowAndTheGenerationCommitTogether` and four rollback tests); accept a non-edit command → red; no prune → red; wrong purpose → 3 red (the renderer's `CONFIG_FIELD` read refuses the row); a command built from another base accepted → red; the store's refusal escaping untranslated → red; the head not advanced → 4 red. **Boot:** `DataPlaneConfiguration` without the new import → `DataPlaneConfigurationTest` red and all 10 `ConfigSecretEndpointIT` tests red (context fails to load); a changed route path → 8 red across `ConfigSecretEndpointIT` and `ProductionAuthenticationIT`.

*What the value-never-leaks tests cover.* `ConfigSecretServiceIT` scans EVERY column of every `sm_data` and `sm_secret` table (bytea decoded) and the TRACE-level log; `ConfigSecretEndpointIT` scans every column of `sm_data`, `sm_secret` and `sm_control` and the server log at TRACE, and renders generation 2 through the production `RenderedConfigSource` to show the value reaches the file only there (and rotation: generation 3 renders the new value, the old row is kept, OR-85 OQ-4).

**Flags (open for the owner; none is a ruling I made).**
1. **Which component instance.** The path names a field key and the contract's body (`additionalProperties: false`) carries no `instanceKey`, so a field two selected components both declare is ambiguous. Built: the one selected instance whose package marks that key secret; none → `404` (a non-secret and an undeclared field answer alike); several → `422 SECRET_FIELD_AMBIGUOUS`, never a guess, so a credential cannot land on an instance the operator did not mean. If the owner wants addressing, the fix is a reviewed `console.yaml` change adding an optional `instanceKey` to the body.
2. **`console.yaml` declares no `422` for `setConfigSecret`**, though the plan's own mutation names one and `stageConfig` declares it (rejected value, ambiguity, and the stage rules). The service answers `422` with the same `VALIDATION_FAILED` problem body as `stageConfig`. A contract edit is single-writer and outside this task's Files list, so it is left for the contract owner.
3. **A secret needs a staged draft.** With no generation there is no selection and so no field: `404`. The operator saves the designer once first. (Hidden and virtual secret fields are valid targets, as `stageConfig` already accepts a reference there.)
4. **Tomcat's own TRACE wire dump logs request bytes.** With TRACE enabled on `org.apache.coyote.http11.Http11InputBuffer`, the container prints the raw request, including this endpoint's `value` (and every login credential, which is pre-existing). No code of ours logs the value (every other logger, at TRACE, was captured and is clean). The IT excludes that one logger by name and says why; the install runbook should state that TRACE must never be enabled on it.
5. **`ConfigSecretServiceImpl` repeats the insert, head advance and prune of `ConfigRecordServiceImpl.stage`** because `stage` is `REQUIRES_NEW` and cannot join a transaction, and the dispatch map reserves that class for T8.1. A later refactor can extract one "append a generation" helper both use.
6. The Files list named `ConfigSecretResult`; it is kept, and is needed (the contract's `StagedConfig` has no `unchanged`, which the existing `StagedConfig` record carries). Shared files edited, modifiers or one line only: `DesignerOrchestrator` (nine `private` → package-private plus the reference prefix constant, so the two orchestrators share one set of stage rules), `StageRefusedException` (two reasons), `DataPlaneConfiguration` and its test, `ProductionAuthenticationIT` (one route), `SchemaMirrorLockTest` (one method appended). `ENDPOINT-INVENTORY.md` still says "not yet served" for this row; not in this task's Files.

### Task T10.2 — `POST …/config/restore-runtime`

**Wave:** W11, first in the T10.2 → T9.4 agent. **Depends on:** T8.1. **Files:** Modify `…/dp/config/ConfigRecordServiceImpl.java`,
`…/apply/service/ApplyServiceImpl.java`; Create `console-gateway/.../RestoreRuntimeController.java`; tests
`launcher/.../RestoreRuntimeEndpointIT.java`. `B/D12` 🔒's manual drift reconcile, "re-apply desired": a copy-forward
of the **current desired** generation (`origin = 'restore-runtime'`), then — **in a second transaction** (Global
Constraints, m3) — a normal apply under `config.apply` (+ `instance.restart` if restarting). **Built to T0.1
point 19 as ruled (owner Step 7, batch 1, `OR-60`)**: `{placementId, strategy}` — `placementId` required, `strategy`
present with default `manual`, the generic package-driven strategies of S0-10; **a restarting strategy requires
`instance.restart`**. The `Strategy` enum lists all five engine tokens and the three unbuilt ones answer `501`. The
landed manual-only shape was **not** kept. Tests:
`restoreReappliesTheDesiredContentUnderANewGeneration()`, `requiresConfigApply()`,
`aRestartingStrategyAlsoRequiresInstanceRestart()`, `aMissingStrategyDefaultsToManualAndNeverChecksInstanceRestart()`,
`anUnbuiltStrategyTokenIs501()` (`OR-60`),
`aDriftedHostIsRestoredAndThePersistedRungReturns()`, `theCopyForwardAndTheSubmitAreSeparateTransactions()`.
**Mutations (m6):** re-submit the desired generation's own number instead of copying forward → admission refuses
`GENERATION_NOT_ABOVE_WATERMARK` (`AdmissionService.java:103-109`) and the first test red; copy forward from the
latest *applied* generation instead of the desired one → the content test red; run both halves in one transaction →
the transaction test red. Revert each. Commit `feat: restore-runtime — re-apply desired as copy-forward (OR-45)`.

**Results (T10.2, 2026-10-06, lane `p010/s8`, after T8.1).** Committed as `feat: restore-runtime — re-apply desired as copy-forward (OR-45)`.
*Shape.* `ApplyService.restoreRuntime` + `RestoreRuntimeCommand` (dp-api); `ConfigRecordService.copyLatestForward(org, instance, origin, actor)`
(new: the source is the instance's newest generation, resolved UNDER the instance row lock, so a stage landing between a
read and the copy cannot make the copy stale; empty when nothing is staged, which the service answers `422`);
`ApplyServiceImpl.restoreRuntime` (strategy served? placement live? executor present? copy the desired forward as origin
`restore-runtime`, then `admit(..., MUST_BE_STAGED)` of the COPY); `RestoreRuntimeRequest` (`placementId` required,
`strategy` optional and defaulted to `manual`, a present unknown token `400`), `RestoreRuntimeController`,
`ApplyOrchestrator.restoreRuntime` (`501` before any other verb; `instance.restart` only for a restarting strategy; then the
404s). `ConfigGenerationRows.namedByAnApplyRequest` (new read) backs the retry rule below. The old manual-only shape was not kept.
*The one design decision.* **A restore must mint a new generation even when the content equals the latest's.** OR-33 guard 1
("a stage equal to the latest generation writes no row") answers an operator EDIT that changes nothing; a restore's point is
that the host differs while the record does not, and the placement's watermark is at or above the desired generation's own
number, so re-submitting it is `GENERATION_NOT_ABOVE_WATERMARK`. So `copyLatestForward` skips guard 1 (`copyForward`, T8.1,
keeps it). Its idempotence is the crash window only: when the latest generation is itself an unapplied copy of the same
origin (no `apply_request` names it), a retry ADOPTS it rather than minting another; once an apply names it, the next restore
mints a fresh one. A refused admission (`SCOPE_BUSY`) therefore leaves exactly one copy and every further attempt adopts it.
*Red first.* Gateway: 7 of the 8 new `ApplyControllerTest` restore cases red against the stub (the 8th, the `403`, is the
interceptor's and was already true), then 47/47. `ApplyServiceRestoreRuntimeIT` (dp-core, 7): 6 red against the stub
(5 errors, 1 failure; the seventh documents the existing watermark fence), then 7/7. `RestoreRuntimeEndpointIT` (launcher, 8)
was written after the controller and is proved by mutation. *Mutations, each red then restored byte-for-byte:* (a) re-submit the
desired generation's own number (the plan's named mutation) → `GENERATION_NOT_ABOVE_WATERMARK`, `ApplyServiceRestoreRuntimeIT`
5 of 7 red including `restoreReappliesTheDesiredContent…`, and `RestoreRuntimeEndpointIT` 3 of 8 red (409 / generation 2 not 3);
(b) copy forward from the latest APPLIED/requested generation instead of the desired → 3 red, the content test among them;
(c) run both halves in one transaction (`REQUIRES_NEW` → `REQUIRED`) → **the test does not fail, it HANGS**: the outer transaction
holds the `instance_state` row lock and the engine's own admission transaction waits for it (confirmed with `jstack`: the test
thread blocked inside `ApplyExecutor.admit`); the run had to be killed after ~10 minutes. That is the self-deadlock the lock-order
rule exists to prevent, and it is a red; (d) keep OR-33 guard 1 on the restore copy → 5 red; (e) no adoption of an unapplied copy → 2 red;
(f) the restore skips the `instance.restart` check → red; (g) the default strategy not `manual` → red.
*Counts.* dp-core `ApplyServiceRestoreRuntimeIT` 7, `ApplyServiceRollbackIT` 7, `RollbackTargetsIT` 12; launcher
`RestoreRuntimeEndpointIT` 8, `RollbackEndpointIT` 9; gateway `ApplyControllerTest` 47. One combined run at the end of the lane: every reactor `*Test` unit
suite (cp-core 324, dp-core 579 with 1 skip, gateway 342, arch-tests 56 among them) plus `ApplyServiceIT` 18, `ConfigRecordServiceIT` 30, `ConfigLadderIT` 13,
`ApplyEndpointsIT` 10, `DesignerEndpointsIT` 18 and the five new ITs: all green.
*`aDriftedHostIsRestoredAndThePersistedRungReturns`* (launcher): applied generation 1 → ladder `persisted`; the in-memory host's
file is altered; the worker is held, a restore is admitted, and the ladder reads `rendered` (generation 4, the new head, is not yet
on the host); released, it lands `STAGED`, the host's file hash is the intended one again and the ladder is `persisted` at generation 4.
*Flags (for the owner).* (a) **Guard 1 bypass** (above): every restore adds a generation, and an applied one is kept for good
(OR-50: a generation an `apply_request` names is never pruned), so repeated restores grow the config of record by one row each, ~15 KB for
Cache. Bounded by how often an operator restores; no cap was added. (b) **"Desired" is the newest generation, applied or not.** A restore
therefore applies a staged edit the host has never held; `architecture.md` §6.3's "re-apply SM's version" is read that way, and the plan's own
mutation (copy from the latest APPLIED one → content test red) pins it. If an operator should only re-assert what was last applied, that is
a different rule. (c) An explicit JSON `null` `strategy` reads as absent (`manual`); the contract's schema would call it invalid. (d) The
contract closes the body; the gateway ignores a stray key, as `submitApply` does. (e) `OR-102` remains unbuilt (see T8.1's flags).

*`OR-108` (8) (2026-10-06):* flag (b) is ruled as built - `restore-runtime`'s "desired" is the instance's newest generation, applied or not; no code change.

### Task T10.3 — D-ACCEPT: reading the live file back into field values (design, docs only)

**Wave:** W10. **Files:** Create `docs/design/backend/accept-runtime.md`; draft `«next free BE-D»`.

OR-45: "`accept-runtime` needs a read-back/parse design first." The design answers: (1) the read is a bounded
request-thread read of the placement's config file through the adapter (no mutation); (2) parse with the same YAML
library the renderer uses, strip SM's own overlay keys (T9.2), map each `renderedPath` back to `(instanceKey,
fieldKey)` by inverting `renderedPathFor` (`project.ts:120`) against the package; (3) ⛔ **`I6`: a secret field's
value read back is dropped, never stored** — the accepted draft keeps the current `secretRef` for that field, and the
response says which secret fields were not accepted; (4) an unmappable key, a type the field's `normalize`
(`project.ts:83`) refuses, or a file whose keyed hash equals the current generation's (nothing to accept) → `422`
or `unchanged`; (5) the resulting draft goes through the **normal** stage path (re-projection, OR-34) with
`origin = 'accept-runtime'`, under `config.edit` **and** `config.overrideWarning` (§5.3 row 42). If the design
needs a contract change beyond S0 point 18, it is a reviewed `console.yaml` change owned by this task — the only
`console.yaml` editor in W10–W11 now that the fleet rows land in T0.1 (single-owner table; M13).

*(Added by the plan 011 integrator hand-off, `OR-93`: T10.3 is docs only. Plan 011's P11-1.2 waits for it, so it may be dispatched **ahead of W10**, beside S6/S7. If T10.3 needs no `console.yaml` change, say so in its Results so P11-1.2 may proceed.)*

- [ ] Commit `docs(backend): D-ACCEPT — accepting runtime drift without ever storing a read-back secret (OR-45)`.

### Task T10.4 — `POST …/config/accept-runtime`

**Wave:** W11. **Depends on:** T10.3 merged, T5.3. **Files:** as D-ACCEPT names; at least
`…/dp/config/{RuntimeReadBack,ReverseRenderedPathMapper}.java`, `console-gateway/.../AcceptRuntimeController.java`,
`launcher/.../AcceptRuntimeEndpointIT.java`. ✅ **`placementId` is a required body field** (owner Step 7, batch 1, `OR-60`:
it names which placement's file is read back; the read-back, the keyed-hash comparison and the stage all use that
placement). Tests: `aMissingPlacementIdIsRefused()`, `theReadBackIsOfTheNamedPlacementOnly()`, `aReadBackSecretIsDroppedAndReported()`,
`anUnmappableKeyIs422()`, `requiresBothConfigEditAndConfigOverrideWarning()`,
`theAcceptedDraftIsReProjectedByTheServer()`, `reachableInTheProductionBoot()`. Mutation: store the read-back
secret → the `I6` test red. Commit `feat: accept-runtime — observed drift accepted as a new generation (OR-45)`.

**Results (T10.4, 2026-10-06, lane `p010/t104` off `integration/dp-remote-demo` `e79ea857`; built as ruled by `OR-104`, which wins where it differs from the task text above).** Committed as `feat: accept-runtime reads the host file back into a staged generation, never storing a secret (OR-45, OR-104)` (the plan's named message is superseded by the brief's). **Differences from the task text, each from a ruling or measured:** (1) the unmappable-key test is no longer `anUnmappableKeyIs422()`: `U-2` accepts the mappable values and reports undeclared keys by path (a `422` is for a file SM refuses to read, or a value the package refuses); (2) a read-back secret is not merely dropped: `V-4` compares it in memory through a compare-only `dp-api` port (`SecretCompare`) and reports `same`/`differs`; (3) files are `RuntimeReadBackServiceImpl`/`ConfigFileParser`/`ReadBackMapper` in `dp.apply.runtime.readback` (the only package family besides the engine that may name `RuntimeAdapter`), not `RuntimeReadBack`/`ReverseRenderedPathMapper`; (4) an unreadable host is `503 SERVICE_UNAVAILABLE` (`Q-A5 (a)`), with `ErrorCode`'s text widened. Built: the seam's tenth operation `readConfig` (`BE-D39`; a `default` method, overridden by both certified adapters and the in-memory one; four kit tests, plus `ConfigReadBackTest`), the event pre-scan parser (`BE-D40`), the mapper (exact kebab paths, `M-1`), `SecretCompareImpl` (`BE-D41`), the gateway orchestrator, controller and advice handlers (`BE-D42`), the contract (`AcceptedRuntimeConfig`, the `503`, the widened text, check 16 in `validate-openapi.py`), `accept-runtime.md`'s dated ruling note and `BE-D39`-`BE-D42` in `decisions.md`. No migration (`V024` unused). No new artifact: `snakeyaml` is declared in dp-core's pom (already on its classpath via `jackson-dataformat-yaml`).

*Counts (fresh, final).* dp-core surefire 636 (1 skipped) and failsafe `ConfigRecordServiceIT` 30 + `DockerAdapterContractIT` 26 (Docker was available, so the kit's four `readConfig` tests ran against the real `DockerAdapter`); console-gateway 356 (`AcceptRuntimeControllerTest` 27, `AcceptRuntimeSchemaLockTest` 5); arch-tests 59 (`ReadBackSecretBoundaryTest` 3); launcher `AcceptRuntimeEndpointIT` 17, `ProductionAuthenticationIT` 4, `DesignerEndpointsIT` 18, `ApplyEndpointsIT` 10; `./contracts/run-checks.sh --strict` green. *Red seen first:* the kit's four new tests on both subclasses (2 failures + 6 errors), `ConfigFileParserTest` 12 of 12, `ReadBackMapperTest` 12 of 13; the gateway, service, `SecretCompareImpl`, IT and arch tests were proved by mutation (below) because their code was written beside them.

*Mutation proofs (each red, restore checked byte-for-byte).* Parser: Jackson alone, no strict duplicates, anchors not refused. Mapper: later duplicate wins, a value in a reported path, hard-coded overlay instead of the seam, a `yaml` field read, a secret mapped like a plain value, relaxed binding. `SecretCompareImpl`: always `SAME`, material never closed, an exception reported as `DIFFERS`, any purpose comparable. Service: placement lookup skipped, read-back not closed, candidate not zeroed, bytes gap always false, an unverifiable key reported as the same, a transaction open during the read. Gateway: `config.overrideWarning` skipped, a secret adopted, round trip skipped, blocking validations skipped, absent field kept (survived first; a test was added), wrong origin, no `derivedFrom`, layout cleared, `truncated` never set, bytes gap always false, `503` as `500`, inactive fields unreported, placement ownership unchecked, a value equal to the effective one written into the draft. Contract: five planted `console.yaml` faults (extra comparison word, no `503`, uncapped report, a `value` property, the wrong `200` schema), and a record component made optional (lock test). Kit/seam: another tenant's hash, a restart inside `readConfig`, an absent file read as empty, `close` not zeroing, `bytes()` handing out its own array, an over-bound read allowed. Arch: a `SecretResolver` held in the read-back package, the wiring allowance dropped. IT: the I6 scan caught a logged secret only after the first version (Spring Boot's `OutputCaptureExtension`, measured to cover one test method, passed the same leak) was replaced by a console tee started before the context; skipping the second verb, Jackson alone, and bytes-gap-always-false are red end to end. Adopting a secret end to end is stopped by the existing `SECRET_NOT_A_REFERENCE` validation as well, a second line.

**Flags for the integrator and owner.** (1) **Overlay seam (closed at the S8-S11 merge):** D-LOG's `OverlayKeys` was not in this tree, so `SmOverlayKeys.DEFAULT` (`management.*`, `logging.level.*`) stood in; `RuntimeReadBackConfiguration.smOverlayKeys` now returns the shared `OverlayKeys::owns` and the default is deleted, with `ReadBackMapperTest.everyKeyTheOverlayWritesIsReportedSmOwnedAndNotAdopted` proving the keys SM writes are reported `sm-owned-overlay` and not adopted. The shared function is narrower (two keys), so `management.server.port` and `logging.level.*` are `undeclared-key` now. (2) The lane allocated `BE-D36`-`BE-D39`, which collided with T9.3's `BE-D36`-`BE-D38`; renumbered to `BE-D39`-`BE-D42` at the S8-S11 merge. (3) **Shared files touched with small hunks:** `DesignerOrchestrator` (nine helpers `private` to package-private), `GatewayErrorAdvice` (two handlers), `DataPlaneConfiguration` and its test (one import), `ProductionAuthenticationIT` (the served-operation set plus two bean counts), `ApplyItSupport.RoutingAdapter` (`readConfig`, `hostFor`), `RuntimeSeamTypesTest` (ten operations). (4) `readConfig` is a `default` method so some fifteen delegating test adapters and other lanes' new ones still compile; the kit pins it for certified adapters. (5) The round-trip check runs in the gateway, not the data plane as the design wrote (it needs the projection). (6) *(Corrected at the S8-S11 fix round, S811-13.)* A missing or unusable SSH credential already answers `503`: `SshjDockerTransport.loadKey` maps SM's `SecretException` to `RuntimeAdapterException("ssh.credential.unavailable")` (or `.invalid`), which `RuntimeReadBackServiceImpl.classify` turns into `HOST_UNREADABLE` (`SshjDockerTransportCredentialTest`, `RuntimeReadBackServiceImplTest`). The only remaining `500` is SM's own HMAC-key failure (`EnvelopeHashKeySource` via `hashOf`), which is an `INTERNAL_ERROR` and correctly stays one under `Q-A5 (a)`: it is not "the host did not answer", and no transport change is wanted. (7) `Q-A6` is unruled: a `yaml` field is `yaml-field-not-accepted` and keeps its value. (8) An instance with no staged generation answers `422` (nothing to read the file against); not in the design, not ruled. (9) The real SSH path was not exercised against a host (the brief forbids it); a placement over the production adapter is proved only through its kit and `ProductionAuthenticationIT`'s boot.

### Task T10.5 — `GET /cp/home/summary` and `GET /cp/home/attention`

**Wave:** W11. **Depends on:** T7.2, T4.4, T3.2. **Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/home/{InventorySummaryService,AttentionService}.java`
and their `dp-core` implementations; Create `console-gateway/.../{HomeController,HomeSummary,AttentionList}.java`;
tests `console-gateway/.../HomeControllerTest.java`, `launcher/.../HomeEndpointsIT.java`.

Composed in the gateway (the one module permitted both `-api`s; `OR-78` hosts the metrics routes in `console-gateway` too, so there is
no `console-api` module to absorb it —
OR-10), **concurrently**, with **per-surface `availability`** so a DP outage degrades the counts and not the
catalog list (OR-10). Both are `x-sm-scope: reach` (S0 point 24, T1.3). Every item is filtered through
`ReachAuthorization.authorizedSubset` by the caller's verb at the item's own scope — the summary never counts an
instance the caller cannot see. **As ruled** (owner Step 7, batch 2, `OR-61`): the summary's `instanceCount` is filtered
**per instance** by `instance.view` at that instance's scope, and `byHealth` counts only those instances where
`health.view` is **also** held at that instance's scope; `byHealth` has the **nine-key** vocabulary (the five rollup
keys `GREEN`, `RED`, `RESTART_EXPECTED`, `INACTIVE_REPLICA`, `NOT_APPLICABLE` plus `missing`, `unreachable`,
`not_exposed`, `bootstrap`), and each instance's state is `project.ts` `healthRollupOf` **literally** at placement
scope with the four NOTFOUND sub-states inserted (`NOT_APPLICABLE` if no probe; else the first of any `RED`, `missing`,
`unreachable`, `not_exposed`, `RESTART_EXPECTED`, `bootstrap`; all `INACTIVE_REPLICA` → `INACTIVE_REPLICA`; else
`GREEN`). Attention reasons are filtered by the verb that guards their **source**: `health` → `health.view`;
`apply-indeterminate` and `config-diverged` → `config.view`. **Each attention item carries its own `stateVersion`**
(`OR-60`). The summary carries **no top-level
`stateVersion`, one per solution row** (owner ruling 2026-09-29 (S0-15), `OR-55`). `attention` reasons, in the
event vocabulary (owner ruling 2026-09-29 (S0-11)): health not `GREEN` (incl. the NOTFOUND sub-state
`unreachable`); an `APPLY_INDETERMINATE` request (including one T6.8's reconciler gave up on); a `CONFIG_DIVERGED`
request.

- [x] **Tests:** `theSummaryNeverCountsAnInvisibleInstance()`, `anEnvironmentBoundOperatorGetsItsOwnCountsNot403()`,
  `aDpOutageDegradesTheCountsNotTheCatalog()`, `theTwoPlaneReadsRunConcurrently()` (a latch proves both are in flight
  together), `attentionListsIndeterminateAndDivergedAndUnhealthy()`, `byHealthCountsOnlyInstancesWhereHealthViewIsAlsoHeld()`,
  `byHealthUsesTheNineKeyVocabularyAndTheLiteralRollup()` (one case per rollup rule, including the four NOTFOUND
  sub-states), `eachAttentionReasonIsFilteredByItsSourcesVerb()`, `everyAttentionItemCarriesItsOwnStateVersion()`,
  `reachableInTheProductionBoot()`.
  **Mutations (m6):** drop the per-item subset → the invisible-instance test red; serialize the two plane reads → the
  concurrency test red; let a DP exception escape → the degradation test red; add a top-level `stateVersion` →
  the `SchemaMirrorLockTest` case for `HomeSummary` red; count `byHealth` without checking `health.view` → the
  count test red; filter `config-diverged` by `health.view` → the source-verb test red. Revert each. Commit
  `feat: home summary and attention served — composed, filtered, degradable (OR-45)`.

**Results (T10.5, 2026-10-06; lane `p010/t105`).** `getHomeSummary` and `getHomeAttention` are served, `x-sm-scope: reach`, composed in the gateway (`HomeOrchestrator`) over two new `dp-api` ports, `InventorySummaryService` and `AttentionService`, which return the data plane's UNFILTERED facts (the DP cannot know the caller's reach, `I7`); the gateway then filters every instance through `ReachAuthorization.authorizedSubset` at the instance's own scope. *Summary:* the catalog (control plane) and the data plane are read concurrently (the DP read on a bounded daemon pool, `CallerRunsPolicy` so a full pool serializes and never refuses); `instanceCount` is filtered by `instance.view`, `byHealth` by `health.view` among those (asked only about already-visible instances), keyed by the nine-key vocabulary and ABSENT, never zeroed, when nothing qualifies; each row's `stateVersion` is the catalog row's own; no top-level `stateVersion`. A data-plane exception degrades the counts (`availability: degraded`, counts `0`, catalog rows intact) and only the exception's class is logged. *Attention:* each candidate is filtered by `instance.view` and the verb guarding its source (`health` by `health.view`; `apply-indeterminate` and `config-diverged` by `config.view`); each item carries its source row's own stamp (`placement_health.state_version` or `apply_request.state_version`); a DP failure is `availability: unavailable` with no items. *DP side (`dp-core`):* `HomeRollup` is `healthRollupOf` literally at placement scope with the four NOTFOUND sub-states inserted (`OR-61`), a `NOT_APPLICABLE` placement (`OR-101`) dropping out beside a probed one; `JdbcApplyAttentionRepository` is one read-only, tenant-bound `SELECT` (no tenant enumeration, so this adds no caller of OR-49's list); `HomeConfiguration` and `HomePersistenceConfiguration` are imported by `DataPlaneConfiguration`. No migration, no contract edit, no new dependency.

*Red first.* `HomeRollupTest` 12 run / 12 red and `HomeReadsIT` 10 run / 10 red (against stubs that throw); `HomeControllerTest` 19 run / 18 red (the one green was the pre-handler `403`). Then green: `HomeRollupTest` 12/12, `HomeReadsIT` 10/10, `HomeControllerTest` 19/19, `HomeEndpointsIT` 7/7 (production boot, real HTTP, real catalog, real reach), with `SchemaMirrorLockTest` (35, one new case for both operations), `ReachOperationsMirrorTest`, `ReachClassificationTest`, `OperationCoverageValidatorTest`, `DataPlaneConfigurationTest` and the three arch-tests (`PlaneBoundaryTest`, `ApplyPathBoundaryTest`, `CompositionBoundaryTest`) green. *Mutation proofs (m6, each red, restored byte-for-byte, hashes checked):* drop the per-item subset, 2 red (`theSummaryNeverCountsAnInvisibleInstance`, `anEnvironmentBoundOperatorGetsItsOwnCountsNot403`); serialize the two plane reads, 2 red (`theTwoPlaneReadsRunConcurrently`, plus `aDpOutageDegradesTheCountsNotTheCatalog` because a synchronous DP call escapes before the guard); let a DP exception escape, 1 red; add a top-level `stateVersion` to `HomeSummary`, 2 red (`SchemaMirrorLockTest` "HomeSummary required" and `theSummaryCarriesNoTopLevelStateVersionOrState`); count `byHealth` without `health.view`, 2 red; filter `config-diverged` by `health.view`, 2 red.

*Deviations.* (1) The gateway wire type is `HomeAttention`, not the plan's `AttentionList`: `SchemaMirrorLockTest` finds each wire type by its contract schema name, and the schema is `HomeAttention`. (2) The two `dp-api` ports return facts for the gateway to filter, rather than counts, because the DP has no caller reach; the plan's `InventorySummaryService` name is kept. (3) `FakeAuthorizationService` (shared test support) gained `authorizedIdsByPermission`, an additive per-verb override, so a test can hold one verb at an instance and not another.

**Flags (open for the owner).** (1) *Row `stateVersion` of the summary.* The contract requires one per solution row and `OR-55` forbids a top-level one, but a solution row's counts move without the catalog row moving: this lane returns the catalog entry's own stamp, which is truthful for the row's identity and does NOT advance when a count changes. A watermark that moved with the counts would need a version over instances and placement health, which have different stamp sequences; ruling needed if the console must discard a stale summary by this stamp. (2) *What counts as "health not GREEN" for attention.* The plan's text is "health not `GREEN`"; read literally it would list every healthy standby (`INACTIVE_REPLICA`) and, after `OR-101`, every no-probe placement (`NOT_APPLICABLE`). Built: `RED`, `RESTART_EXPECTED`, `unreachable`, `not_exposed`, `missing`, `bootstrap` are attention; `GREEN`, `INACTIVE_REPLICA`, `NOT_APPLICABLE` are not. `RESTART_EXPECTED` is listed because it is not `GREEN` and is a warn-tone state; the owner may rule it out. (3) *`config-diverged` is "the placement's LATEST request is `CONFIG_DIVERGED`".* The state is terminal, so listing every request ever in it would keep a long-superseded divergence on the home page forever; the latest-per-placement rule lets a later request clear it. `APPLY_INDETERMINATE` is always the latest for its scope by the in-flight fence. (4) *A placement or instance nothing has polled is not an attention item and is in no `byHealth` bucket*; the summary says so in `availability` (`degraded`, `polling`) for the instances the caller may see health of. (5) *No declare sweep.* Unlike `listInstances`, these reads do not run the lazy declare sweep, so a registered-but-pending instance appears once something activates it; they stay pure reads. (6) *DP outage counts.* `instanceCount` is a required integer, so a degraded summary carries `0` with the reason in `availability`; a console must read `availability` first. (7) *N+1 on persisted rows.* Both DP reads issue one health query per active instance; fine at v1 sizes, and a single tenant-bound join is the follow-up if the home page is polled hot. (8) `ENDPOINT-INVENTORY.md`'s served rows for the two operations are T12.1's.

*`OR-108` (2), (3) (2026-10-06):* (2) a healthy standby (`INACTIVE_REPLICA`) is not a home attention item - the plan's "health not GREEN" is read as excluding `INACTIVE_REPLICA` and `NOT_APPLICABLE` (`OR-101`), as built (`AttentionServiceImpl`); no code change. (3) the summary row's `stateVersion` versions the row's identity (the catalog entry) only and the console re-reads the summary whole; `console.yaml` now says so (a description edit, no code change).

---

## S11 — Fleet apply (REQ-013, OR-47)

### ~~Task T11.1~~ — moved to **T0.0** (B3)

The fleet design now runs **first in W0**, before the apply contract is fixed (OR-47: *"its own design before the
apply slice"*), and its two contract rows land in T0.1's one reviewed `console.yaml` change (point 25). The earlier
draft's path, `POST /dp/solutions/{solutionId}/fleet-apply` with `x-sm-scope: instance`, could not be authorized:
the path carries no instance id for the interceptor's single pre-handler `require`
(`AuthenticationInterceptor.java:131-158`). The id `T11.1` is retired, not reused.

### Task T11.2 — Fleet apply implementation

**Wave:** W11. **Depends on:** T0.0 and T0.1 merged (design + contract), T1.3 (reach), T6.3 (the apply API),
T2.4 Step 3b (the second tenant list), **T9.3 merged** (both edit `ApplyExecutor.java`: T9.3 first, then T11.2 — sequential
for that file, as V013 precedes V014).
✅ **The nine fleet questions are answered** (owner Step 7, batch 4, `OR-65`, all (a); [`fleet-apply.md`](../../design/backend/fleet-apply.md) §10):
**Q-F1** authorize at submit only — each member's request carries the submitter as `requested_by`, and no later batch
re-authorizes; **Q-F2** an indeterminate member waits until resolved, or settles `failed` once `reconcile_class` is set, and
`SUPERSEDED` at first observation settles `failed` (a **deliberate departure** from "terminal", accepted); **Q-F3** admission is
untouched — a fleet reservation does not block a single `submitApply`; **Q-F4** `members` `maxItems` 100, `batchSize` default 1,
`onFailure` default `halt-remaining`; **Q-F5** a refused fleet is always `403 FORBIDDEN` with `refusedMembers[]`; **Q-F6** members
are filtered by `config.view`, counts cover visible members only, `404` when none is visible; **Q-F7** no fleet cancel or halt
operation in v1 (a known gap, recorded in Results); **Q-F8** refused members are filtered like any member, so the `202`/`403`
is the submitter's record; **Q-F9** `halt-remaining` acts between batches only. ✅ **Batch advancement is no longer gated** — owner ruling 2026-09-29 (Q-T),
`OR-49`: between batches a tenant may have no in-flight apply, so the scheduler iterates
`tenantsWithActiveInstances()` (called, never re-spelt). **Authorization as ruled** (owner ruling 2026-09-29 (fleet
authorization)): reach pre-check, one `require` per member, refuse-whole by default, `proceedWithAuthorized: true`
for the authorized subset. **Files:** Create `…/sm_data/V014__fleet_action.sql`
(`fleet_action` stamped from `state_version_seq` under its own row lock, OR-35/m8),
`backend/dp-api/src/main/java/com/solace/sm/dp/api/fleet/*`, `backend/dp-core/src/main/java/com/solace/sm/dp/fleet/{FleetService,FleetScheduler,FleetConfiguration}.java`,
`…/apply/persistence/JdbcFleetRepository.java`, `console-gateway/.../FleetController.java`; tests
`…/fleet/FleetServiceIT.java`, `launcher/.../FleetEndpointsIT.java`. **Folded in from `fleet-apply.md` §11 item 2** (design §4.2 (a) — a pre-minted `apply_id` per member. ⚠️ It is the design's
recommendation, **not** one of the owner's Q-F1 to Q-F9 rulings, so it stays a design choice for the S11 slice review to confirm): Modify
`dp-core/.../apply/engine/AdmissionService.java`, `…/engine/AdmissionOutcome.java`, `…/engine/ApplyExecutor.java` and
`…/engine/AdmissionResult.java` (the `admit(ApplyCommand, ApplyId)` overloads; the id lookup under the scope lock **before** the
blocking check; the new `AlreadyAdmitted` variant on both sealed types; the two casts it would otherwise break, `ApplyExecutor.java:164`
and `:227`), and `dp-core/.../apply/model/ApplyCommand.java` only if T11.2 prefers the design's shape (ii) to shape (i) (under (ii) the 56
constructor sites stay unchanged only if the six-argument constructor is kept as an overload). `ApplyExecutor.java`'s ownership chain is
**T6.4 → T6.9 → T6.8 → T9.3 → T11.2** (Single-owner table).

- [x] **Tests (REQ-013's three bullets, one test each at least):** `aPartiallyAuthorizedPrincipalIsToldWhichMembersAreRefused()`,
  `everyMemberHasItsOwnApplyIdJournalAndAuditLineAndTheFleetIdTracesThemAll()`,
  `oneFailingMemberNeitherStopsNorHidesTheOthersUnderContinue()`, `haltRemainingStopsOnlyUnadmittedMembers()`,
  `theNextBatchWaitsForTheCurrentOneToBeSettled()` (renamed from "…Terminal" under the Q-F2 ruling; it adds the case of an
  `APPLY_INDETERMINATE` member whose reconciler gave up — settled `failed` but not terminal), `aFleetWithNoMemberInFlightStillAdvances()` (B4, through
  the second tenant list), `aPartlyRefusedFleetIsRefusedWholeByDefaultAndNothingIsApplied()`,
  `proceedWithAuthorizedAppliesOnlyTheAuthorizedAndReportsTheRest()`, `theFleetSchedulerReadsNoTenantOutsideItsSource()`, `eachMemberIsAuthorizedByItsOwnRequire()`
  (reworded per `fleet-apply.md` §11 item 5 so it no longer contradicts `OR-57`'s refuse-whole default: *each member is refused by its own
  `require` at its own instance — `cache-eu` `NOT_FOUND`, the others authorized — not by one fleet-level `require`*; the whole-call outcome is
  asserted by `aPartlyRefusedFleetIsRefusedWholeByDefaultAndNothingIsApplied()` and
  `proceedWithAuthorizedAppliesOnlyTheAuthorizedAndReportsTheRest()`),
  `aFleetTransitionStampsFleetAction()` (m8), `reachableInTheProductionBoot()`.
  **Also (from `fleet-apply.md` §11 item 2, each with a planted-violation proof):**
  `aPreMintedIdIsAdmittedOnceAcrossACrashBetweenAdmissionAndRecord()` (crash after pass (ii) commits and before (iii); the next tick
  leaves exactly one `apply_request` for the member under its pre-minted id, recorded `admitted`; violation: the fleet passes no id, so
  admission mints a fresh one — red on the "under its pre-minted id" and "recorded `admitted`" assertions);
  `reAdmittingAnAdmittedIdAnswersAlreadyAdmittedInEveryRequestState()` (the member's own request in `REQUESTED`, `RUNNING`, `APPLIED`,
  `STAGED` and `FAILED_NO_EFFECT`; `ApplyExecutor.admit(command, id)` answers `AdmissionResult.AlreadyAdmitted(id)` in every one;
  violations: (1) move the id check after the blocking check, (2) separately remove it, (3) map `AlreadyAdmitted` through `admit`'s
  existing cast → `ClassCastException`); `aRefusedAdmissionIsRecordedAdmittedWhenTheMembersRequestExists()` ((iii)'s re-read, driven with
  `Refused(SCOPE_BUSY)` directly — T11.2 picks the seam; violation: remove the re-read);
  `onlyTheFleetSchedulerAdvancesAndOnlyUnderTheWorkerSingletonGuard()` (violations: call `advance` from `submit`, and separately start the
  scheduler without the guard); `aSecondRunningFleetOnAReservedPlacementIsRefused409()`, `aSettledMemberNeverChangesItsSettlement()`,
  `aMemberDataPlaneCheckNeverRunsBeforeItsRequire()` and `aMemberShowsNoApplyIdUntilItIsAdmitted()` (§7). The earlier concurrent-advance
  test is **withdrawn** (its two planted violations could not fire, because each defence covers the other); if T11.2 keeps a concurrent
  test its claim is narrowed to *re-admission of an already-admitted id*, with both advancers holding the member `pending` before either
  admits (a latch, not timing) that also fixes the commit order of the two (iii)s, and the only violation that turns it red is removing
  **both** defences — the test says so (§5.5). Mutations: admit all batches at
  once → the batch test red; drop a refused member from the report → the first test red; authorize the fleet once at
  the reach pre-check only → the per-member test red; apply the authorized subset without `proceedWithAuthorized`
  → the refuse-whole test red; iterate `tenantsWithNonTerminalWork()` instead of the second list → the no-member-in-flight
  test red. Revert each. Commit `feat: fleet apply (REQ-013, OR-47)`.

**Results (T11.2, 2026-10-06, on `p010/t112` off `integration/dp-remote-demo` at `41a12e70`; Sonnet 5.5, no full reactor build).** Committed as
`feat: fleet apply (REQ-013, OR-47)`. **Files:** `sm_data` **`V014__fleet_action.sql`** (the lane built it as `V015`, with the plan's `V014` left to T9.3; T9.3 needed no migration, so the S8-S11 merge renumbered it to `V014`, the next free `sm_data` number), `dp-api/.../fleet/*` (`FleetApplyService` + 6 types), `dp-core/.../fleet/{FleetService,FleetScheduler,FleetConfiguration,
FleetProperties,FleetRows,FleetRecord,MemberRecord,MemberRequest,FleetSettlement}`, `apply/persistence/{JdbcFleetRepository,FleetPersistenceConfiguration}`,
`console-gateway/.../{FleetController,FleetOrchestrator,FleetApplyRequest,FleetMember,FleetAccepted,FleetObservation,FleetRefusedException,FleetRefusedBody}` plus
three handlers in `GatewayErrorAdvice`; the engine delta (`AdmissionService`, `AdmissionOutcome`, `ApplyExecutor`, `AdmissionResult`) and one `@Import` line in
`DataPlaneConfiguration`. **No contract change** (`console.yaml` already carried every shape; `FleetSchemaMirrorTest` now pins the wire records to it, equality, with the
closed body, `maxItems` 100 and the three Q-F4 defaults). **Red first (stubs that throw, no migration):** `FleetServiceIT` 22 of 23 red (4 failures, 18 errors; the one
green was the trivially-inert scheduler), `FleetSchemaIT` 13 of 13 red, gateway `FleetControllerTest` 15 of 20 red (the five green were what the real controller,
DTO and advice already did: reach pre-check, closed body, anonymous, classification), `FleetEndpointsIT` 6 of 6 red (no `FleetApplyService` bean: the context failed).
`FleetSettlementTest` (7) and `FleetSchemaMirrorTest` (4) went green first time because the code they pin was written beside them; each is guarded by mutation
instead (below). **Green:** `FleetSettlementTest` 7, `FleetServiceIT` 24, `FleetSchemaIT` 13, `FleetControllerTest` 20, `FleetSchemaMirrorTest` 4, `FleetEndpointsIT` 6 (the real
boot, real HTTP, real scheduler and worker: a fleet that proceeds past a refused member runs both authorized members to `SUCCEEDED`; operator two sees only the refused
`cache-eu`; a viewer and an administrator see all three; another organization's administrator gets `404`; a placement reserved by a running fleet is `409` and is freed
when it settles); gateway unit suite 348/348; dp-core unit suite 586 (1 skipped); arch-tests green with **no change** (`onlyProductionCodeTouchesTheMutatingRuntimeAdapter`
is inverted, so `dp.fleet` is already barred from `RuntimeAdapter`); the four `sm_data` count ITs rebased onto 14 migrations, 16 tables, 4 functions, 4 triggers, 86 named
CHECKs (`SmDataBootstrapIT` 11, `SmMetricsMigratorIT` 4, `ApplyPersistenceConfigurationIT` 4, `RefusalShapesIT` 7) and `DataPlaneConfigurationTest`'s import set; and 28 engine, inventory,
reconcile and wiring ITs plus nine other launcher ITs and `SolutionManagerConfigurationTest` (apply, worker lifecycle, singleton lock, mesh-off boot, master key) unchanged green. **Mutations, each red then restored from the
index (byte-identical):** admit every batch at once → 7 `FleetServiceIT` red; drop `NOT_FOUND` members from the report → 5 `FleetControllerTest` red; authorize at the reach pre-check only (no
per-member `require`) → 7 red; apply the authorized subset without `proceedWithAuthorized` → 3 red; iterate `tenantsWithNonTerminalWork()` → `aFleetWithNoMemberInFlightStillAdvances` and
`theFleetSchedulerReadsNoTenantOutsideItsSource` red; the fleet passes no id → 12 red (crash test, re-admission, refused-recorded, traceability); remove (iii)'s re-read → exactly
`aRefusedAdmissionIsRecordedAdmittedWhenTheMembersRequestExists` red; id check moved after the blocking check → 2 of 5 states red (`REQUESTED`, `RUNNING`: `SCOPE_BUSY`); id check removed → 5 of 5
red (two `SCOPE_BUSY`, two fence, one rethrown primary-key conflict); `AlreadyAdmitted` through `admit`'s cast → 5 of 5 `ClassCastException`; `advance` called from `submit` → 4 red; scheduler
started without the guard → `onlyTheFleetScheduler...` red; submit not waking the scheduler → same test red; `SUPERSEDED` settled `succeeded` → 1 red, `APPLY_INDETERMINATE` settled at once → 2 red;
`halt-remaining` never skips → 2 red; **it also skipping same-batch siblings survived the first draft of the tests** (a pending sibling is only left behind by a crash or an exception, which no test drove),
so `aSiblingLeftPendingByAnExceptionIsStillAdmittedAfterAnotherMemberWasRefused` was added and the mutation then went red; a pending member shown with its reservation id → red; the data plane
validating a refused member → 2 red; `runningFleets` unscoped → red; observe unfiltered → 2 red, observe answering an empty list instead of `404` → 1 red; DDL: settlement rewritable → 2 red,
reservation index dropped → 2 red, member change not stamping its parent → 2 red, runtime `DELETE` granted → 1 red, terminal fleet may return to `RUNNING` → 1 red, a refused member rewritable → 1 red,
the AFTER trigger not touching the parent → 2 red; feature wiring: `FleetConfiguration` out of the aggregator → 6 of 6 `FleetEndpointsIT` red, scheduler never scheduled → 2 red (`SUCCEEDED` never reached; the `409` test cannot release);
wire records: a renamed property → red, `availability` made optional → red. **Deviations and readings, none ruled:**
(1) **`V015`, not `V014`**, as instructed; renumbered to `V014` at the S8-S11 merge (T9.3 took none). (2) **The engine delta is `admit(command, id)` and `admit(command, binding, id)`** on `ApplyExecutor`, one three-argument `admit` on `AdmissionService`, and the `AlreadyAdmitted`
variant on both sealed types; shape (i), `ApplyCommand` untouched; `ApplyExecutor.execute()` refuses an `AlreadyAdmitted` with a sentence. The id check requires the found request to be on the SAME scope, else the insert's
primary key refuses it as the invariant violation it would be. T9.3 also edits `ApplyExecutor.java`: this change is confined to the `admit` region and one branch of `execute()`. (3) **The seam for (iii)** is `FleetService.Admitter`
(production wires `executor.admit(command, MUST_BE_STAGED, id)`), which is what lets `aRefusedAdmissionIsRecordedAdmittedWhenTheMembersRequestExists` hand (iii) `Refused(SCOPE_BUSY)` with the request existing. (4) **`FleetServiceIT` and `FleetSchemaIT` live in
`apply.persistence`'s test package**, not `.../fleet/`: `SmDataPostgres`, `ApplyEngineHarness` and `InventoryFixtures` are package-private there, as for `HealthPollerIT`; `FleetSettlementTest` is in `.../fleet/`.
(5) **A member's batch is `ordinal / batchSize`, literally** (§4.3's sketch); a refused member keeps its ordinal, an empty batch is passed over inside the same pass, and `currentBatch` jumps to the next non-empty one.
The alternative (rank among the authorized, so `batchSize` always means "this many applied at once") needs the owner to say so. (6) **A fleet whose last batch fails under `halt-remaining` with nothing left to skip lands
`COMPLETED_WITH_FAILURES`**: §5.2 names three verdicts and this case fits none of them as worded. (7) **The lock-order wording of §4.3 is not enforceable in a trigger.** PostgreSQL has locked the member tuple before a
BEFORE ROW trigger runs, so a trigger's `FOR UPDATE` on the parent comes second whatever it says; the service takes the parent first (`FleetRows.lock`), and the database guarantees the PROPERTY the rule exists for
(stamp order is commit order within a fleet) through the AFTER trigger's parent update. The BEFORE-trigger lock was written, proved an equivalent mutant (removing it stayed green) and was removed; the migration's comment says
what the database does and does not do, and `fleet-apply.md` §4.3's sentence should say the same. (8) **A duplicate `(instance, placement)` is checked over EVERY member, refused ones included**, before any read: it is a property of the body alone and
reveals nothing, and a duplicate among stored refused members would otherwise surface as a `500` from `fleet_member_named_once`. (9) `observeFleetApply` answers `availability: {state: live}` (the contract requires it on this body only) and no `mode`.
(10) No `sm.dp.fleet.max-age` (Q-F1 (a) is the ruling; (c)'s bound is a later option). **Flags for the integrator:** shared files touched are `DataPlaneConfiguration.java` (one `@Import`), `DataPlaneConfigurationTest.java` (its expected set),
`GatewayTestSupport.java` (`FakeAuthorizationService.outcomeByResource`, a per-resource, per-verb outcome: the scenario needs one instance refused and another not), `GatewayErrorAdvice.java` (three handlers) and the four count hunks above, each a rebase hunk;
`ENDPOINT-INVENTORY.md` is T12.1's and still lists the two operations as unserved; **Q-F7 stays a known gap** (no fleet cancel or halt). **Not touched:** `contracts/`, `frontend/`, any other worktree. Docker: this task started no container of its own
beyond Testcontainers' shared `postgres:14`, which Ryuk removed; the containers listed by `docker ps` while it ran belong to other lanes.


### OR-108 follow-ups

**Results (2026-10-06, lane `p010/or108`).** Items (1) and (3)-(7), one commit each, tests red first and mutation-proved, targeted runs only; (2) and (8) are one-line notes under T10.5 and T10.2.
*(1)* `V015__apply_attempt_preimage_change.sql` (built as the placeholder `V016`; renumbered `V015` at the wave-2 merge and re-pinned in `CHECKSUMS.sha256`) adds `apply_attempt.pre_image_change` with `pre_image_change_shape` (paths only, at most 50, each in the replica-ref grammar so no content fits; a change must name a path or say other content changed) and `pre_image_change_only_for_rollback_steps`. `ApplyExecutor.reportPreImageChange` keeps the change with the attempt that found it (`ROLLBACK_ASSESS`, or `ROLLBACK` when it appears between assess and restore), once, best effort, after the attempt is closed. `ChangedContent` sorts, de-duplicates, bounds and grammar-checks it (a non-path string counts as `otherContentChanged`). The observation step carries `changedPaths`, `changedPathsTruncated`, `otherContentChanged` (`console.yaml`, optional, omitted when none); a readiness check refuses a database without the column. Tests: `ChangedContentTest` 5, `AttemptPreImageChangeIT` 6, `ManualApplyIT` 21 (assertions added to the three OR-102 cases), `ApplyServiceGateProvedTest` +1, `ApplyControllerTest` +1, `ApplyPersistenceConfigurationIT` +1; migration-count assertions moved (`15` migrations, `88` CHECKs, `15` updatable `apply_attempt` columns). Mutations, each red then restored: not recorded, column not read, once-only fence, tenant bind, `$.paths[50]`, path grammar, step restriction, gateway and service mapping, `ChangedContent` cap and grammar, readiness column name. **Not covered:** the reconciler's inspection path logs a changed pre-image but opens no attempt, so the observation shows a change only where an assess or restore attempt found it.
*(3)* `HomeSummary` row `stateVersion` description added in `console.yaml`. *(4)* `fleet-apply.md` section 5.2 item 4 (and 5.4) amended; `FleetServiceIT.aHaltRemainingFleetWhoseLastBatchFailsLandsCompletedWithFailuresNotHalted` pins it (built behaviour, so its red is the mutation: `verdict` returning `HALTED` on any failure turns it and the `continue` case red).
*(5)* `LogServiceImpl.pageBackwards`: with only `before`, one `--since before - W --until before` window (`sm.dp.logs.before-window`, default 15 minutes), newest lines kept, stepping back until `limit` lines or `sm.dp.logs.before-max-windows` (default 8) windows; `--since` is sent a nanosecond early so an edge line is read once; a window over the byte bound is halved to a 1 s floor instead of answering `unavailable`. Closed command set unchanged. `LogServiceImplTest` 18 (6 of the 9 new red first). Mutations: edge offset -> 4 red, halving removed -> 2 red. **Flag:** a page shorter than `limit` means the bounded look-back ended, not the log; the contract has no cursor to say which.
*(6)* `AcceptRuntimeOrchestrator.compose` unsets an absent declared field only when the current projection shows it; an inactive field keeps its stored value (`AcceptRuntimeControllerTest` 28, 1 red first; `accept-runtime.md` section 3.3 item 5 amended).
*(7)* `JdbcRollbackTargetRepository`'s one `ELIGIBLE` clause adds `INDETERMINATE_RESOLVED` with outcome `applied`; `RollbackTargetsIT.resolvedAppliedIsExcludedAsTheRulingListsOnlyThree` is flipped to `resolvedAppliedIsEligibleAsARollbackTargetOR108` (+ the apply-id form and a `not_applied` still-refused case; 15 run, 2 red first; dropping the outcome clause turns the `not_applied` case red); `RollbackEndpointIT` order 8 flipped likewise; `console.yaml`'s eligibility text names the fourth case.
*Integrator.* `V016` was a placeholder: renumbered to `V015` (the next free `sm_data` number) at the wave-2 merge, re-pinned, and the version lists in `SmDataBootstrapIT` and `SmMetricsMigratorIT` moved with it (the counts were already 15). Launcher ITs `ApplyEndpointsIT`, `AcceptRuntimeEndpointIT`, `RollbackEndpointIT`, `LogsEndpointsIT`, `RestoreRuntimeEndpointIT`, `FleetEndpointsIT` (57) and the arch-tests `*BoundaryTest` (59) green; `contracts/run-checks.sh --strict` green; no new dependency.

---

## S12 — Close

### Task T12.1 — Inventory, parity, Results, adversarial review

**Wave:** W12. **Parallel:** ⛔ alone.

- [x] **Step 1: `ENDPOINT-INVENTORY.md`** — every operation this plan served moves to "Served now", **each citing
  the launcher-level IT that proves it**; anything built-not-served says so.
- [x] **Step 2: plan 007 Task 13a's parity checklist** (`007-…md:1444`) as a test: every overlapping demo capability
  (auth, solutions, instances, designer, config, apply, restart, health, ladder, logs) has an authorized production
  endpoint. ⛔ Metrics/dashboards/Explore are excluded (plan 011's).
- [x] **Step 3: Full gate** (run 2026-10-07 on `p/w4` after the wave 4 fix round; Results section 2 and 8). `mvn -o -f backend/pom.xml clean verify` (with the Testcontainers override) ·
  `./contracts/run-checks.sh --strict` · `git diff --check` · the DDL replay harness **only if** a task changed
  `eda-walking-skeleton.md`'s SQL block (`docs/design/backend/evidence/ddl-replay/README.md` — it replays the
  design's block, not the migrations).
- [x] **Step 4: Update `console-integration.md` §14** from planned to measured.
- [x] **Step 5: Results** below — exact counts per module, every skip with its reason, every mutation and whether it
  fired, every flag still open (OR-31/32/40/42; T2.1's host-key uniqueness (now per organization, OR-89; T7.1 carries the several-key-types residual); T8.1's excluded state;
  D-LOG's audit-verb flag; T6.8's supersession of PROPOSED `BE-D12`/`BE-D16`, its reconciler-identity and
  unstamped-bookkeeping readings, and the conditional write's residual non-atomic window; T1.2's structural-vs-byte
  golden result; the second review's owner questions Q-L1/L2, Q-R1–R4, Q-G1/G2, Q-C1–C3 with how each was
  answered), plus **the 2026-09-29 rulings as
  recorded, not open** — Q-E, Q-T, Q-R, S0-8/10/11/15/22/24, fleet authorization, T0.2, verify-then-retry — each with
  its registered `OR-*` id, and how T0.1 Step 7 resolved the landed-slice divergences (points 12, 16, 17, 19) and point 18's addition; Docker state
  before and after.
- [x] **Step 6: The combined Fable 5.1 review** (owner ruling 2026-10-05, `OR-70`: **one** review by Fable 5.1 after all slices
  S0 to S12, over every slice's diff; it **replaces** the independent adversarial review this step used to call for, the per-slice Opus reviews having
  already run) — specifically: a route counted served without a production-boot
  IT; a **third** cross-tenant read, or the second one re-spelt or used by a caller the Q-T ruling does not name; a
  branch on a solution name where a package declaration could carry it; a reconciler write over content SM did not
  write; a gate reported as proving `readiness` for a liveness-declared probe; a secret in any row/log/response/hash input unkeyed; a CP↔DP import or a
  cross-schema join; a controller touching a transport; a poll introduced where an event exists; a flagged ruling
  described as ratified; the demo module reachable from production.
- [ ] **Step 7: Commit.**

---

## Rollback and forward-only changes

- **`sm_control` V013 is forward-only once published** (OR-22). Before it is pushed, a defect is fixed by editing
  it; after, only by a new migration. Its privilege widening (runtime `INSERT` on `instance`/`scope`/`solution`) is
  reverted, if ever, by a new `REVOKE` migration — and every declare path then fails closed at the trigger/grant.
- **`sm_data` V007–V014 are forward-only** in the same way by convention (no down-migrations exist in this repo);
  pre-production, a development database may be recreated instead, and each time it is, the commit says so.
- **Customer hosts:** every host write is an apply through the journal, so `BE-D22`'s retained pre-image and the
  bounded-retry exhaustion path are the rollback; staging never writes a host; rollback (S8) and restore-runtime
  (S10) are themselves applies. No task writes a host outside the engine (T6.7 enforces it).
- **Secrets:** losing the master key is not recoverable by SM (D-KEY §4) — the runbook says so before the first
  production install.

## Acceptance

This plan is complete when **every** row holds, each proven by the named evidence:

| # | Criterion | Evidence |
|---|---|---|
| 1 | Every operation in `console.yaml` except the auth four, the hierarchy three (already served) and the `501` rows is **served by the production launcher**: `listSolutions`, `loadPackage`, `listTargets`, `listInstances`, `declareInstance`, `getInstance`, `getDesigner`, `stageConfig`, `getConfigHistory`, `submitApply`, `observeApply`, `rollbackApply`, `stopInstance`, `getHealth`, `getConfigLadder`, `readLogs`, `getLogLevel`, `setLogLevel`, OR-45's five, and fleet apply | one launcher-level `*EndpointsIT` per operation, over real HTTP against PostgreSQL |
| 2 | The first E2E slice (OR-46) ran against a real SSH-Docker Cache instance with `instance.apply-outcome` received on a local broker | `SkeletonWalkthroughIT` run (not skipped) + `evidence/010-first-e2e-walkthrough.md` |
| 3 | Backend resilience scenarios 1–3 and 11 pass, 11 against the real SSH-Docker adapter | `MeshResilienceIT` |
| 4 | Java `project()` is byte-identical to the TS engine on all 33 fixtures (structural equality is ✅ landed; byte identity needs T1.2's remaining check — until it lands, Results says "structural only"); the validator agrees with the Python gates on every positive, negative and overlap fixture, T0.1a's included | `DesignerProjectorFixtureTest` (+ T1.2's byte case), `*ParityTest` |
| 4a | An `APPLY_INDETERMINATE` apply is reconciled per the verify-then-retry ruling — confirmed with no host write, re-restarted, re-applied, or left for a human with a stamped, observable alert (event + `reconcileReason`) — from a fresh host observation whose "prior" is the retained pre-image; foreign content is never overwritten; inspections and re-drives are both bounded; no lock is held across SSH | `IndeterminateReconcilerIT`, `IndeterminateReconciliationIT`, T6.6 scenario R1 (or its recorded skip reason) |
| 4b | `rolling-health-gated` gates on the package-declared probe SM executes (never on a fallback signal), the file is read back and verified after the restart, and the API reports the strength the gate proved; no code path branches on a solution name | `RollingHealthGatedIT`, `ProbeBackedHealthTest`, `PostRestartVerifyTest` |
| 5 | No secret value in any table other than `sm_secret` ciphertext, any log, response, event, or unkeyed hash | T2.4/T10.1/T10.4 scans + the review |
| 6 | Every new `sm_control` write is OR-25-audited and proven as `sm_cp_runtime`; V013 is pinned and (post-push) anchored | `GovernanceSchemaIT`, `MigrationChecksumsTest`, check 12 |
| 7 | `arch-tests` green including T6.7's rules, every rule with a planted-violation proof | `VERIFY arch-tests` |
| 8 | REQ-013's three acceptance bullets each have a passing test | T11.2 |
| 9 | Every flag in the header is still listed as open in Results unless ratified; every S0 design point and former owner question is recorded as **ruled** (2026-09-29) with its registered `OR-*` id; every landed-slice divergence (T0.1 points 12, 16, 17, 19) and point 18's addition is resolved at T0.1 Step 7 and the dependent task matches the resolution; every second-review owner question is answered and registered | Results |

## Non-goals — stated exactly

| Not here | Why / where |
|---|---|
| Browser live notify, mesh tickets, `LiveStateClient` | gated by open items 32/33/35; plan 007 Tasks 7/8/10 and the frontend |
| OR-6's topic taxonomy migration of all seven events | a separate reviewed `contracts/events/` change; T0.2 follows the gated taxonomy deliberately |
| Multi-placement apply, `apply_step_intent` placement identity | plan 008 Tasks 2–7, renumbered by T0.3 |
| Agent path: auto-registration, `host-local` | `AGW-D*` / `AG-D*`, unbuilt; this plan leaves the quota and governance ports it will use |
| Package signing | phase 2 (OR-40); packages are recorded **unsigned** |
| Undeclare, package actions, `restart-all`, `rolling-no-validation`, `scheduled` | no verb (open item 39) — `501`. (`rolling-health-gated` is **in** scope since owner ruling 2026-09-29 (S0-10): generic, package-driven, T6.9; open item 15 is answered by declaration) |
| A package declaration for MIs that hot-reload their config, or that expose Actuator loggers | routed schema changes if needed (T6.2 Step 0, T9.2 point 6); nothing branches on the solution meanwhile |
| Target creation over HTTP | no contracted endpoint (§5.5); the setup command is the v1 path |
| The drop-counter metric export | no metrics stack chosen (plan 007 Measured facts); routed |
| Metrics, dashboards, Explore, deleting `demo-launcher`/`demo_control` | plan 011 |
| Admin screens' endpoints | an Admin plan |
| Frontend cutover, deployability with the current UI | plan 007's release gate |
| A transactional outbox | no event is authoritative (OR-3) |
| A stop-outcome notice (`STOPPED`/`STOP_FAILED` on the mesh) | OR-4 approves an **apply** outcome with a required real `placementId`; an instance-wide stop has none (T0.2, M10). Ruled (owner ruling 2026-09-29 (T0.2)): stops are REST-observed — the browser polls `observe?applyId=` ~2 s until terminal, declared; a notice is a separate owner question |
| Journal (`apply_request`) pruning | nothing prunes the journal today; it is what makes the ruled Q-R (a) bound grow — "a later decision" per the ruling |

---

## Results

*Written at T12.1 (2026-10-07) from the per-task Results above; nothing here was re-run. The per-task paragraphs
remain the detailed record. The full gate (T12.1 Step 3), the Docker state and the combined Fable 5.1 review (Step 6)
are the integrator's and are recorded by the integrator beneath this section's last table.*

### 1. What this plan delivered

Every operation the plan owned is **served by the production launcher**, each proved by a launcher-level IT that boots
the real `SolutionManagerApplication` over PostgreSQL: the evidence is the "Proved by" column of
[`ENDPOINT-INVENTORY.md`](../../../contracts/openapi/ENDPOINT-INVENTORY.md) (Step 1). Plan 007 Task 13a's parity
checklist is now a test, `DemoParityChecklistIT` (Step 2, section 5 below). `console-integration.md` section 14 is
rewritten from planned to measured (Step 4). The OR-46 first end-to-end slice ran live (T6.6 run 3, 2026-10-06:
`SkeletonWalkthroughIT` 9 run, 0 failures, 1 skipped; `MeshResilienceIT` 7 run, 0 failures, 0 skipped), and its claim is
limited to what its evidence file states.

### 2. Test counts — and which build they come from

**The final full gate (T12.1 Step 3), 2026-10-07, on `p/w4` (head `63a95286`, after the wave 4 fix round):**
`mvn -o -f backend/pom.xml clean verify` with the Testcontainers override, **BUILD SUCCESS**, all ten modules, 40 minutes;
`./contracts/run-checks.sh --strict` **ALL CHECKS PASSED** (step 14 against a sibling worktree's Ajv 8 through
`SM_FRONTEND_NODE_MODULES`, because the primary checkout's `frontend/node_modules` holds only the hoisted Ajv 6);
`python3 contracts/openapi/postman/check-postman.py` OK (46 operations: 45 served, 1 wired-not-counted-served, 0 not
served); `python3 backend/scripts/check-frozen-migrations.py` PASS (33 migrations byte-identical); `git diff --check
integration/dp-remote-demo...HEAD` clean. **The first run of this gate failed** (2 failures) and is recorded in section 8.

| Module | Surefire (unit) | Failsafe (IT) | Skipped (surefire + failsafe) |
|---|---|---|---|
| cp-api | 48 | - | 0 |
| dp-api | 0 | - | 0 |
| mesh-events | 160 | - | 0 |
| cp-core | 462 | 287 | 0 |
| dp-core | 1080 | 1134 | 1 + 12 |
| console-gateway | 598 | - | 0 |
| launcher | 77 | 289 | 0 + 27 |
| demo-launcher | 6 | - | 0 |
| arch-tests | 76 | - | 0 |

Failures 0 and errors 0 in every module. These replace the table of the slice-3 close (`p010/merge2`, 2026-10-05: cp-core 274 / 253,
dp-core 360 / 516, console-gateway 195, launcher 5 / 46, arch-tests 43), which is stale for every module S5-S11 and plan 011
touched.

*Latest recorded **targeted** counts after that build (not a full build, not additive; a lane's own report):*
dp-core surefire 636 with 1 skipped and gateway 356 (T10.4); arch-tests 56 (T6.7) and the `*BoundaryTest` set 59
(OR-108); cp-core 324 (T10.2); `ConfigRecordServiceIT` 30 and `DockerAdapterContractIT` 26 (T10.4; Docker was available);
dp-core failsafe 586 with 5 skipped (T6.9 lane `-am verify`); T7.2's lane ran a full `verify`, BUILD SUCCESS, 0 failures,
count not recorded. `DemoParityChecklistIT` (T12.1): 1 test, green, red under a planted route change (section 5).

**Every skip and its reason, from the final gate's reports** (each is a JUnit `@EnabledIf...` / assumption naming a system property
this build does not set; no customer broker or remote host was contacted):

| Module | Skipped | Test | Reason |
|---|---|---|---|
| dp-core surefire | 1 | `InMemoryRuntimeAdapterContractTest` | an assumption, no message (the pre-existing unit skip carried in every count) |
| dp-core failsafe | 1 | `ApplyOutcomePublishIT` | needs `-Dbroker.host` (the G1 spike broker) |
| dp-core failsafe | 1 | `JcsmpEventPublisherIT` | needs `-Dbroker.host` |
| dp-core failsafe | 1 | `MeshLifecycleIT` | needs `-Dbroker.host` |
| dp-core failsafe | 2 | `CacheApplyWalkthroughIT` | needs `-Dsm.demo.cache=true` |
| dp-core failsafe | 7 | `MetricsStorageBenchmarkIT` | needs `sm.bench.url` (the storage gate's scaled run, P11-8.1) |
| launcher failsafe | 1 | `CatalogEventIT` (broker case) | needs `-Dbroker.host` |
| launcher failsafe | 7 | `MeshResilienceIT` | needs `sm.walkthrough.ssh.host` (an operator-provided SSH Docker target, T6.6) |
| launcher failsafe | 9 | `SkeletonWalkthroughIT` | needs `sm.walkthrough.ssh.host` |
| launcher failsafe | 7 | `MetricsCollectionLiveIT` | needs `sm.walkthrough.ssh.host` (P11-4.4's live run) |
| launcher failsafe | 2 | `MetricsGateBenchmarkIT` | needs `sm.bench.gate.url` (the EC2 gate, P11-8.1 Step 3) |
| launcher failsafe | 1 | `SkeletonWalkthroughRehearsalIT` | the browser live notify: open items 32/33/35 |

The three rehearsals ran against the local broker image `solace-pubsub-enterprise:10.25.0.177` (`MeshResilienceRehearsalIT` 7, `MetricsCollectionRehearsalIT` 7, `SkeletonWalkthroughRehearsalIT` 9 with 1 skipped). The 12
dp-core failsafe skips and 27 launcher failsafe skips above are the full list.

### 3. Mutation-proof status per task (summarized from each task's Results; not re-run)

* **Mutation-proved, every listed mutation red then restored byte-for-byte:** T0.2 (3), T0.4b (full list), T1.1, T1.2,
  T1.3, T2.5, T2.6, T2.7 (in place of red-first), T3.1, T4.4 (9), T5.1, T5.2, T6.7, T6.8 (16 plus extras), T6.9, T7.1,
  T7.2, OR-101 (3), OR-102 (8), OR-105 b/c/d/e, T8.1 (5), T9.1, T9.3, T9.4, T10.2 (7), T11.2 (about 30, one added after
  a mutation survived the first draft of the tests), OR-108 items (1) and (3)-(7). Documented equivalent mutants:
  T9.3 (one, defence in depth), T11.2 (the BEFORE-trigger parent lock).
* **Mutation not reported by the lane, box left unticked in the task:** T3.2 Step 5, T4.2 Step 5, T4.3 Step 5.
  **No lane record carried:** T6.1 and T6.2 (the integrated suite is their evidence). These are known gaps, not claims.
* **Not applicable (docs or no behaviour change):** T0.0, T0.3, T2.3, T3.0, T4.1, T10.3.
* **Not cleanly proven:** T9.4's copy/admission transaction split (the mutation hung rather than failing; proved for
  restore by `ApplyServiceRestoreRuntimeIT`); T10.2 mutation (c) likewise hangs by design (the self-deadlock the lock-order
  rule exists to prevent).
* **T12.1:** `DemoParityChecklistIT` -- planted mutation, `HealthController` route renamed `/health` to `/healthz`: the
  test failed with `health / getHealth: path /api/dp/instances/{instanceId}/health not registered`; restored (`git
  checkout`), then green.

### 4. Flags still open, each with its source

| Flag | Source | Status |
|---|---|---|
| `OR-31` amends LOCKED `D2` (stored SSH credentials, inbound SSH) | plan header; `DECISIONS.md` | **open** - owner-ruled, architect-group ratification pending |
| `OR-32` names `I6` (keyed hash depends on resolved values) | plan header | **open** - accepted reading, ratification pending |
| `OR-40` implements `B/D4` partially (no package signing; packages recorded unsigned) | plan header; T3.2 | **open** |
| `OR-42` names `B/D12` (re-apply a prior generation read as re-applying its content) | plan header; T8.1 flag (a) | **open** |
| T2.1 host-key uniqueness | T2.1 Results | per organization, **`OR-89`**; T7.1 closed the several-key-types residual (every offered key type is recorded; a registration-command change, flagged there) |
| T8.1's excluded state (`INDETERMINATE_RESOLVED` with outcome `applied`) | T8.1 flag (c) | **resolved, `OR-108`**: it is an eligible rollback target (T8.1 item (7) of the OR-108 Results) |
| D-LOG's audit-verb flag | T9.2 / T9.4 | **resolved, `OR-109`**: `logs.setLevel` alone authorizes a configured level change |
| T6.8: supersession of PROPOSED `BE-D12`/`BE-D16` | T6.8 | **open** - `BE-D34` supersedes by owner ruling (`OR-59`, `OR-63`, `OR-64`), not by ratification |
| T6.8: reconciler identity (a fixed non-principal resolver token) and the unstamped bookkeeping reading | T6.8 decision draft | **open** - recorded in `BE-D34`, a new meaning of `Resolution.resolvedBy`, unratified |
| T6.8: the re-apply write is check-then-write | T6.8 "May not claim" | **open** residual - the window is narrowed (as `restore` does), not closed; obligation (r) is **not** closed |
| T1.2 structural-vs-byte golden result | T1.2 | **resolved**: byte identity 33/33 on `ViewModelJson`; the served bytes are compared on 15+ cache fixtures by the HTTP IT (T5.3). Residual, routed: the shared fixtures cannot tell coercion from strict equality (a Java-only guard was added); a coercion fixture is a separate `contracts/view-models` change |
| `OR-102` follow-up | OR-102 Results | **built**; open items: records deleted host-side read as `unrecorded` and are refused; a pre-image retained by an earlier build is refused (`rollback.preimage.unrecorded`); the changed paths are in the log and the observation, no UI surface beyond that |
| `OR-104` D-ACCEPT, `OR-103` D-LOG, `OR-106`-`OR-110` | their Results | **ruled** - see section 6 |
| T0.4b: `sisu.plexus:0.9.0.M3` | T0.4b | **resolved, `OR-83`** |
| T0.4b deviations (election enforced in the template, no automatic-election proofs, build plugins covered by `check-licences.py` outside `verify`, `.sha512` not enforced, first-use wrapper resolution) | T0.4b | **open** - for the owner to confirm |
| T4.4: a declare whose CP registration lands after the sweep abandoned its row (500 plus an orphaned CP node) | T4.4 | **accepted as rare, `OR-90`** |
| T6.4: the drop counter is an in-process `AtomicLong`, not exported | T6.4 | **open**, routed (no metrics stack chosen) |
| T6.6: OR-46 claim scope; M4 not observed; Step 4 (two live runs) not made | T6.6 | **open** - the live ITs ran green once; see the evidence file |
| T7.2/OR-101: `V007`'s stale comment about `not_exposed` | OR-101 flag (1) | **open**, harmless: a published migration may not be edited |
| T9.1 flag (i): a `before` over a log larger than 512 KiB answers `unavailable` | T9.1 flag (i) | **resolved by `OR-108` (5)**: `LogServiceImpl.pageBackwards` pages backwards in bounded, halving windows (`sm.dp.logs.before-window` / `before-max-windows`), proved by `LogServiceImplTest.aLargeLogIsPagedBackwardsInWindowsAndNeverDeclaredUnavailableForBeingLarge` and `aWindowThatIsItselfOverTheByteBoundIsNarrowedNotDeclaredUnavailable` |
| T9.1 flag (ii): `component` is validated against types only (one container per placement) | T9.1 flag (ii) | **open** |
| T9.4 / T8.1 / T10.2: a restore or rollback restores references, not secret values; every restore adds a generation | T8.1 flag (d), T10.2 flags (a)-(e) | **open** |
| T10.2: "desired" is the newest generation, applied or not | T10.2 flag (b) | **open** - the owner may want a stricter rule |
| T11.2: `Q-F7` (no fleet cancel or halt) and five unruled readings (ordinal batching, `COMPLETED_WITH_FAILURES`, the trigger lock order, duplicate checks, the `observeFleetApply` availability) | T11.2 | **open** known gap and readings |
| Journal (`apply_request`) pruning | plan Non-goals | **open** ("a later decision") |
| Exported metric for the drop counter; browser live notify; the OR-6 taxonomy migration; multi-placement; `restart-all`/`rolling-no-validation`/`scheduled`; undeclare; package actions | plan Non-goals | **open**, each routed as the Non-goals table states |

### 5. Step 2 — the parity test

`DemoParityChecklistIT` (`backend/launcher/src/test/java/com/solace/sm/launcher/`) boots the production application and
asserts, for 22 checklist rows over plan 007 13a's ten capabilities (auth, solutions, instances, designer, config,
apply, restart, health, ladder, logs), that exactly one handler is registered for the operation, with the contract's HTTP
method and path, a `@ConsoleOperation` whose `OperationPolicy` classification is exactly the expected permission (or
session-only kind), and, for a catalog operation, a resource resolver. Metrics, dashboards and Explore are excluded
(plan 011's). **Findings:** every one of the ten capabilities has a production endpoint. Two readings are explicit in the
test: the demo's `restart` is, in production, an apply whose strategy restarts (`submitApply`, with `instance.restart`
required in-handler for a restarting strategy) plus `stopInstance`; there is **no standalone restart-only route**. Demo
capabilities with **no** production endpoint, each by a recorded decision, are listed in the test and not asserted:
undeclare (no verb, open item 39d), the demo's two-step package confirm/discard (production `loadPackage` is one gated
step), `setup`/`ssh-keys`/`ssh-host-key` (the operator setup commands are the v1 path), multipart `/packages`, and the
demo's single-instance legacy routes.

### 6. Rulings recorded, not open

*The 2026-09-29 rulings:* Q-E `OR-48` · Q-T `OR-49` · Q-R `OR-50` · S0-24 `OR-51` · S0-8 `OR-52` · S0-10 `OR-53` ·
S0-11 `OR-54` · S0-15 `OR-55` · S0-22 `OR-56` · fleet authorization `OR-57` · T0.2 `OR-58` · verify-then-retry `OR-59`.
*The second review's questions (answered at the T0.1 Step 7 sitting, 2026-09-30, licence batch closed 2026-10-05):*
Q-L1 and Q-L2 `OR-66`/`OR-67` (and `OR-68`, `OR-83`, `OR-84`); Q-R1 to Q-R4 `OR-63`; Q-G1 and Q-G2 `OR-64`; Q-C1 and
Q-C2 `OR-61`; Q-C3 `OR-62`; Q-F1 to Q-F9 `OR-65`. *Later rulings this plan executed:* `OR-69` (owner decides for the
architect group), `OR-70` (execution model), `OR-71`/`OR-82` (acquisition, no host), `OR-85`-`OR-90`, `OR-96`, `OR-101`
to `OR-111`.

**How T0.1 Step 7 resolved the landed-slice divergences** (points 12, 16, 17, 19, and point 18's addition): **all
accepted as recommended, `OR-60`**; `console.yaml`'s structure already said what was ruled and the divergence was prose
(27 lines still called ruled items pending), which was rewritten as ratified with check 16 (pins the ruled shapes) and
check 17 (refuses pending-marker prose) added first and seen red; the `Strategy` enum widened to all five tokens.
The dependent tasks (T6.3, T6.8, T6.9, T8.1, T10.2, T10.5) were written to the resolution.

### 7. Open owner questions that exist today

* **(a) Wave 3.** *(W3-01 is ruled and built: `OR-112` (1), see T6.5's note; W3-06 is the other lane's.)* **W3-01:** the metric active-list switch happens only at `restart-observed`, which only a *gated* apply
  reaches (the ladder's `restart-observed` rung requires a gated `APPLIED` request with a matching `VERIFY`, T6.5), so a
  `manual` apply never moves the collector to the new list; this is plan 010 T6.4's flag 2 seen from plan 011. **W3-06:**
  broker connections that share a VPN. Both are findings of the wave 3 review, which lands separately; neither text is
  in this tree at this base, so the question is recorded here by name and the review is the source.
* **(b) P11-8.1's scaled storage gate:** a month of metrics does not fit on the E2E host (**170.19 B/row measured**);
  the numbers and a recommendation go to the owner (P11-8.1 Step 7 always puts M-Q19 (c) to the owner). And the owner's
  scope ruling **`OR-111`**: dp-remote targets **Docker only** for now; the order that follows is plan 008
  (multi-placement) next, then VM.
* Not new but still the architect group's: `OR-31`, `OR-32`, `OR-40`, `OR-42` ratification (section 4).

### 8. Docker state

Recorded at T12.1 Step 3 (the final gate, 2026-10-07). **Before and after both full builds:** `docker ps` lists **no running
container**; `docker ps -a` lists the same eight **pre-existing, exited** `oms-*` containers (`oms-frontend`, `oms-backend`,
`oms-transformer-mi`, `oms-db-mi`, `oms-broker-init`, `oms-xml-tap`, `oms-postgres`, `oms-broker`), none touched, none started.
Testcontainers started and removed its own containers (`postgres:14`, the local broker image for the rehearsals) with Ryuk
enabled, and left none behind. Docker Desktop was used through `tc-env.sh`; Colima and `~/.testcontainers.properties` were not touched.

**The gate failed once and was fixed (wave 4 fix round).** The first full build of the round stopped in `launcher` with two
failures, both fixed in `63a95286` and confirmed by a second, complete, green build:

* `MetricsGateBenchmarkLocalIT`: the W4-11 guard (an answer with no series or points is an EMPTY finding, never MEASURED p50/p95)
  fired on the benchmark's own seed. The test had passed before only because it timed queries that matched nothing. Two seed
  defects in `StorageBenchmark`: the main site declared no `metric_definition` rows (the entity-type filter dropped every series),
  and its placements and series were created at seed time, so the query side (which bounds a series by its placement's life)
  excluded the backdated samples. Both fixed; the IT now asserts series and points above zero for every shape.
* `HealthEndpointsIT.thePersistedRowsAreTheOnlyThingRead` (flaky under load): the 1500 ms assertion timed the login (a password
  hash) as well as the read; it passed alone in 26 s. It now times only the read.

### 9. Combined Fable 5.1 review (OR-70)

**Combined Fable 5.1 review (OR-70), fix round, 2026-10-07 (`p/fable`, base `a93078a6`).** The one combined review after all slices (plan 010 S0 to S12 and plan 011) returned **12 findings: 0 BLOCKER, 3 MAJOR, 9 MINOR**. All 12 are fixed (none disputed, none skipped); each had a test red before its fix, recorded in its commit message.

| Id | Sev | Disposition |
|---|---|---|
| APPLY-01 | MAJOR | **Fixed in `690f7460`.** A config-secret set minted a generation with no carried log levels (the designer stage and accept-runtime kept them). One shared `CanonicalContent.carryingLevelsOf` now serves all three paths; `ConfigRecordServiceImpl.withCarriedLevels` is gone. Red first: `ApplyServiceSetLogLevelIT.aSecretSetKeepsTheConfiguredLogLevel` expected `DEBUG`, got `null`. |
| APPLY-02 | MAJOR | **Fixed in `bf942f03`.** `SshDockerRuntimeAdapter` keeps the newest `placement.retained_generations` distinct generations of its pre-image / fingerprint / absence files (always the current one) after a verified write and removes the rest through `deleteFile`; only names in SM's fixed grammar for the declared file are candidates; a listing or delete failure never fails the apply. New transport method `listHiddenFileNames` (bounded to 4096). Red first: three `SshDockerRuntimeAdapterContractTest` cases saw generations 1-6 where 4-6 were expected. The evidence file no longer says "SM never deletes them". |
| MON-01 | MAJOR | **Fixed in `c1556f04` (+ `645f137f`).** The rollup rule `sample_count < expected_count` flagged nearly every closed hour and day (the collector's period is always a little over the declared interval) and missed a day with an absent hour. `sm_metrics` **`V003`** stores `max_gap_seconds` (longest spacing ending in the bucket, looking back across the edge) and `interval_seconds`; the query judges `max_gap > 2 x interval` (D-3's rule); a bucket following an absent one is not flagged twice; the judgement survives widened folds and pooling. `expected_count` is unchanged. Dated note beside the plan text. Red first: `MetricsQueryServiceIT` drew 48 `partial-samples` bands for a 30.5 s-spaced series, none for a day with an absent hour, and a double flag for a straddling outage. |
| APPLY-05 | MINOR | **Fixed in `24afc99a`** (section 4's T9.1 row: flag (i) resolved by `OR-108` (5), flag (ii) stays open). |
| MON-03 | MINOR | **Fixed in `4205f739`.** An insert whose timestamp is outside the current and next UTC month runs in a savepoint (`IngestRows.Raw.insertBeyondRunway`), so the failed `INSERT` no longer aborts the batch's transaction (25P02 -> database resting -> the batch held forever). Red first: `IngestServiceIT.anOutOfPartitionFirstSampleOfAFreshSeriesDropsOnlyThatSample` got `RetryAfter` and its five successors failed on the resting service. Residual: the empty series row minted for the dropped sample stays (harmless, no samples). |
| code-GW-1 | MINOR | **Fixed in `e0fe8e83`.** Six descriptions said "not yet served" / "awaiting the owner"; `getHomeSummary` now cites `OR-61` as ruled. Check 17 also scans paragraphs with whitespace collapsed and refuses `not (yet) served` (seven hits on the old file, none on the new). Postman regenerated. |
| code-GW-2 | MINOR | **Fixed in `e0fe8e83`.** `logs.loggers.path` and `levelProperty` end in `(?![\s\S])`; two negatives (`gate01-loggers-{path,levelproperty}-trailing-newline`, 76 -> 78) and two `MutantParityTest` cases; cp-core's classpath copy of the schema updated. Red first: `validate-package.py ... --expect-fail` reported both new negatives accepted. |
| code-GW-3 | MINOR | **Fixed in `e0fe8e83`** (`ENDPOINT-INVENTORY.md`: `listSolutions` is a reach pre-check with no per-item filter; the bijection is pinned by check 15 and `ConsoleOperationsMirrorTest`). |
| code-GW-4 | MINOR | **Fixed in `e0fe8e83`** for the three named operations (`observeApply`, `listInstances`, `observeFleetApply` declare `400`), pinned by `DeclaredBadRequestsTest` (red first on `observeApply`). **Not generalised:** every other operation with a UUID path parameter can answer the same `400` and does not declare it; see the owner questions. |
| code-GW-5 | MINOR | **Fixed in `959cbcb1`.** `OperationCoverageValidator` also requires no root and no default handler on a mapping it lets through; two planted-violation tests (root only, default only) were red first. |
| code-GW-6 | MINOR | **Fixed in `959cbcb1`** (comment only: a package with no probe is `NOT_APPLICABLE`, `OR-101`). |
| code-GW-7 | MINOR | **Fixed in `e0fe8e83`** (`readLogs` is **not** redacted; `logs.read` is the control). |

**Owner questions from this fix round (none blocks).** (1) `V003` is a new `sm_metrics` migration: it is unfrozen until the publishing commit's SHA is appended to `frozen-migration-anchors.txt` after the push, and a rollup row written before it has no spacing and is judged by nothing (unknown is not a gap) until its hour is recomputed. (2) MON-01's factor is D-3's 2 x interval, kept from the raw rule; a different tolerance is the owner's. (3) Operations with a UUID path or query parameter other than the three above can also answer `400` undeclared; declaring `400` on all of them (and in the Postman allowed set) is a wider contract change. (4) Still open as already known, not re-reported: W3-01, W3-06, W4-04, W4-05, W4-06, P11-8.1's scaled storage gate, P11-9.1's demo deletion; their impact is no worse than stated.

**Gate after the fixes** (`mvn -o -f backend/pom.xml clean verify` with the Testcontainers override, **BUILD SUCCESS**, all ten modules; the first run of the round failed once, in `SmMetricsMigratorIT`, which pinned two `sm_metrics` migrations - fixed in `645f137f`, then a second complete green build): cp-api 48 / -; mesh-events 160 / -; cp-core 466 / 287; dp-core 1086 (1 skipped) / 1140 (12 skipped); console-gateway 601 / -; launcher 77 / 289 (27 skipped); demo-launcher 6 / -; arch-tests 76. `./contracts/run-checks.sh --strict` ALL CHECKS PASSED (step 14 against a sibling worktree's Ajv 8); `check-frozen-migrations.py` PASS (33 migrations byte-identical; `V003` not yet anchored); `git diff --check a93078a6...HEAD` clean.


### 10. `OR-116` — the identity carries the visible organizations (2026-10-07; lane `p/or116`)

`Identity` (`login`, `getIdentity`) gained **`organizations: [{id, name, subOrganizations: [{id, name}]}]`**, required in
`console.yaml` (new `VisibleOrganization` schema; empty array, never absent). **One rule, not two:**
`HierarchyDirectory.visibleOrganizations` is `ScopeVisibleHierarchyDirectory`'s own `listOrganizations` followed by its own
`listSubOrganizations` for each organization returned, so the field is never wider than the lists and the two agree by
construction. The gateway (`AuthController`) maps it; environments are not included.

**Decision for `mustRotate` (documented in the contract and the Postman README):** the field is **empty while `mustRotate` is
true**, and the directory is not consulted at all. The hierarchy lists answer `428` until the credential is rotated, so the
identity is populated only when the lists would answer; the identity itself is still answered (`login` and `getIdentity` stay
rotation-exempt). The console calls `getIdentity` again after `rotateOwnCredential`. Names are the same placeholder labels the
lists return today (the id's string form, `ScopeNode.placeholderNamed`); a real name column is a separate change and the field
will follow the lists when it lands.

*Red first / evidence.* `IdentityOrganizationsTest` (gateway, 5: wire shape with ids and names, `[]` for no bindings, empty and
unconsulted while `mustRotate` on both `login` and `getIdentity`, login consults the authenticated session);
`HierarchyDirectoryIT` +5 (organization-scoped, sub-organization-scoped, install-scoped administrator, no bindings, and the
identity-versus-lists agreement over five grant shapes); `HierarchyEndpointsIT` +6 over HTTP (organization-scoped operator sees
both sub-organizations, **a sub-organization-scoped operator sees only its organization and that sub-organization, never the
sibling or another organization**, an installation-scope administrator sees its own organization in full, no bindings gives
`[]`, the bootstrap administrator under `mustRotate` gets `[]` on login and identity, and **one test compares the identity
field with `listOrganizations` + `listSubOrganizations` for four principals**); `SchemaMirrorLockTest` pins `Identity` and
`VisibleOrganization` to the schema. `ProductionAuthenticationIT` and `DemoParityChecklistIT` stay green. *Mutation proofs (each
red, restored with `cp`):* **widening the identity field's filter** (sub-organizations read straight from the tree, unfiltered
by the caller's grants): `HierarchyDirectoryIT` 2 failures (sub-organization-scoped and the agreement test) and
`HierarchyEndpointsIT` 2 failures (sub-organization-scoped operator and the agreement test); dropping the `mustRotate` guard in
`AuthController`: `IdentityOrganizationsTest` 2 failures. Postman regenerated (`check-postman.py` OK), `run-checks.sh --strict`
all checks passed, `arch-tests` boundary tests green. The Postman README gained **"Before loading a package"**: an
organization needs an entitlement for the solution or `loadPackage` answers `409 ENTITLEMENT_REFUSED`, granted today by the
operator command `EntitlementMain` (environment-only configuration, the exact invocation with placeholders, its exit codes).
Targeted tests only, per `OR-70`; no full reactor build was run.

---


## Plan review — dispositions

*Independent adversarial review, 2026-09-28, against `3cd0407` (4 BLOCKER · 13 MAJOR · 10 MINOR). Each finding was
re-verified against the repository before it was acted on; the file:line that settled it is given. Nothing below
claims a fix is proven. Each fix is a planned change with its own test and mutation in the named task. The findings
text itself is superseded by this table; it is not reproduced.*

| # | Finding (short) | Verified? | Disposition — where |
|---|---|---|---|
| **B1** | Keyed hash broken on the production path: remote `sha256sum`, grammar/CHECK reject the spelling, `SshDockerConfigEditor` missed, fake-only tests | ✅ `SshjDockerTransport.java:44-66`; `ValueGrammar.java:35`; `AttemptRow.java:39`; `ReplicaObservation.java:26`; `V002__apply_request.sql:105`; `SshDockerConfigEditor.java:61,83`; also `ConfigFileStore.java:99-101` (not in the finding) | **Fixed.** T5.1 was rewritten. `StatusSnapshot` now carries bytes, so the interface cannot express a remote hash. The adapter hashes in the DP, and the key never goes to the host. The spelling is `hmacsha256v<n>:<hex>`, which fits the grammar and CHECK as they stand, so no migration is needed (T2.3 §5; Migration-allocation note). All five hashing sites are in Files, along with the demo. There are two production-path tests: an SSH-free command test that fails on `sha256sum`, and a journal IT for the spelling. T6.6 now asserts the live observed hash. |
| **B2** | Browser picks host paths and container, and an Operator can write anywhere | ✅ `SshDockerPlacement.java:12-13`; Operator holds the four verbs (`V003__role_and_binding.sql:74-80`) | **Fixed.** The immutable envelope `allowed_config_root`/`allowed_container_prefix` lives on `target_connection` and is set by the registering command (T2.1, T2.7). Three enforcers check it: the service `422` (T2.5), a DB trigger (T2.1), and an SFTP-canonical preflight against symlink escape (T6.1, M8). The rule is in Global Constraints and S0 point 8. Tests plant `…/.ssh` and a sibling-prefix path. |
| **B3** | Fleet design after the apply slice; fleet path unauthorizable | ✅ OR-47 and REQ-013 "Trace"; `AuthenticationInterceptor.java:131-158`; `OperationClassification` sealed at four | **Fixed.** D-FLEET moved to **T0.0**, first in W0. Its contract rows land in T0.1 (point 25). Authorization goes through the reach classification plus a per-member `require` (T0.0 §1, T1.3). T11.1 is retired. |
| **B4** | Background loops have no lawful tenant source | ✅ `TenantEnumerationRepository.java:75-82` yields only in-flight-apply tenants; Global Constraints allow one read | **Fixed where it can be; routed where it cannot.** The declare sweep is now lazy and tenant-scoped, run inside declare/list/get (S4, T4.2, T4.4). Retention runs inside `stage` (T5.2). The canary is install-level with no tenant (T2.3). The health poller, fleet advancement and KEK re-wrap need an owner ruling: **Q-T**, with the recommendation to widen OR-44 by one column over `instance_state`. That ruling gates T7.2, T11.2 and T2.4 Step 3b. The E2E critical path does not depend on it. **→ Ruled 2026-09-29 (Q-T): (a), as recommended; the three tasks are un-gated.** |
| **M1** | Organization-scoped lists are `403` for an env-bound Operator; `ScopeVisibility` is off-limits | ✅ `ScopeEvaluatingAuthorizationService.java:41-50`; `ScopeVisibility.java:6`; `gatewayUsesApisOnly` | **Fixed.** New S0 point 24, a reach-filtered classification, built by the new task **T1.3** (`ReachAuthorization`, cp-api). It serves listSolutions, listInstances, listTargets, home and fleet. The org-owned-target consequence is stated in S0 point 22, and T6.6's target is owned at the environment. Owner yes/no is required. **→ Ruled 2026-09-29 (S0-24, S0-22): yes.** |
| **M2** | DP/CP organization split-brain | ❌ **Disputed.** The session organization is the principal's (`JdbcSessionRepository.java:62-69`), and `require` returns 404 for any other organization's resource (`ScopeEvaluatingAuthorizationService.java:36`) | **No split possible.** Recorded in Global Constraints ("`I5` across planes") and T4.4. A pinning test is added (`aDeclareIntoAnotherOrganizationsEnvironmentIs404AndWritesNothing`). |
| **M3** | Setup commands put business logic in the launcher; invocation undefined; T3.2 depends on T2.7's `SetupAuthentication`; no install-admin verb | ✅ `SolutionManagerConfiguration.java:14-16`; `SmControlLockoutRecoveryMain.java:19-62`; `Permission.java` | **Fixed and routed.** Setup commands are now plane-local standalone `main`s with no Spring context (Global Constraints, File structure). Target registration is split into a CP command (`target.create` verb check) and a DP command (T2.7). The entitlement command is independent of T2.7 (T3.2). Its authority is **Q-E**, recommended as the OR-20 operator model with a table-scoped actor exemption in V013 (T2.0). **→ Ruled 2026-09-29 (Q-E): (a), as recommended.** |
| **M4** | Aggregator imports break every launcher boot | ✅ `MeshProperties.java:34-36`; 7 launcher ITs declare their own executor (`ApplyWorkerLifecycleIT.java:60,193-221`); only `application.properties` exists | **Fixed.** `sm.mesh.enabled` defaults to false, gives a declared degraded state, and a partial configuration still refuses to boot (T3.3). A test master key is registered in `TwoSchemaTestDatabase` plus the 3 ITs that bypass it (T2.6). `sm.dp.apply.production.enabled` is off in the 7 ITs (T6.2), backed by new measured-fact row **M16**. `application.yml` is removed from the text. |
| **M5** | `demo-launcher` breaks silently; fifth arch rule cannot go green | ✅ `backend/pom.xml:73`; `arch-tests/pom.xml` omits demo; `DemoInstanceService.java:193-197`; `SshDockerMetrics.java:26`; `SshDockerTransport.java:70-71` | **Fixed.** `VERIFY demo-launcher` is added to the verification commands. The `SshDockerPlacement` change moved from T2.5 to T6.1, together with its transport and the demo. T5.1 and T6.1 name the demo files. The T6.7 rule is split into two scoped rules that are green on today's tree. |
| **M6** | Stamp trigger vs V006 terminal guard; backfill refused | ✅ `V006__terminal_guard.sql:12-21` | **Fixed.** V008 refuses a non-empty journal, so there is no backfill. The stamp skips the compaction fold, which makes the two triggers order-independent, so no firing-order fact is needed (T2.2). Tests cover the fold, STAGED→SUPERSEDED, and "other terminal writes still refused". |
| **M7** | Shared-host uniqueness keyed on target; redundant index; abandon holds names; health-port clash | ✅ V007 DDL as drafted; `abandon` did not retire placements | **Fixed.** Host-key fingerprint is unique across the install, flagged (T2.1). The redundant `(dir,file)` index is dropped, with the reason stated. `abandon` retires placements (T2.5, S4). `placement_health_port_once` is added. Tests are added for each. |
| **M8** | Placement has no probe path or kind | ✅ probe is `{kind,path,port}` (`cache-2.4.package.json:491-495`; schema `$defs/health/properties/probe`) | **Fixed.** `health_kind`/`health_path`/`health_port` are all-or-nothing, and HTTP kinds only (T2.1). They are resolved from the package by the gateway at declare (T4.4) and used by the poller (T7.2). |
| **M9** | T6.1 point 3 misreads `status()` | ✅ `SshjDockerTransport.java:44-58`; `SshDockerRuntimeAdapter.java:102-111`; `8081` only in metrics (`:219-247`) | **Fixed.** Point 3 is struck and a pinning test is added (`statusStillReadsDockerInspectHealth`). The engine health gate stays on `docker inspect` (T6.1, T7.2). |
| **M10** | Apply-outcome includes stop states; an instance-wide stop has no placement | ✅ `ApplyExecutor.java:146-157` (BE-D26) | **Fixed.** The enum is restricted to apply outcomes, with a fourth negative fixture and a mutation (T0.2). Test `aStopLandingPublishesNoApplyOutcome` added (T6.4). A stop notice is listed in Non-goals. |
| **M11** | D-LOG option (a) exposes loggers to the Docker network | ✅ against OR-43 text (`DECISIONS.md` OR-43 row) | **Fixed.** Option struck. The OR-43-consistent options are host-network-namespace with a literal 127.0.0.1, or P2 only. Anything else is routed as an OR-43 change (T9.2). |
| **M12** | Retention not bounded; trigger undefined; N fixed | ✅ no `DELETE` on `apply_request` (`V002__apply_request.sql:148-153`) | **Fixed and routed.** The effective bound is stated (N plus journal-referenced). Pruning runs inside `stage`, tenant-scoped. N is configurable in the service; the trigger enforces only the floor and the reference rule (T5.2). The M14 row is corrected. The predicate is **Q-R**. **→ Ruled 2026-09-29 (Q-R): (a), literal.** |
| **M13** | Parallel waves share files | ✅ 23 files call `RenderedConfig.of`; 35 admitting test files; `ConfigRecordServiceImpl` in T9.4+T10.2; `console.yaml` in W10 | **Fixed.** T5.1 moved to W3 after T2.2 merges. T10.2→T9.4 run in one agent. The fleet contract moved to W0, leaving T10.3 as the sole W10–W11 `console.yaml` editor. The single-owner table gains 5 rows. ⚠️ The `SmDataBootstrapIT` overlap is **disputed**: its `execute(` matches are SQL, not admissions (`SmDataBootstrapIT.java:87-235`). T2.4 owns it anyway. |
| m1 | Validator details | ✅ `fixtures/overlap/`; `validate-package.py:22,26-34,183-217`; `run-checks.sh:131-132`; `engine-edge` is not run by run-checks at all | **Fixed** (T3.1). Correct path; gate-2 production policy (dev mode off → `422`); 6/7/8/11 → `gate1` mapping; parity by parsing the Python's `-> gate…` stdout. |
| m2 | `entitled` cannot change and the withdrawal test cannot be produced | ✅ T2.0 DDL | **Fixed.** `entitled` is dropped from S0 point 20 and the test is removed (T3.2). |
| m3 | Copy-forward + submit lock inversion | ✅ against the T0.3 lock order | **Fixed.** Two transactions, stated in Global Constraints, T0.3, T8.1, T10.2 and T0.0 §5. |
| m4 | Envelope `stateVersion` description | ✅ `envelope.schema.json:23` | **Fixed** (T0.2 Files). |
| m5 | Hash-key scope vs `hash(byte[])` | ✅ `PlacementRef.java:18` carries the tenant | **Fixed.** One key per tenant; `hash(TenantId, byte[])` (T2.3 §5, T5.1), with a two-tenant test. |
| m6 | Vacuous render test; missing mutations | ✅ `dpNeverImportsCp` makes a package read impossible | **Fixed.** Replaced by a positive two-collaborator test (T6.2). Mutations added to T10.2 and T10.5. T11.1 is retired; its contract proof now sits in T0.1 Step 6. |
| m7 | "HKDF not in JDK" asserted | ✅ unmeasured claim | **Fixed.** The claim is removed. Derivation is not relied on, so no fact is needed (T2.3 §5). |
| m8 | `stateVersion` sources for secrets and fleet | ✅ `sm_secret` is not stamped | **Fixed.** The secret response carries no `stateVersion` (S0 point 17, T10.1). `fleet_action` is stamped (T0.3, T0.0, T11.2). |
| m9 | Ownership columns unconstrained; package copied into audit | ✅ T2.0 DDL; `V012:35,178-193`; filemesh fixture is 111,543 bytes | **Fixed.** Composite FKs are added (`solution_package_entry`, `target_ownership_in_org`, `scope_org_id`), and `document` is stripped from `solution_package` audit rows while the digest is kept (T2.0). Tests added. |
| m10 | PROPOSED decisions cited | ✅ `docs/checklists/plan-review.md` item 2 | **Recorded.** A header row lists `SM-D11a`, `SM-D22`, `SM-D23`, `BE-D11` and `FE-D17`, and the checklist item is marked failed on the record under OR-1. |

**Findings that changed what execution may start on (2026-09-28):** three owner questions (**Q-T**, **Q-E**,
**Q-R**) and the S0 design points were made explicit gates.

**Status after the owner's rulings of 2026-09-29:** all three questions and every S0 design point are **ruled**
([Owner rulings](#owner-rulings-2026-09-29--the-former-gating-questions)); at that point no owner question was
open (the second review has since raised new ones — see below). The rulings changed the plan in three places — S0-10 (restart strategies generic and package-driven: T0.1 point 10, new
T0.1a, T6.3, new T6.9), verify-then-retry (new T6.8, superseding plan 005's rule and plan 007 Task 4b's skip —
flagged, PROPOSED), and the second tenant list (T2.4 Step 3b, T7.2, T11.2 un-gated). Two early slices have landed
(part of T0.1; the Java `project()` port). **The next gate is the owner's review of the remaining S0 contract change
at T0.1 Step 7.** The second adversarial pass over the new tasks (T0.1a, T6.8, T6.9), T0.4 and the rewritten
T0.1 was run on 2026-09-29; its dispositions follow.

---

## Amendment review — dispositions

*The second independent adversarial review (Opus) ran on 2026-09-29. It was read-only against `02f72d1` and
covered T0.1 (remaining delta), T0.1a, T0.4, T6.8, T6.9, the wave table, the critical path, the V011–V015
renumbering, and the T1.1/T1.2 "landed" claims. It reported 2 BLOCKER, 9 MAJOR and 17 MINOR findings.*

*Each finding was **re-verified against the repository at `683d802`** before it was acted on. `683d802` is
`02f72d1` plus the owner's REQ-014 allow-list confirmation, and no code differs between the two. The file:line that
settled each finding is given below.*

*What the review found correct, and was not re-litigated:*
- T0.1's "Landed" column matches `9ec6797`.
- The T1.1/T1.2 landed claims hold.
- The `sm_data` numbering has no collision.
- `APPLY_INDETERMINATE → RUNNING` is legal in the DDL.

*Nothing below claims a fix is proven. Each fix is a planned change, with its test and mutation in the named task.
The findings text is superseded by this table and is not reproduced.*

| # | Finding (short) | Verified? | Disposition — where |
|---|---|---|---|
| **B-1** | T0.1a would leave all 17 negatives rejected by gate 1, so nothing turns red | ✅ `negative/generate.py:10` (`BASE` = the Cache fixture); every negative and both overlap fixtures carry the probe (`grep -rl '"probe"' contracts/validation`: 21 files); `validate-package.py:176-180` stops at gate 1; `:193-196` accepts any error; `run-checks.sh` step 3 accepts any failure. Today's `-> gate…` lines were measured: the `06`/`07`/`08`/`11` files already report `gate1` | **Fixed (T0.1a).** Negatives and `overlap/cache-2.0` are regenerated by `generate.py`. `overlap/cache-2.4` (hand-kept, not generated) is edited by hand. The two new cases go into `CASES`. `--expect-fail` now asserts the file name's gate, using the measured mapping. Step 3 requires `gate3`. Step 1 hardens the check green on today's tree **before** the schema change, and Step 2's red is the proof it sees B-1. New mutations restore one stale negative, and show the old any-error check going green over it. |
| **B-2** | T6.8's "prior" = `PreBaseline`, which records a container start, not a file | ✅ `PreBaseline.java:26-29`; `baseline_only_for_lifecycle`; the pre-image and absence marker are host-side (`SshDockerRuntimeAdapter.java:114-176`); `BE-D22` makes a **restore to** absence unjournallable (`decisions.md:1160-1163`) | **Fixed (T6.8).** "Prior" is the adapter's retained pre-image, read from the host as part of the same fresh observation and compared with the keyed verifier. A vanished or altered pre-image, or none at all (the cut came before retention), goes to the no-overwrite row. ⚠️ **Partly disputed:** the absent case does **not** contradict `BE-D22`. `BE-D22` forbids journalling a restore *to* absence; a reconciler re-apply writes the intended bytes under `MATERIALIZE`'s own bound hash. Whether absent-recorded counts as "prior" is left to the owner: **Q-R1**. |
| **M-1** | The give-up has no stamped column, so no event and no observation | ✅ `V002__apply_request.sql:6-39` (no classifier column), `:148-153` (six-column `UPDATE`); `land()` persists only the state (`ApplyExecutor.java:771-774`) | **Fixed.** V011 adds `apply_request.reconcile_class`, with the runtime's `UPDATE` on that column only. T2.2's trigger stamps it (it stamps everything but the fold, T2.2 step 3). `OutcomeService.recordReconcileGiveUp` writes it and calls T6.4's emitter. `observeApply` gains `reconcileReason` (T0.1 point 3). T0.2's sentence now names the column. Test and mutation are in T6.8. |
| **M-2** | Two clocks compared ("`StartedAt` after the attempt time") | ✅ `HealthGate.java:82-85` compares host-observed baselines only (`!equals`) | **Fixed (T6.8).** "Restarted by this request" is decided as `HealthGate` decides it: the current baseline differs from the latest `LIFECYCLE` attempt's `pre_baseline`, and no `LIFECYCLE` attempt means not restarted. An unreadable baseline (mid-restart) is undecided: retried, never restarted. Skewed-clock and mid-restart tests and mutations are added. The Docker `StartedAt` semantics are the new row **M18**. |
| **M-3** | Re-drives unbounded; give-up not terminal | ✅ `max-attempts` counted only failed inspections; no due-time rule | **Fixed, with a policy question.** There is a separate `max-redrives` counter, `RetryCapExceeded` on a re-drive is a give-up, and a permanent `gave_up_at` makes the due predicate exclude given-up rows. A flapping-host mutation is added. The defaults and the age bound are **Q-R3** (recommendation (a)). |
| **M-4** | Scope lock held across SSH; the re-apply is check-then-write | ✅ `AdmissionService.java:31-35` (stop exempt from the fence); `materializeConfig` writes without re-checking (`SshDockerRuntimeAdapter.java:73-85`), unlike `restore` (`RuntimeAdapter.java:94-109`); obligation (r) unproven (`ApplyExecutor.java:397-412`) | **Fixed (T6.8).** The reconciler observes with no lock, then takes the scope lock only to re-check (no later request, same `state_version`) and transition, then acts as `RUNNING` through the normal step loop. The re-apply uses a new seam operation, `rematerializeIfCurrent`, which re-checks immediately before writing, as `restore` does, with its own `«next free BE-D»`. Its residual non-atomic window is stated in "Must not claim". A stop arriving mid-re-drive ends the re-drive by observation (`ApplyExecutor.java:422-450`). There are tests for foreign content written between inspection and write, and for a stop admitted during a slow probe. |
| **M-5** | A confirmation under `rolling-health-gated` takes no health reading | ✅ `ApplyExecutor.java:486-495` (a run can go indeterminate inside the gate); `gateProved` present only on a passed gate | **Routed to the owner: Q-R2** (it extends OR-59's "confirmed" row). T6.8 is written for (a): one read-only gate observation, then `gateProved` recorded or `reconcile.gate.unproven`. |
| **M-6** | `gateProved` from the declaration, not the signal used; `HEALTHCHECK` contradicts point 26; `broker-heartbeat` | ✅ `SshDockerRuntimeAdapter.java:182-193` (a `HEALTHCHECK` gives `HEALTHY`); schema `kind` enum includes `broker-heartbeat` (`solution-package.schema.json:645-649`) | **Fixed, with a policy question.** There is no fallback: when an executable (HTTP) probe is declared, it is the only signal, so `gateProved` is exactly its declared strength, and it is absent for every other kind. A test pins the no-fallback property. The `HEALTHCHECK`-only case is **Q-G1** (recommendation (a): fail closed, matching point 26). ⚠️ **Disputed in part:** the suggested schema `if/then` (strength required only for HTTP kinds) is not needed. A package still declares what its probe proves, and v1 simply does not execute `broker-heartbeat`. T0.1 point 26 and T6.9 say so. |
| **M-7** | T6.9 needs an engine change, which breaks W8's split | ✅ `ApplyExecutor.java:504-507` (gated path lands `APPLIED` with no comparison); `VERIFY` exists only on restore (`:718-729`) | **Fixed.** T6.9 adds a post-restart `VERIFY` to `ApplyExecutor` (listed in Files), and Step 0 measures its legality on `apply`. The mismatch landing is **Q-G2** (recommendation `CONFIG_DIVERGED`). The waves are resequenced: W8 {T6.4 ∥ T6.7}, W8a {T6.9 → T6.5, one agent, because T6.5 consumes T6.9's read-back}, W8b {T6.8}. New single-owner rows cover `ApplyExecutor` and `OutcomeService`. |
| **M-8** | T0.4 will stop at an owner question, and it closes the sequential W0 | ✅ `~/.m2` POMs declare off-list licences for logback, JUnit, jakarta.annotation, BouncyCastle and HdrHistogram (names read locally; see Q-L1). `REQ-014` routes each to the owner | **Fixed.** T0.4 is split. **T0.4a** (the audit, report only) runs in W0 before the owner sitting, so the owner decides **Q-L1/Q-L2** from a measured list. **T0.4b** (the gate) opens W1's T3.0 agent, the only thing it blocks. The header no longer says "no owner question remains open". ⚠️ **Disputed in part:** whether test scope and plugins are covered is **not** undecided. `REQ-014` says "ships **or builds with** — direct and transitive", so both are in. Whether the plugin can see them is M17 (v). |
| **M-9** | Unknown licence, multiple licences, and exclusion matching unspecified and unmutated | ✅ against T0.4's text | **Fixed (T0.4b, M17).** Unknown and unmapped names fail. The multi-licence rule is **Q-L2** (recommended fail-closed). Matching is exact, with any regex anchored. M17 gains (ii)–(v). New mutations cover a no-licence artifact, a one-character mapping near-miss and, under Q-L2 (a), HdrHistogram's approval removed. |
| m1 | Stale allow-list text; `:164` names only T3.0 | ✅ the allow-list line had already been corrected in the working copy before this review; `:164` had not | **Fixed.** `:164` now names T0.4 and T3.0 and says the reactor builds offline again after the one resolution. |
| m2 | T0.4 under the S1 heading | ✅ | **Fixed.** T0.4 now sits at the end of S0. |
| m3 | Point 26 re-declares a closed vocabulary | ✅ | **Fixed.** `validate-openapi.py` (iv) holds the two enums in parity with the schema's `strength`, and T0.1 Step 6 (g) mutates it. |
| m4 | `engine-edge` validated by no Python gate | ✅ `run-checks.sh:130-132`; measured: it passes with `--dev-mode` and fails gate 2 without | **Fixed.** T0.1a adds it to step 1 with `--dev-mode`. |
| m5 | `manual` confirm without a start-time check extends OR-59 | ✅ | **Routed: Q-R4** (recommended (a); `RuntimeAdapter.startOrUpdate` javadoc: `manual` never restarts). |
| m6 | The alert relies on T10.5 (W11) | ✅ | **Fixed.** T6.8's alert is the stamped event plus `reconcileReason`. Home attention is T10.5's, stated in "Must not claim". |
| m7 | The "then left alone" test does not exist | ✅ no `LeftAlone` under `backend/*/src`; plan 007 Results `:1880` | **Fixed.** T6.8 writes it **new** (`…ThenReconciled`), not "changed". |
| m8 | T6.8 omits T6.9; T6.6 hedges | ✅ | **Fixed.** T6.8 and T6.6 both depend on T6.9. The hedge is removed, and the gated walkthrough line is required. |
| m9 | Startup handoff, defaults, lateness | ✅ | **Fixed.** Startup upserts a due row and never blocks boot, with a test. Defaults are stated. The age bound is part of **Q-R3**, and the runbook states the latest re-drive time. |
| m10 | Operator resolution racing a re-drive | ✅ `ApplyClaimRepository.java:18` (the claim is the only `REQUESTED → RUNNING`) | **Fixed.** T6.8 rule 4 adds three tests: operator resolution of a `RUNNING` re-drive is refused, a lost race drops the reconciler's bookkeeping, and only the reconciler takes `APPLY_INDETERMINATE → RUNNING`. |
| m11 | Docker `StartedAt` and `curl --fail` facts unmeasured | ✅ | **Fixed.** New rows **M18** and **M19**. `HostProbe` drops `--fail` for `--write-out '%{http_code}'` (T6.1), so a `DOWN` answer is distinct from a transport failure. T6.9's probe-down test pins `gate.unhealthy`. |
| m12 | Six T0.1 rows need attention at Step 7 | ✅ `console.yaml:369-373` (`organizationId` in the body); `:1379-1386` (`Target.kind`); `console-integration.md` §6.1 ("collections — per item, never one version for the collection") | **Split.** Rows 9, 11, 20 and 21 are **fixed** as corrections to binding text: §6.1, OR-55, and `I5` for `loadPackage`, with a new check-11 mutation. They are shown at Step 7, not re-decided there. Rows 15, 16 and 22 are **Q-C1/Q-C2/Q-C3**. The "four vs five" divergence count is fixed everywhere to four divergences (12, 16, 17, 19) plus point 18's addition. |
| m13 | Critical path omits T0.3, T0.4 and T3.3 | ✅ | **Fixed.** The path now runs through T0.4a and T0.3, with two named feeder chains (T0.4b → T3.0 → … → T4.4; T3.3 → T6.4). |
| m14 | W11 migrations with no merge order | ✅ `:339-341` version-order rule | **Fixed.** The W11 row and the allocation note both say T9.3 (V013) merges before T11.2 (V014). |
| m15 | Placeholders now known | ✅ `9ec6797`, `0718603` in `git log` | **Fixed.** Both SHAs are written in. T0.1 Step 6(c) cites `9ec6797`'s recorded mutation. |
| m16 | R1 names no fault-injection mechanism | ✅ | **Fixed.** A named test-scope `BeanPostProcessor` decorator fails `status()` for one window after the write, so the real adapter still does every real write. If the engine's bounded retry routes the request to `BE-D22` instead of `APPLY_INDETERMINATE` (`ApplyExecutor.java:594-618`), R1 is **skipped with that reason**, not forced. |
| m17 | T1.2's fallback breaks the 33-fixture lock | ✅ `thereAreExactlyThirtyThreeSharedFixtures`; no owner for `contracts/view-models/fixtures/**` | **Fixed.** A non-distinguishing fixture set is a finding, recorded and routed as its own reviewed contract change. A Java-only rule test may pin the comparator. `contracts/view-models/fixtures/**` is named ownerless in the single-owner table. |

**Owner questions this review raised** (all put in the T0.1 Step 7 sitting — [the table](#owner-questions-raised-by-the-second-review--answered-at-t01-step-7)):
- **Q-L1, Q-L2** (licences; they gate T0.4b, and through it T3.0 on the critical path).
- **Q-R1–Q-R4** (the reconciler; they gate T6.8).
- **Q-G1, Q-G2** (the gated strategy; they gate T6.9).
- **Q-C1–Q-C3** (contract text; they gate T0.1's merge).

**Can W0 start? Yes.** Nothing in W0 before the sitting (T0.0, T0.1 Steps 1–6, T0.1a, T0.4a) waits on a new
question. T0.4a needs one online resolution, which the user must permit. The sitting is still the gate ahead of W1,
and it now carries these questions as well as the contract diff.
