# Backend Plan 007 — the production API and the event-driven single-placement SSH-Docker walking skeleton

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One Cache instance, on one SSH-reachable Docker host, driven end to end from the browser by an
**authenticated** REST API over plan 005's durable engine — with the console kept live by **contract-defined
events published after commit**, healed by **REST backfill from `stateVersion`**, and degrading to **visible**
polling when the mesh is unavailable. No second apply path, no fixture fallback, no polling as the normal
architecture.

**Architecture:** Three layers, each with one job. (1) A reviewed **OpenAPI** document under `contracts/` is the
single source for both sides' models: the **TypeScript client types are generated** from it (`FE-D7`: *"types are
generated from the Console OpenAPI contract when it exists"*), while the **Java server models are hand-written and
mirror-locked** against it by a test. ⚠️ **That asymmetry is deliberate and it is `BE-D10`'s:** `BE-D10` is
literally *"Contracts consumption: mirror-and-lock, **no codegen**, no producer-side runtime validation"*, and it
rejected codegen for v1 with the scenarios recorded in `backend-consumption.md`. An earlier draft of this plan
cited `BE-D10` **in support of** Java codegen, which is backwards. If an implementer wants Java codegen, that is a
design change: **record a new decision (next free id from the merged register) that supersedes `BE-D10` for the OpenAPI artifact, with the conflict flagged
(`I3`/`I4`) — do not just add a plugin.** (2) `console-gateway` terminates HTTP, resolves the session, calls
`authorizationService.require` once per operation, and delegates to `cp-core`/`dp-core` **service interfaces**
(`BE-D3`, `I7`) — it holds no datasource. (3) `dp-core`'s apply engine gains **event emission at the existing
`EventPublisher` seam**, after commit, at the points `contracts/events/` already defines — and `cp-core` gains its
**own** emitter for `catalog.package`, which `backend/design.md` §3 assigns to `cp-core` and the envelope's
`producer` enum will not let the DP claim. (4) A small **production composition module** owns the Spring Boot
`main`, wires implementations to the `cp-api`/`dp-api` interfaces, and owns startup sequencing and the **durable
apply worker** — because two existing ArchUnit rules (`gatewayUsesApisOnly`, `gatewayNeverImportsEvents`) forbid
`console-gateway` from doing any of it. The browser gains a
`shared/mesh` module implementing `BE-D14` §4.3's `LiveStateClient`, whose notify handlers invalidate TanStack
Query keys and **never** write payloads into the cache.

**Requirements:** [`REQ-012`](../../requirements/REQ-012-event-driven-live-console.md) — the event-driven backbone
and live console state — is what this plan actually delivers; [`REQ-006`](../../requirements/REQ-006-rbac.md)'s
*"no endpoint is reachable without it"* clause is enforced here per endpoint (plan 006 built the machine);
[`REQ-001`](../../requirements/REQ-001-unified-config-layer.md) and
[`REQ-005`](../../requirements/REQ-005-three-runtimes.md) are exercised by the Cache/SSH-Docker slice.

**Tech Stack:** Java 17 · **Spring Boot 3.5.16** (web, for the first time in this repo) · OpenAPI 3.1 ·
**Testcontainers 1.21.4** · **ArchUnit 1.5.0** · **PostgreSQL 14** · JCSMP (already present in the `dp-core` mesh
bridge, at the reactor's managed version) · React + Vite + strict TS + MUI (the frontend's existing pins) · the
Solace **WebApp SDK** (`~/Work/Development/RapidPilot/webAppSDK`) · MSW (tests only)

⚠️ **Three versions are deliberately unpinned here because they are not yet chosen** — see
[Measured facts](#measured-facts).

**Spec:** [`docs/design/common/contracts/console-integration.md`](../../design/common/contracts/console-integration.md)
(the whole document — it is this plan's contract) ·
[`docs/design/backend/eda-walking-skeleton.md`](../../design/backend/eda-walking-skeleton.md) §4 (`BE-D14`) ·
[`contracts/events/README.md`](../../../contracts/events/README.md) ·
[`docs/design/D10-G2-APPROVAL-2026-09-15.md`](../../design/D10-G2-APPROVAL-2026-09-15.md) §1.5, §3 ·
[`docs/design/D11-AMENDMENT-PROPOSAL.md`](../../design/D11-AMENDMENT-PROPOSAL.md) §3.4 (the 404-vs-403 sets) and
§6.5 obligations (i₁)/(i₂), which this plan owns

---

## Global Constraints

- **EDA is the architecture, not a phase.** Rules 1–12 of `console-integration.md` §2 are binding on every task.
  A task that satisfies its own acceptance criteria by adding a poll has failed.
- **Exactly one apply path:** `console-gateway` → DP service interface → **durable worker** → `ApplyExecutor` →
  `RuntimeAdapter`. **A controller that calls a `RuntimeAdapter`, or that performs a *mutating* remote operation,
  is a defect** — and Task 12 makes it a red build.
- ✅ **The CLI posture is a ruling, and it does not soften the browser's** (OR-3): a **CLI/terminal client may use
  adaptive REST polling as its primary mechanism** — no session to keep warm, no query cache to invalidate, often
  no mesh reachability. ⛔ **The browser still uses events as *refetch now* notices** with polling as fallback, and
  **events never become authoritative state** in either client.
- **Exactly one apply path**, and ⚠️ **"remote work" above means *mutating* remote work.** A synchronous **read** of a host — status, health, a bounded log tail — is a legitimate request-thread operation and Task 6a builds several. A rule that forbade `GET /logs` from existing would be a broken rule, not a strict one.
- ⛔ **`202` must be genuinely asynchronous.** Measured: plan 005's `ApplyExecutor.execute(ApplyCommand)` performs
  admission **and** the remote SSH/Docker work in one synchronous call. Returning `202` around that method would
  hold the HTTP request open for the whole remote operation — a `202` that lies. Task 4 splits submission from
  execution; **PostgreSQL and the apply journal remain the only durable queue and the only truth.** No Kafka, no
  workflow engine, no in-memory-only queue.
- ⛔ **The production application class may not live in `console-gateway`.** `gatewayUsesApisOnly` restricts it to
  `cp.api`/`dp.api`, and `gatewayNeverImportsEvents` bars `com.solace.sm.events..` outright. Task 3a creates a
  composition module instead.
- ⛔ **`catalog.package` is `cp-core`'s event.** `backend/design.md` §3's catalog row says so and
  `envelope.schema.json` closes `producer` to `['cp-core','dp-core']`. **Do not add `onPackageLoaded` to
  `dp-core`'s `ApplyEventEmitter`** — an earlier draft of this plan did, and it would have made the DP either
  mislabel its producer or claim the CP's id.
- **`demo-launcher` is not a production authority.** Task 13 removes overlapping demo routes after backend
  production parity; Plan 009 removes the remaining monitoring module after its backend parity. Those cleanup
  changes may land on integration without frontend cutover. The resulting branch is **not deployable with the
  current UI** until the frontend consumes the handed-off endpoints; that is a release gate, not a cleanup gate.
  No **new** production code may read `demo_control` or import a `com.solace.sm.demo` class.
- **The catalog is closed at 38** — the amendment's §1.1 (rows 1–35) plus §1.7 (rows 36–38: `instance.stop`, `audit.view`, `user.edit`). ⚠️ `POST …/stop` **is still not built here**, but not for want of a verb: `instance.stop` exists and is Administrator-only; the endpoint belongs to a later plan. `restart-all` and
  `rolling-no-validation` are **refused at admission** with `501`, not `403` (`console-integration.md` §5.6 rule 5),
  because no strategy-assertion verb exists. `scheduled` stays out of release.
- **Events publish only after commit**, through `EventPublisher`. No module imports JCSMP; `onlyMeshbridgeTouchesJcsmp`
  already enforces that and must stay green.
- **Notify payloads carry identifiers only** (`I6`). No config value, no secret, no metric value, no label value.
  `contracts/events/negative/value-in-notify.json` is that rule as a check.
- **No new event type is invented, and ⛔ no new event is implemented before its contract lands.** The six in
  `contracts/events/` are the catalog. ✅ **`instance.apply-outcome` is APPROVED** (ruling **OR-4**, 2026-09-16) as
  a **placement-scoped** notice: **`entity.kind = "placement"`** (a `const`), `entity` = `kind` + `instanceId` +
  **required** `placementId`; **`payload`** = `applyId` + the terminal `state` key; **envelope** carries
  `stateVersion`. ⛔ **No error details.** ⚠️ The envelope/payload split matters — an earlier draft listed all five
  fields as peers, and `entity.kind` not at all, which is the shape the gate rejects — ⚠️ **but its normative contract lands later as one reviewed
  single-writer change**, and the rest of the `BE-D4` amendment
  ([`BE-D4-AMENDMENT-PROPOSAL.md`](../../design/backend/BE-D4-AMENDMENT-PROPOSAL.md)) is **not** approved.
  Until it is approved *and* implemented, the apply surface runs in **declared degraded mode** (polling, indicator
  says degraded), and **this plan may not claim the event-driven apply walking skeleton complete.** See
  [Acceptance](#acceptance--what-this-plan-may-and-may-not-claim). **A missing event contract is never permission
  to make polling the normal live architecture.**
- **Contracts are single-writer.** The OpenAPI addition and any `BE-D4` amendment are **reviewed** changes with
  `./contracts/run-checks.sh --strict` green before and after.
- **`I5`** — organization/tenant is server-derived from the session, never from the request.
- **`I7`** — `console-gateway` holds no datasource and no DB role; it imports `cp-api`/`dp-api` only.
- **`I3`** — ⛔ **no id is reserved in advance**: allocate the next free id from the merged `DECISIONS.md` (ruling **OR-11**), after plan 005 merges.
- **`I2`** — every external fact (generator behaviour, SDK API, broker option) is verified with WebFetch against a
  specific URL, or measured, before it is written down. Web search is unavailable.
- **Docker/Colima is shared state.** `colima status` · `docker context show` · `docker ps` before any container
  work; context stays `colima`; never prune, switch, stop or inspect foreign containers; serialize Testcontainers,
  DDL replay and Compose work; use a Compose project name owned by this worktree and clean up only its containers.
- **Every guard carries a planted-violation proof** (`L7`); a mutation that does not fire is a finding about the
  tests (`L22`).
- **Commit attribution.** Every commit ends with the session's attribution trailer.

---

## Prerequisites — two of them are hard

1. ✅ **Plan 005's engine completion is an ancestor of the current `integration/dp-remote-demo` baseline.** The 2026-09-15 starting measurement recorded
   `plan005-runtime-completion` (`ba7db2e`) as outside the then-baseline `integration/dp-remote-demo` (`2a0047e`). The demo work was
   committed first, `git merge-tree --write-tree` was re-run, and plan 005 then merged. The health gate, rolling
   strategies, resume planner, scope-granular stop and generation-free `replicas(PlacementRef)` observation are
   current integration inputs. The semantic overlap in
   `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/ssh/SshDockerRuntimeAdapter.java` retains both
   plan 005's `replicas()` behavior and the demo transport/status changes. The sequence remains at
   [`docs/tasks/integration-sequence-2026-09-15.md`](../../tasks/integration-sequence-2026-09-15.md) as historical
   integration evidence.
2. ⛔ **Plan 006 must be complete before any endpoint task.** Every endpoint here calls
   `authorizationService.require`, and there is no development bypass to build against. ⚠️ **Some tasks are the
   exception and may precede it** — Tasks 1, 2, 3a, 4b, 5, 5a and Task 9's backend scenarios 1–3 touch no
   authorization semantics; the
   [Acceptance section's subset table](#acceptance--what-this-plan-may-and-may-not-claim) says which, and it governs. **This is a sequencing
   prerequisite, not a gate:** plan 006 is itself unblocked.
3. **Check the integration worktree is clean before starting, and do not mix its work with this plan's.** It was
   dirty when this plan was written (2026-09-15: 32 modified, 12 untracked, no stash) — the *count* will be stale
   by execution time, so run `git status --porcelain` and act on what it says, rather than on this number.
4. **Read `console-integration.md` §14 first.** It is the measured starting point: the event seam exists and has
   **no production publish site**.
5. **Pick up plan 005's routed watch items — this is "the plan that adds an application class", which is what three
   of them wait for.** Plan 005's own Results route them here, and its Spec says a predecessor's Results are
   *inputs, not background*:
   - ⛔ **`resume` has no production caller.** The resume path is implemented and proven and **nothing invokes it**,
     because no application class existed and G2 forbade writing one. This plan creates the application. **Task 4a
     below wires it**, or a crashed apply stays `RUNNING` forever in production while every test passes.
   - ⚠️ **`resume` re-runs pre-flight, spending one attempt of the `PREFLIGHT` intent.** Budget for it or the retry
     cap is reached one crash earlier than the configuration says.
   - ⚠️ **`resume` is proven only against an in-memory adapter.** Task 9 must exercise it against the SSH-Docker
     adapter at least once, or "crash-safe resume" is a claim about a fake.

## Measured facts

**Fill these before the task that needs them.**

`I2` forbids stating an external fact from memory, and web search is unavailable — use WebFetch against a specific
URL, or measure.

| Fact | Status | Needed by |
|---|---|---|
| **The TypeScript OpenAPI generator: which one, and its exact version** | ❌ not chosen. Verify by WebFetch that it supports OpenAPI **3.1** (several popular generators still target 3.0 only) | Task 2 |
| **The Solace WebApp SDK's connect/subscribe API and its version** | ❌ not read. `BE-D14` §4.2 inherits G1's measured behaviours (`connectRetries: 0`, the undifferentiated `AUTH_FAILED`), but the API surface must be read from the SDK itself | Task 7 |
| **The metrics stack for the drop-counter export** | ❌ not chosen. `JcsmpEventPublisher`'s javadoc says *"the metrics plan must expose it"*; Task 5 step 4a is a **deliberate re-route** of that obligation to this plan, which does not otherwise pick a metrics stack. Pick the smallest thing that works, or route it back | Task 5 |
| **Spring Boot web's CSRF and cookie defaults** | to be verified, not recalled — this repo has been burned three times by confident recall of Spring defaults (`I2`) | Task 3 |


### Ratification posture — stated, because `docs/plans/README.md` says not to start on a PROPOSED decision

[`docs/plans/README.md`](../README.md) says: *do not start a plan when it depends on a `PROPOSED` decision that
ratification could still change.* This plan does depend on PROPOSED decisions, and the posture is the same one
`BE-D16`–`BE-D18` and plans 004–005 already carry, stated rather than assumed:

- **What is settled** is the part this plan is built on: `BE-D1`/`BE-D4` are executable today (`contracts/events/` plus check 10), `BE-D12`/`BE-D13` are implemented (plans 004–005), and G2 is closed. `BE-D14`'s session contract is PROPOSED and is the part most exposed to ratification — which is why the browser's **generated** notify types wait on the `BE-D4` amendment while the seam itself does not.
- **What ratification would decide** is whether those decisions *stay*, not whether they may be built.
- **What is genuinely blocked** is listed in the non-goals table, and nothing in the task list depends on it.
- **A contradiction found during execution is design-first**: record it and stop the decision-dependent task.
  Do not invent semantics, relax a constraint, or write a migration around it.

---

## File Structure

| Path | Responsibility |
|---|---|
| `contracts/openapi/console.yaml` | **The** REST contract. CP, DP and console prefixes. Reviewed, single-writer. |
| `contracts/openapi/validate-openapi.py` | Check 11 in `run-checks.sh`: parses, asserts every operation has an `operationId`, a documented error body, and an `x-sm-permission` naming a catalog verb. |
| `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/*` | Controllers — thin. Session resolve, `require`, delegate, map exceptions. |
| `…/gateway/http/ErrorBodyAdvice.java` | The one error shape (`code`, `message`, carried details). |
| `…/gateway/http/CsrfFilter.java` | The other half of `FE-D14`'s CSRF seam. |
| `…/gateway/mesh/TicketController.java` | Mint / grant / revoke, gated by the three mesh verbs. |
| `backend/dp-api/src/main/java/com/solace/sm/dp/api/*` | The DP service interfaces the gateway calls. |
| `backend/launcher/**` | ⛔ **The production composition module** — the Spring Boot `main`, bean wiring to `cp-api`/`dp-api`, startup sequencing, and the durable apply worker. **Not `console-gateway`:** `gatewayUsesApisOnly` and `gatewayNeverImportsEvents` forbid it (Task 3a). |
| `…/launcher/ApplyWorker.java`, `ApplyWorkerProperties.java` | The bounded worker that claims durable journal work. The HTTP layer never executes an apply. |
| `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/ApplyClaimRepository.java` | The claim — a guarded `UPDATE` on the request's own row. PostgreSQL is the queue. |
| `backend/dp-core/src/main/java/com/solace/sm/dp/apply/events/ApplyEventEmitter.java` | The DP's publish site: rollout, config-state, health. **Producer `dp-core` only.** |
| `backend/cp-core/src/main/java/com/solace/sm/cp/events/CatalogEventEmitter.java` | The CP's publish site: `catalog.package`, producer `cp-core`. `backend/design.md` §3 assigns that event to `cp-core`; the DP may not claim it. |
| `frontend/src/shared/mesh/LiveStateClient.ts` + `liveStateClient.ts` + `notifyRouter.ts` | `BE-D14` §4.3's seam, the invalidation table, the resync/backfill discipline. |
| `frontend/src/shared/mesh/guard.test.ts` | The no-fixture-in-production guard and its planted violation. |
| `backend/arch-tests/.../ApplyPathBoundaryTest.java` | One apply path; no controller touches a `RuntimeAdapter`. |

---

## Task 1 — The OpenAPI document, and the check that keeps it honest

**Files:** Create `contracts/openapi/console.yaml`, `contracts/openapi/validate-openapi.py`; Modify `contracts/run-checks.sh`.

**Interfaces:** Produces the operation ids and schema names every later task consumes.

- [ ] **Step 1: Write the failing check first.**

```python
# validate-openapi.py — check 11
SENTINELS = ("none:public", "none:authenticated", "local:ROTATE_OWN_CREDENTIAL")

def test_every_operation_declares_a_shippable_permission(spec, catalog, undecided):
    # `catalog` and `undecided` are BOTH parsed from D10-G2-APPROVAL-2026-09-15.md
    # **§1.1 AND §1.7** -- 38 rows across two tables. Parsing §1.1 alone rejects
    # instance.stop / audit.view / user.edit, which are rows 36-38. The two tables also
    # spell the consequence column differently (yes/no vs HIGH/STANDARD): treat both.
    # the verb column, and the rows whose consequence column is unmarked. The second is
    # what makes an UNDECIDED verb unshippable HERE as well as in the Java registry --
    # an earlier draft parsed only the verb column, so an operation naming user.deactivate
    # would have PASSED.
    for path, ops in spec["paths"].items():
        for method, op in ops.items():
            assert "operationId" in op, f"{method} {path} has no operationId"
            perm = op.get("x-sm-permission")
            assert perm is not None, f"{method} {path} declares no x-sm-permission"
            assert perm in catalog or perm in SENTINELS, \
                f"{method} {path} names {perm!r}, which is not one of the ratified 38"
            assert perm not in undecided, \
                f"{method} {path} names {perm!r}, whose consequence class is UNDECIDED"
```

`catalog` is parsed from `docs/design/D10-G2-APPROVAL-2026-09-15.md` §1.1 — the same mirror discipline as plan
006 Task 1, so the OpenAPI cannot name a verb the approval withheld.

- [ ] **Step 2: Run it against an empty spec.** Expected: fails, no spec file.
- [ ] **Step 3: Write `console.yaml`** for the operations this plan needs, taking shapes from
  `console-integration.md` §5 and the as-built description (imported as **reference**, per
  `dev-frontend-intake.md` step 4 — never promoted to normative):
  `POST /cp/auth/login` · `GET /cp/auth/identity` · `POST /cp/auth/logout` · **`POST /cp/auth/credential`** (rotate own credential — `console-integration.md` §4.2; ⛔ **without it the bootstrap administrator is deadlocked**, because `must_rotate` blocks every other endpoint and nothing clears it) · `GET /cp/hierarchy/*` ·
  `GET /cp/solutions` · `POST /cp/solutions/load` · `GET /dp/solutions/{solutionId}/instances` ·
  `POST /dp/solutions/{solutionId}/instances` · `GET /dp/instances/{instanceId}` ·
  `GET /dp/instances/{instanceId}/designer` · `POST /dp/instances/{instanceId}/config` ·
  `POST /dp/instances/{instanceId}/apply` · `GET /dp/instances/{instanceId}/apply/observe` ·
  `POST /dp/instances/{instanceId}/apply/rollback` · `GET /dp/instances/{instanceId}/health` ·
  `GET /dp/instances/{instanceId}/config-ladder` · `GET|POST /dp/instances/{instanceId}/log-levels/{component}` ·
  `GET /dp/instances/{instanceId}/logs/{component}` · `POST /cp/mesh/ticket` · `POST /cp/mesh/grant` ·
  `DELETE /cp/mesh/grant/{grantId}` — **plus the seven §5 rows an earlier draft of this list omitted**:
  `GET /cp/home/summary` · `GET /cp/home/attention` · `GET /cp/targets` · `GET /dp/instances/{id}/config/history` ·
  `POST /dp/instances/{id}/config/secrets/{fieldKey}` (the `I6` secret-reference endpoint §15 lists as a demo
  learning to inherit) · `POST /dp/instances/{id}/config/accept-runtime` ·
  `POST /dp/instances/{id}/config/restore-runtime`.

  ⚠️ **The Admin surface is deliberately absent.** §5.2 rows 12–22 (`/cp/admin/permission-catalog`, `/admin/roles`,
  `/admin/principals`, `/admin/scope-tree`, `/admin/assignments` ×3, `/admin/effective-permissions`,
  `/admin/users` ×2) are **out of this plan's scope** and are listed in its non-goals: the delivery sequence's W3
  ships no principal or role-binding endpoint, and provisioning the demo operator is an authenticated setup
  command, not an HTTP surface. Plan 006 supplies their decision logic; an Admin plan ships them.
- [ ] **Step 3a: Encode the three authorization shapes check 11 must accept**, because `x-sm-permission` alone cannot express them:
  - `none:public` — `GET /config.json`, `POST /cp/auth/login`.
  - `none:authenticated` — `GET /cp/auth/identity`, `POST /cp/auth/logout`.
  - **`local:ROTATE_OWN_CREDENTIAL`** — `POST /cp/auth/credential`. ⛔ Not a catalog verb: the approved **38** contain
    no rotate verb, and inventing one is exactly what the authorization forbids.

  And one **refusal**: an operation whose `x-sm-permission` names a verb whose `ConsequenceClass` is `UNDECIDED`
  fails check 11. ⚠️ **No catalog member is `UNDECIDED` today** — `user.deactivate` was ruled `HIGH` (**OR-13**),
  which is why `PATCH /cp/admin/users/{id}` is now fully authorizable (`user.edit` + `user.deactivate`). The
  refusal stays because it is what stops the **next** unruled verb reaching an endpoint, and it is exercised
  against a fixture verb rather than a real one.
- [ ] **Step 4: Wire it as check 11** and run `./contracts/run-checks.sh --strict` green.
- [ ] **Step 5: Prove the check fires — with verbs that are genuinely absent.** ⚠️ The two mutations an earlier draft used are now **dead**: `instance.stop` is catalog row 36 and `user.deactivate` is `HIGH`, so both would pass. Use instead: add an operation with `x-sm-permission: instance.undeclare` → check 11 red with that operation named; point one at `group.view` → red; point one at a **fixture verb whose consequence column is blank** → red *because the class is `UNDECIDED`*; remove an `operationId` → red; give login a permission that is not `none:public` → red. Restore.
- [ ] **Step 6: Commit.** `feat(contracts): the console OpenAPI, with check 11 binding every operation to an approved verb`

⚠️ **`x-sm-permission` is the artifact that makes `REQ-006`'s *"no endpoint is reachable without it"* checkable at
build time** rather than by reading controllers. Without it, §5's map is a document nobody has to obey.

---

## Task 2 — Generated models, both sides, validated

**Files:** Modify `backend/pom.xml`, `backend/console-gateway/pom.xml`; Create `backend/console-gateway/src/test/java/.../GeneratedModelParityTest.java`. The OpenAPI and generator command are handed to the frontend; no frontend file is changed or accepted by this backend plan.

- [ ] **Step 1: Write the failing backend mirror test.** Every `operationId` in the spec has a **hand-written Java** model/handler signature, and every Java type's field names, required-ness, enums and numeric bounds are **locked equal to the spec** — the `SchemaMirrorLockTest` idiom `BE-D10` already established for `contracts/events/`.
- [ ] **Step 2: Run; fails — the hand-written Java models and their mirror lock do not exist yet.**
- [ ] **Step 3: Write the Java models by hand with the mirror-lock test**, per `BE-D10`; publish the pinned OpenAPI generator command and `console.yaml` as the **frontend handoff**, not a backend dependency. Generated code is not committed (`common/contracts/README.md`).
- [ ] **Step 4: Run green.** The backend toolchain builds and the mirror lock passes.
- [ ] **Step 5: Prove it fires.** Rename a field in a hand-written Java model → the lock test goes red. Loosen a required field to optional in Java → red. The frontend separately proves generated TypeScript against the handed-off contract.
- [ ] **Step 6: Commit.** `build: one OpenAPI and mirror-locked Java models (BE-D10)`

⚠️ **`FE-D7` forbids hand-typed wire types.** ⚠️ **The notify event types are a separate matter and stay
hand-written against the JSON Schemas until the `BE-D4` amendment lands** — that is the G3 residue, recorded in
`console-integration.md` §16.1, and it does not block this task.

---

## Task 3 — The gateway: session, CSRF, one `require`, one error shape

**Files:** Create `…/gateway/http/SessionResolver.java`, `CsrfFilter.java`, `ErrorBodyAdvice.java`, `AuthController.java`; Create `…/gateway/http/GatewayContractIT.java`.

**Interfaces:** Consumes plan 006's `SessionService`, `AuthorizationService`, `MustRotateGate`.

- [ ] **Step 1: Write the failing tests — §4's contract, one test per row.**

```java
@Test void theSessionCookieIsHttpOnlySameSiteStrictAndNeverReadableByScript() { }
@Test void everyMutationWithoutACsrfTokenIsRefused() { }
@Test void aGetRequiresNoCsrfToken() { }
@Test void loginDoesNotRequireACsrfTokenBecauseNoSessionExistsYet() {
    // ⛔ Requiring the post-login token on login makes FIRST LOGIN IMPOSSIBLE. Login is
    // protected by SameSite=Strict, Origin/Referer validation, rate limiting and lockout.
    assertThat(postLoginWithoutCsrf()).hasStatus(200);
}
@Test void loginFromAForeignOriginIsRefused() { }
@Test void loginIssuesBothCookiesAndOnlyOneIsHttpOnly() {
    assertThat(sessionCookie()).isHttpOnly().hasSameSite("Strict");
    assertThat(csrfCookie()).isNotHttpOnly();   // the SPA must read it to set the header
}
@Test void loginIsRateLimitedAndLocksOutAfterTheConfiguredFailures() { }
@Test void anUnauthenticatedRequestGets401AndNotARedirect() { }
@Test void aDeniedRequestGets403AndLeaksNoExistenceInformation() { }
@Test void aResourceInAnotherOrganizationGets404NotA403() { }          // D11-AMENDMENT-PROPOSAL.md §3.4
@Test void mustRotateBlocksEveryEndpointExceptRotationLogoutAndIdentity() { }
@Test void theRotationRefusalIsNotA403BecauseThePrincipalIsAuthorized() {
    // It is the credential that is provisional, not the grant. A 403 would send an operator
    // hunting for a missing permission.
}
@Test void theErrorBodyIsOneShapeAndTheUiSwitchesOnCodeNotMessage() { }
@Test void anEmptyOrNonJsonErrorBodyDoesNotBreakTheClient() { }
@Test void theResponseCarriesXCorrelationIdOnEveryToastBearingMutation() { }   // FE-D13
@Test void noEndpointReadsAnOrganizationFromTheRequestBodyAsAuthority() { }    // I5
```

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Implement, with these five choices made explicitly.** (1) **One filter order:** resolve session → `MustRotateGate` → CSRF → authorize; a CSRF failure on an unauthenticated request must not leak that the session is invalid. (2) **CSRF is double-submit** with a per-session token in a **script-readable** cookie and a header — and ⚠️ **reconcile with the demo's existing `X-SM-CSRF`/`SM_DEMO_CSRF` pair** (`providers.tsx` wires it on the `local-demo` channel), either by adopting those names or by deliberately replacing them in the same change as the frontend. (3) **`404` vs `403`** follows `D11-AMENDMENT-PROPOSAL.md` §3.4's sets exactly, and obligations (i₁)/(i₂) are **byte-equality** assertions on the response, which is why they live here and not in plan 006. (4) **One `@ControllerAdvice`** produces the error body; no controller formats its own. (5) `X-Correlation-Id` is echoed on **every** response, and the server's value wins (`FE-D13`).
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Prove they fire.** Remove the CSRF filter → its test red. Swap the 404/403 discrimination → its test red. Drop the correlation header → its test red. Reorder the filters so CSRF precedes session resolution → the leak test red.
- [ ] **Step 6: Commit.** `feat(console-gateway): the authenticated HTTP boundary — session, CSRF, one require, one error shape`

---

## Task 3a — The production composition module, and the `console-api` boundary

✅ **`console-api` is approved (ruling OR-10, 2026-09-16) and open item 21 is closed.** Two modules, two jobs, and
conflating them is the mistake this task exists to prevent:

| Module | Job | Rules |
|---|---|---|
| **`console-api`** | **composes CP and DP reads** for the console | depends on `cp-api`/`dp-api` **service interfaces only**; owns **no database**; ⛔ **no cross-schema SQL**; composes reads **concurrently**; returns `stateVersion` + **per-surface availability/degraded** info (§10.2) |
| **the launcher** | the Spring Boot `main` and bean wiring | depends on everything; owns no business logic; declares no `DataSource` |

⚠️ **`console-api` is not the composition root** and the launcher is not a read-composer. `console-api` serves
`/api/console/v1/*` — the prefix open item 21 previously blocked — and **CP owns package/hierarchy/config/auth
data while DP owns apply/runtime/health/metrics**. Broker consumers persist into the **owning plane**; a broker
notice only triggers a refetch and is never the write.

**Files:** Create `backend/launcher/pom.xml`, `backend/launcher/src/main/java/com/solace/sm/launcher/SolutionManagerApplication.java` and `…/launcher/SolutionManagerConfiguration.java` (the `@Import`s and the bean wiring Task 5a also modifies); Modify `backend/pom.xml` (add the module) **and `backend/arch-tests/pom.xml`** (add `launcher` and `demo-launcher` at test scope — Step 3b); Create `backend/arch-tests/src/test/java/.../CompositionBoundaryTest.java` + two planted-violation fixtures under the `archfixture` package.

**Why a new module rather than `console-gateway`** — measured, from `PlaneBoundaryTest`:

| Existing rule | What it forbids |
|---|---|
| `gatewayUsesApisOnly` | `com.solace.sm.gateway..` may depend on `cp.api`/`dp.api` **only** — so it cannot wire `cp-core`/`dp-core` implementations |
| `gatewayNeverImportsEvents` | `com.solace.sm.gateway..` may not depend on `com.solace.sm.events..` — so it cannot construct or inject the `EventPublisher` |
| `jdbcIsConfinedToApplyPersistence` | six package roots (`java.sql`, `javax.sql`, `org.springframework.jdbc`, `org.postgresql`, `com.zaxxer.hikari`, `org.flywaydb`) are confined to `com.solace.sm.dp.apply.persistence..` — so **the launcher declares no `DataSource` bean** |
| `onlyTheEngineTouchesTheMutatingRuntimeAdapter` (Task 12) | The **mutating `RuntimeAdapter` seam** is reachable only from the engine — so **the launcher may not declare an `ApplyExecutor` or `RuntimeAdapter` bean either**, because ArchUnit reads `@Bean` parameter and return types and that constructor takes `RuntimeAdapter` and `RenderedConfigSource`. This is deliberately not a package-wide ban on `dp.apply.runtime..`: retained demo monitoring reads `SshDockerMetrics`, handles `RuntimeAdapterException`, and constructs read-only placement data until Plan 009 deletes the module |

**Interfaces:**
- Consumes: `console-gateway`, `cp-core`, `dp-core`, `mesh-events`.
- Produces: the Spring Boot `main`; bean wiring from `cp-api`/`dp-api` interfaces to their implementations; the
  startup sequence; and the host for Task 4a's durable worker and Task 4b's resume sweep.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void theApplicationStartsAndExposesTheGatewaysRoutes() { }
@Test void theLauncherOwnsNoTableAndNoMigration() {
    assertThat(resourcesUnder("db/migration")).isEmpty();
}
@Test void theLauncherDeclaresNoDataSourceBean() {
    // jdbcIsConfinedToApplyPersistence would fail the build; this test says why.
}
@Test void everyCpApiAndDpApiInterfaceHasExactlyOneImplementationWired() { }
@Test void theStartupOrderIsSmDataSchemaReadinessVerificationThenResumeSweepThenTheHttpConnector() {
    // NOT "after migrations": THIS LAUNCHER DOES NOT RUN MIGRATIONS. Migration is
    // SmDataMigrationMain, an out-of-process install step the runbook runs first. The launcher
    // VERIFIES via smDataSchemaReadiness -- a check the runtime role can make -- and refuses to
    // serve a schema the migration step has not reached. A launcher that ran Flyway would need
    // an org.flywaydb reference, which jdbcIsConfinedToApplyPersistence forbids here, and would
    // undo the review finding that moved migration out of the application process.
}
@Test void theContextHoldsExactlyOneDataSourceAndItIsSmData() { }
```

```java
// CompositionBoundaryTest -- @AnalyzeClasses and the fixture-exclusion import option are BOTH
// required: without the first the @ArchTest fields never run; without the second this task's
// own planted fixtures keep the rules permanently red.
@AnalyzeClasses(packages = "com.solace.sm",
                importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class CompositionBoundaryTest {

@ArchTest static final ArchRule nothingDependsOnTheLauncher = noClasses()
        .that().resideOutsideOfPackage("com.solace.sm.launcher..")
        .should().dependOnClassesThat().resideInAPackage("com.solace.sm.launcher..")
        .because("a composition root is depended on by nothing; it is the top of the graph");

@ArchTest static final ArchRule theLauncherHoldsNoBusinessLogic = classes()
        .that().resideInAPackage("com.solace.sm.launcher..")
        .should().haveSimpleNameEndingWith("Application")
        .orShould().haveSimpleNameEndingWith("Configuration")
        .orShould().haveSimpleNameEndingWith("Worker")
        // "Properties" is in the list because Task 4a creates ApplyWorkerProperties; an earlier
        // draft omitted it, so Task 4a turned Task 3a's own rule red.
        .orShould().haveSimpleNameEndingWith("Properties")
        .because("wiring and sequencing only -- no domain type, no repository, no controller");
```

- [ ] **Step 2: Run; all fail** (no module).
- [ ] **Step 3: Create the module and wire it by `@Import`, not by declaring beans.** Three things an earlier draft of this task got backwards:

  1. ⛔ **No auto-configuration of the datasource.** `ApplyPersistenceConfiguration`'s javadoc: *"**Not
     auto-configured, and not `spring.datasource.*`** … Boot's `DataSourceAutoConfiguration` would give the
     application ONE datasource, and the whole privilege topology of §3.1 — obligations (k), (l), (o), (s), (t) —
     rests on the runtime connection being a role that cannot migrate and cannot `SET ROLE sm_dp_owner`."* So:
     `@Import(ApplyPersistenceConfiguration.class)` and **exclude `DataSourceAutoConfiguration`**. That import is
     ArchUnit-safe — the imported class is not itself in any of the six roots.
  2. ⛔ **`dp-core` must publish the `ApplyExecutor` and `RuntimeAdapter` beans from inside
     `com.solace.sm.dp.apply.engine`** (extend `ApplyEngineConfiguration`, which anticipates exactly this: *"The
     future application class `@Import`s both, deliberately, exactly as it imports `MeshBridgeConfiguration`"*).
     The launcher `@Import`s it and injects **engine-package** types only. Otherwise Task 12's two rules and this
     composition root are mutually unsatisfiable, and today the only class wiring the adapter at all is
     `demo-launcher`'s `DemoConfiguration`, whose overlapping route cleanup Task 13 performs after backend parity.
  3. ⛔ **The launcher does not sequence migrations.** Migration is `SmDataMigrationMain`, a separate one-shot
     process the install runbook runs **before** the application — that split is what stops the application
     holding the schema-owner credential for its life. The launcher *verifies* instead, via
     `smDataSchemaReadiness`.

  Then add the module to the reactor. ⚠️ Reactor order comes from the **dependency graph**, not the `<modules>`
  list, so also **add `launcher` (and, until Task 13, `demo-launcher`) to `backend/arch-tests/pom.xml` at test
  scope** — see Step 3b.
- [ ] **Step 3b: Put the new module on `arch-tests`' classpath, because otherwise these rules are silently vacuous.** Measured: `backend/arch-tests/pom.xml` declares exactly six test-scope module deps — `cp-api`, `dp-api`, `mesh-events`, `cp-core`, `dp-core`, `console-gateway` — and **`demo-launcher` is absent**, despite the POM's own description claiming it depends on every module. Consequences if this step is skipped:
  - `theLauncherHoldsNoBusinessLogic` is a **positive** `classes().that()` rule over an empty subject set, and
    ArchUnit's `failOnEmptyShould` defaults to **true** (no `archunit.properties` exists in this tree), so it fails
    with *"failed to check any classes"* — which Step 2 would misread as the intended "fails because there is no
    module yet".
  - `nothingProductionImportsTheDemoModule` and the mutating-`RuntimeAdapter` rule (Task 12) are **false greens**
    against `demo-launcher`'s real apply violation unless the module is scanned.
- [ ] **Step 4: Run green**, and confirm `gatewayUsesApisOnly`, `gatewayNeverImportsEvents` and `jdbcIsConfinedToApplyPersistence` are all **still green** — the launcher must not have been the thing that weakened them.
- [ ] **Step 5: Prove the guards fire.** Put the `main` class in `console-gateway` → `gatewayUsesApisOnly` red. Declare a `DataSource` bean in the launcher → `jdbcIsConfinedToApplyPersistence` red. Declare an `ApplyExecutor` bean in the launcher → `onlyTheEngineTouchesTheRuntimeSeam` red. ⚠️ For `nothingDependsOnTheLauncher`, **plant the violation the way every other rule here does** — a test-scope `…archfixture` class inside `arch-tests` that references a launcher type, excluded from the production run by `ExcludeArchFixtures`. Making `dp-core` depend on the launcher would be a **Maven reactor cycle**: the build refuses and the rule is never reached, so the proof would not exist.
- [ ] **Step 6: Commit.** `feat(launcher): a production composition module, because the gateway may not be one`

⚠️ **Task 12 distinguishes production fencing from full isolation.** Once `demo-launcher` is on the arch-tests classpath,
`onlyTheEngineTouchesTheMutatingRuntimeAdapter` is **red on arrival** — `DemoInstanceService` imports `RuntimeAdapter` and
calls `startOrUpdate`/`materializeConfig` from a controller path. Task 13 fences the intact module as staging-only;
Task 12 proves the production-scoped rule. Plan 009 removes the module after all-backend parity and proves the
unscoped rule. Frontend adoption remains a release gate, not a backend-cleanup gate.

⚠️ **`demo-launcher` is not this module and must not become it.** The reactor's own comment says *"Local,
time-boxed demo composition. It is not the console-gateway architecture boundary."* Task 13 cleans up its
overlapping routes after backend parity; Plan 009 removes the remaining module after monitoring parity.

---

## Task 4 — Authenticated apply submission: `202 Accepted` + `applyId`

**Files:** Create `…/gateway/http/ApplyController.java`, `backend/dp-api/.../ApplyService.java`; Modify `dp-core` to implement it; Create `…/gateway/http/ApplySubmissionIT.java`.

**Interfaces:**
- Produces: `ApplyService.submit(TenantId, InstanceId, Strategy, ActorId) → ApplyId` and `observe(TenantId, ApplyId) → ApplyObservation`.
- Produces, in `dp-core`, the **split of plan 005's synchronous entry point** — one path, two halves:

```java
/**
 * Admission only. Validation, the fences, the REQUESTED row. NO adapter call.
 *
 * A sum type, NOT a bare ApplyId: measured, plan 005's execute() returns five non-admitted
 * outcomes at or before admission -- OperationUnsupported (restart), StrategyUnsupported,
 * ScopeUnsupported (instance-wide apply) and NotAdmitted(reason, detail) from
 * AdmissionOutcome.Refused. A method returning ApplyId can express none of them, and Task 4's
 * own 501/refusal tests depend on all of them.
 */
sealed interface AdmissionResult {
    record Admitted(ApplyId id) implements AdmissionResult {}
    record OperationUnsupported(Operation op) implements AdmissionResult {}
    record StrategyUnsupported(Strategy s) implements AdmissionResult {}
    record ScopeUnsupported(ScopeKey scope) implements AdmissionResult {}
    record NotAdmitted(String reason, String detail) implements AdmissionResult {}
}
AdmissionResult admit(ApplyCommand command);

// Everything plan 005's execute() did after admission. Called ONLY by the durable worker.
ApplyOutcome runAdmitted(TenantId tenant, ApplyId id);
```

⛔ **`outcomes.start(t, id)` does NOT move into `runAdmitted`. It is absorbed into the claim.** In plan 005's
`execute()` it sits between admission and `run()`, and it performs `REQUESTED → RUNNING`. Task 4a's claim performs
the **same** transition. An earlier draft of this plan put the claim in `ApplyClaimRepository` *and* kept
`outcomes.start()` inside `runAdmitted` — **two `REQUESTED → RUNNING` transitions for one request.** The second
would always fail, because the first already moved the row: the worker would claim successfully and then its own
`start()` would refuse, so either every apply died at the starting line or `start()` had to be weakened into
something that tolerates `RUNNING`, which is exactly the fence plan 004 built it to be.

**The correction: one atomic guarded transition, which is simultaneously the claim and the semantic start.**

```java
/** Claim-as-start. The ONLY REQUESTED -> RUNNING transition in the system. */
Optional<ApplyId> claimNextAsStarted(TenantId tenant);
```

- It is `outcomes.start`'s transition, executed once, under `WHERE … AND state = 'REQUESTED'`.
- **Winning the claim *is* being started.** `runAdmitted(tenant, id)` is therefore entered on a row that is
  **already `RUNNING`**, and it **must not** call `start()` — it begins at the step loop.
- Exactly one worker's `UPDATE` affects the row, so exactly one worker reaches the adapter. The claim is not a
  lock *around* the start; it is the start.
- `outcomes.start` remains the single owner of that transition's semantics (the watermark, the fence, the
  `state_fits_operation` CHECK). The claim **calls it** rather than reimplementing the `UPDATE` — the repository
  exposes the guarded transition and the service keeps the meaning, so there is one transition and one owner, not
  one transition and two writers.

Measured, for the record: `AdmissionService` inserts `RequestState.REQUESTED` and its imports carry no
`apply.runtime` type, so the admission half is genuinely adapter-free and `REQUESTED` is a real, observable,
committed state before any worker exists.

⚠️ **This is a refactor of `ApplyExecutor`, not a second engine.** `execute(ApplyCommand)` becomes
`admit(...)` followed by `runAdmitted(...)`, and may remain as a **test-only** convenience that calls both in
sequence. The step loop, the fences, the journal, the retry budget and `resume` are untouched — plan 005's
semantics are reused, never re-implemented.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void submitRequiresConfigApply() { }
@Test void submitAlsoRequiresInstanceRestartWhenTheStrategyRestarts() { }
@Test void submitAnswers202WithAnApplyIdAndALocationHeader() { }
@Test void theOutcomeIsNotInTheResponseBody() {          // it is durable and asynchronous
    assertThat(body).doesNotContainKey("kind");
}
@Test void the202IsReturnedBeforeTheAdapterIsTouchedAtAll() {
    // The worker pool starts SUSPENDED and the counter is snapshotted at the moment the
    // response is read. Both matter: an earlier draft asserted callCount()==0 AFTER the
    // response with a live worker, which the CORRECT implementation fails -- the worker claims
    // the committed row within milliseconds and legitimately calls prepare().
    workers.suspend();
    adapter.blockForever();
    var timed = timed(() -> post("/dp/instances/i-1/apply"));
    int atResponse = adapter.callCount();
    assertThat(timed).hasStatus(202).completedWithin(Duration.ofMillis(500));
    assertThat(atResponse).isZero();
}
@Test void afterTheWorkerIsReleasedExactlyOneAdapterSequenceRuns() {
    // The complement. Without it, isZero() could be satisfied by never executing at all.
}
@Test void submissionPersistsTheRequestedRowInItsOwnTransactionAndCommitsIt() {
    // Rule 0, write-before-act: the row is durable before the worker can possibly see it.
}
@Test void restartAllIsRefusedWith501BecauseNoStrategyAssertionVerbExists() { }
@Test void rollingNoValidationIsRefusedWith501ForTheSameReason() { }
@Test void scheduledIsRefusedAsOutOfRelease() { }
@Test void aSecondInFlightApplyOnTheSameScopeIsRefusedAtAdmissionNotQueued() { }   // BE-D13 §3.3
@Test void theRequestIsPersistedBeforeAnyAdapterCallHappens() { }                  // §2.2 rule 0
@Test void noControllerEverConstructsOrCallsARuntimeAdapter() { }                  // see Task 12
@Test void requestedByStoresAnActorIdAndIsNeverConsultedForAuthorization() { }
```

- [ ] **Step 2: Run; all fail.** **Step 3: Implement** — the controller resolves, authorizes, submits and returns; the engine executes. **Step 4: Run green.**
- [ ] **Step 5: Prove they fire.** Return the outcome in the body → its test red. Accept `restart-all` → its test red. Call the adapter from the controller → Task 12's rule red.
- [ ] **Step 6: Commit.** `feat: authenticated apply submission — 202 + applyId over plan 005's durable engine`

⚠️ **This deliberately changes the as-built contract.** The as-built `POST …/apply` returns `200` with
`kind: "INDETERMINATE"` **always**, because open item 1 was open when it was written. The design now exists and is
implemented, so `APPLY_INDETERMINATE` becomes one terminal state among several. Record the change in the Results
section and in `console-integration.md` §9.1 — do not leave two contracts live.

---

## Task 4a — The durable apply worker, so `202` is true

**Files:** Create `backend/launcher/src/main/java/com/solace/sm/launcher/ApplyWorker.java`, `ApplyWorkerProperties.java`; Create `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/ApplyClaimRepository.java`; Create `…/dp-core/src/test/java/.../ApplyWorkerIT.java`, `ApplyClaimIT.java`.

**The queue is the apply journal.** No new durable store, no broker-as-queue, no in-memory handoff. A claim is a
row-level operation in PostgreSQL, which is already the only truth.

⛔ **The trigger is a bounded poll, and that must be stated rather than assumed.** An earlier draft specified the
claim and never said what causes one. The honest v1 answer: the worker polls for claimable rows on a configured
interval (`ApplyWorkerProperties.pollInterval`; the default is a measured value, order of a second), **plus** an
optional in-process nudge after a submission commits — the nudge is latency, never correctness. ⚠️ PostgreSQL
`LISTEN`/`NOTIFY` **must not be the only path**: it is not durable, so a notification lost to a reconnect would
strand a committed row until the next restart. The poll is what makes the 202 a promise.

**Interfaces:**
- Consumes: `ApplyService.admit`, `ApplyExecutor.runAdmitted`, `ApplyExecutor.resume`.
- Produces: **`ApplyClaimRepository.claimNextAsStarted(TenantId) → Optional<ApplyId>`** — the one atomic
  `REQUESTED → RUNNING` transition, delegating to `OutcomeService.start` for its meaning. ⛔ **No `release(...)`:**
  a claim is not a lease, so there is nothing to hand back; a row that stops being worked on is `RUNNING` until a
  terminal landing or the next startup sweep. `ApplyWorker` as a bounded pool owned by the launcher.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void aCommittedRequestedRowIsPickedUpAndExecutedWithoutAnHttpCall() { }
@Test void aRowCommittedWhileNoWorkerIsAwakeIsStillExecutedWithinThePollInterval() {
    // The lost-work window an in-process-notification-only design leaves open.
}
@Test void twoWorkersNeverExecuteTheSameRequest() {
    // The claim is the whole correctness argument. Run N workers against one row and assert
    // exactly one adapter sequence happened.
    assertThat(concurrentClaims(8, applyId)).hasSize(1);
}
@Test void thereIsExactlyOneRequestedToRunningTransitionPerRequest() {
    // The defect this task was corrected for: a claim that transitions the row AND a
    // runAdmitted() that calls start() again is two transitions, and the second must fail.
    // Assert it from the journal, which is the only place that cannot lie.
    claimAndExecute(applyId);
    assertThat(stateTransitionsOf(applyId).stream().filter(x -> x.to() == RUNNING)).hasSize(1);
}
@Test void runAdmittedDoesNotCallStartBecauseTheClaimAlreadyDid() {
    // Entered on a row that is ALREADY RUNNING. A start() here would refuse, and weakening
    // start() to tolerate RUNNING would delete plan 004's fence.
    var spy = spyOn(outcomes);
    executor.runAdmitted(tenant, claimed(applyId));
    verify(spy, never()).start(any(), any());
}
@Test void winningTheClaimIsWhatMakesARequestRunning() {
    assertThat(stateOf(applyId)).isEqualTo(REQUESTED);
    var won = claims.claimNextAsStarted(tenant);
    assertThat(won).contains(applyId);
    assertThat(stateOf(applyId)).isEqualTo(RUNNING);   // one call, one transition
}
@Test void aLoserOfTheClaimNeverTouchesTheAdapter() { }
@Test void aCrashDuringExecutionLeavesTheRowRunningAndTheStartupSweepRecoversIt() {
    // Restart-only reclaim, because the schema carries no lease. A row left RUNNING by a crash
    // is Task 4b's to recover -- NOT a deadline this task can compute.
}
@Test void aRunningRowIsNeverClaimableAgainByARunningProcess() {
    // The property that makes "no lease" safe rather than merely cheap: the guard refuses it,
    // so a second worker cannot adopt in-flight work and double-execute the adapter.
    claims.claimNextAsStarted(tenant);
    assertThat(claims.claimNextAsStarted(tenant)).isEmpty();
}
@Test void theClaimWritesNoColumnOutsideTheSixTheGrantPermits() { }
@Test void aCrashAfterAdmissionButBeforeAnyClaimIsPickedUpByTheNextPoll() {
    // The gap the 202 creates: the row exists, nothing has run, the state is still REQUESTED.
    // It needs no sweep -- it is indistinguishable from a row not yet picked up, which is
    // correct. Asserting it here is what stops someone "fixing" it with a lease.
}
@Test void onePoisonedRequestDoesNotStopTheOthers() {
    // Bounded retry then strand, per plan 005's retry cap and terminal mapping. The queue
    // must not head-of-line block.
}
@Test void theWorkerPoolIsBoundedAndRespectsItsConfiguredSize() { }
@Test void theSingletonWorkerTopologyIsEnforcedAtStartupNotAssumed() {
    // R8's ruling ("no v1 lease") is CONTINGENT on this. A second process or a second pool
    // would let a RUNNING row strand until a restart that may not come, and nothing in the
    // schema would notice. So the launcher refuses to start rather than silently voiding
    // the condition the ruling rests on.
    assertThatThrownBy(() -> bootWith(workerPools(2)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("single worker pool");
}
@Test void shutdownDrainsInFlightWorkAndStopsClaimingNewWork() {
    // A killed mid-apply request is resume's job; a GRACEFUL shutdown must not create one
    // needlessly.
}
@Test void shutdownBeyondTheGraceDeadlineLeavesARecoverableRowNotACorruptOne() { }
@Test void theWorkerNeverBypassesTheEngine() {
    // worker -> ApplyExecutor -> RuntimeAdapter. Task 12's rule is the build-time half.
}
```

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Implement the claim as a *state-only* compare-and-set — and understand why it cannot be richer.** Measured against the shipped DDL:
  - `sm_data.apply_request` has **no `claimed_by` and no `claimed_at` column**, and `V002`'s grant is
    **column-scoped**: `UPDATE (state, terminal_at, resolved_by, resolution_outcome, resolution_observed,
    attempt_summary)`. None of those can hold a worker identity.
  - `terminal_at` is the only writable timestamp, and `terminal_has_timestamp` **forces it NULL** while the state
    is `REQUESTED`/`RUNNING` — precisely the window a claim occupies. So there is nothing to compute a lease
    deadline from either.

  Therefore: **the claim is the single `state = 'REQUESTED' → 'RUNNING'` transition, guarded by
  `WHERE … AND state = 'REQUESTED'` — and it is also the semantic start** (Task 4's `claimNextAsStarted`). Exactly
  one `UPDATE` affects the row, so exactly one worker wins and exactly one worker reaches the adapter; that is the
  compare-and-set discipline plan 004's fences already use. `SELECT … FOR UPDATE SKIP LOCKED` is permitted for
  *selecting* candidates (legal: PG 14 needs `UPDATE` on at least one column, and six qualify) but is **not** a
  substitute for the guarded update. ⛔ **Worker identity is not recorded and there is no lease deadline** — an
  earlier draft described both, against a schema that has neither. **Reclaim is restart-only**: a crash
  mid-execution leaves the row `RUNNING`, and Task 4b's startup sweep recovers it.

  ⚠️ **Crash semantics, stated once so the three tasks agree.** There are exactly two windows, and they are
  distinguishable by state alone — which is why no lease is needed:

  | Crash point | Row state after the crash | Who recovers it |
  |---|---|---|
  | After admission commits, **before** any worker claims | `REQUESTED` | the **next poll** — any worker, no sweep needed. Indistinguishable from a row that simply has not been picked up yet, and that is correct |
  | After the claim commits, **during** execution | `RUNNING` | **Task 4b's startup sweep** via plan 005's `resume`, which is built for exactly this: an attempt row with no outcome |

  A `RUNNING` row is therefore **never claimable again by a running process** — the guard refuses it — and that is
  the property that makes "no lease" safe rather than merely cheap.

  ⚠️ **Routed, not decided (R8):** whether v1 needs in-process reclaim — a lease with `claimed_by`/`claimed_at` —
  is an owner decision, because it means **a migration widening the column-scoped grant**, whose own comment makes
  that scoping deliberate and `I5`-load-bearing. With one process and one pool, restart-only reclaim is sufficient
  and the migration is unnecessary. **Do not add the columns on the way past.**
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Prove they fire.** Remove the `WHERE` guard from the claim → `twoWorkersNeverExecuteTheSame…` red.
  Make the worker call the adapter directly → `theWorkerNeverBypassesTheEngine` red and Task 12's rule red. Remove
  the startup sweep → `aCrashDuringExecutionLeavesTheRowRunning…` red. Let one poisoned request retry unboundedly
  → `onePoisonedRequest…` red. Try to write a seventh column in the claim → `42501`, which is the DDL proving the
  shape rather than a test asserting it. ⛔ **And the mutation this correction exists for: put `outcomes.start()`
  back into `runAdmitted` → `thereIsExactlyOneRequestedToRunningTransitionPerRequest` and
  `runAdmittedDoesNotCallStartBecauseTheClaimAlreadyDid` both red**, and every apply dies at the starting line.
- [ ] **Step 6: Commit.** `feat: a durable in-process apply worker over the journal — PostgreSQL is the queue`

---

## Task 4b — Startup recovery, across every tenant

**Files:** Modify `…/launcher/SolutionManagerApplication.java`; Create `…/dp-core/…/persistence/TenantEnumerationRepository.java`; Create `…/dp-core/src/test/java/.../ResumeOnStartupIT.java`.

Plan 005 routed this here by name: *"`resume` has no production caller. The path is implemented and proven; nothing
invokes it on startup, because no application class exists"* → **"the plan that adds an application class."**
Without this, a crashed apply stays `RUNNING` in production forever while every test passes.

⚠️ **`resume(TenantId)` needs a tenant, and there is no tenant registry in `sm_data`.** The CP owns the tenant
registry and `I7` forbids the cross-schema read (`eda-walking-skeleton.md` §8 row 9). So recovery enumerates
tenants from the DP's **own** rows:

```sql
SELECT DISTINCT tenant_id FROM sm_data.apply_request
 WHERE state IN ('REQUESTED', 'RUNNING', 'APPLY_INDETERMINATE')
```

Three things to get right about that query, each of which an earlier draft of this task got wrong or left out:

1. ⚠️ **It is a strict superset of the *recoverable* set, not "exactly the set that has work to recover."**
   Measured, `ApplyExecutor.resume` skips `APPLY_INDETERMINATE` on purpose (*"resuming one would be the engine
   confirming its own uncertainty"*), and skips `RESTART` operations and instance-wide applies too. So this query
   is **enumeration only**; `resume`'s own filter stays the authority on what is recovered. The three-tenant test
   below must therefore seed **recoverable** work, or it fails for a correct implementation.
2. ⚠️ **The state set must not be restated in a second place.** `OutcomeService.nonTerminal` already owns it;
   `TenantEnumerationRepository` must reuse that definition rather than copy it, or the two drift silently.
3. ⚠️ **This is the codebase's first deliberately unscoped cross-tenant read**, and `I5` says *"every query goes
   through the central data-access module that applies tenant/authz scope."* It is legal (`V002`'s `GRANT SELECT`
   is table-wide) and it is necessary (there is no other way to learn which tenants have work), but it is a
   **stated, reviewed exception**: it reads one column, it runs only at startup, it is in the persistence package,
   and it feeds a per-tenant loop that is scoped from there on. Record it as such — do not let a reader discover
   it.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void startupRecoversRecoverableWorkForEveryTenantWithoutAHardCodedTenantId() {
    // Seed RECOVERABLE work, not merely non-terminal: resume deliberately skips
    // APPLY_INDETERMINATE, RESTART and instance-wide applies.
    seedRecoverable("tenant-a"); seedRecoverable("tenant-b"); seedRecoverable("tenant-c");
    bootTheApplication();
    assertThat(terminalStates()).hasSize(3);
}
@Test void aTenantWhoseOnlyNonTerminalRowIsApplyIndeterminateIsEnumeratedAndThenLeftAlone() {
    // The superset property, asserted rather than glossed.
}
@Test void theEnumerationReusesOutcomeServicesStateSetRatherThanCopyingIt() { }
@Test void aTenantWithNoNonTerminalWorkIsNotSweptAndCostsNothing() { }
@Test void theSweepRunsAfterMigrationsAndBeforeTheHttpConnectorAccepts() {
    // A request served mid-sweep can be admitted against a stale fence.
}
@Test void theSweepIsIsolatedPerRequestSoOneStrandDoesNotAbortTheRest() { }
@Test void theSweepIsIdempotentAcrossTwoConsecutiveStartups() { }
@Test void resumeSpendsOnePreflightAttemptAndTheBudgetAccountsForIt() {
    // Plan 005's second watch item: resume re-runs pre-flight, consuming one attempt of a
    // finite cap. At the default cap of 3 a request survives two resumes.
}
@Test void recoveryReusesPlan005ResumeAndIntroducesNoSecondStateMachine() { }
@Test void theSweepRecoversRunningRowsAndLeavesRequestedOnesToTheNextPoll() {
    // The two crash windows, distinguished by state alone -- which is what makes the
    // absent lease (R8) safe.
    seedRunningAfterCrash("a"); seedRequestedNeverClaimed("b");
    bootTheApplication();
    assertThat(stateOf("a")).isTerminal();
    assertThat(stateOf("b")).isEqualTo(REQUESTED);   // the poll's job, not the sweep's
}
@Test void theSweepNeverCallsStartBecauseTheDeadWorkerAlreadyDid() { }
```

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Implement** as an ordered startup step that logs a classifier per request and never throws into startup.

  ⚠️ **What the sweep is for, given the claim-as-start correction.** The sweep's job is **`RUNNING` rows only** —
  requests whose worker won the claim and then died. A `REQUESTED` row needs no sweep: the next poll takes it, and
  it is indistinguishable from one not yet picked up. So the sweep does **not** re-claim; it hands `RUNNING` rows
  to plan 005's `resume`, which is built for an attempt row with no outcome, and it must **not** call
  `outcomes.start` either — that transition already happened when the dead worker claimed.
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Prove they fire.** Hard-code one tenant → the three-tenant test red. Move the sweep after the port opens → its test red. Drop per-request isolation → its test red. **Have the sweep re-claim or call `start()` → `theSweepNeverCallsStartBecauseTheDeadWorkerAlreadyDid` red.** **Have it sweep `REQUESTED` rows too → `theSweepRecoversRunningRowsAndLeavesRequestedOnesToTheNextPoll` red.**
- [ ] **Step 6: Commit.** `feat: startup recovery across every tenant with non-terminal work`

⚠️ **Plan 005's third watch item lands in Task 9, not here:** `resume` is proven only against an **in-memory**
adapter. Task 9 scenario 11 exercises it against SSH-Docker, or "crash-safe resume" is a claim about a fake.

## Task 5 — Event emission: the first production publish site

**Files:** Create `backend/dp-core/src/main/java/com/solace/sm/dp/apply/events/ApplyEventEmitter.java`; Modify the engine's transition points; Create `…/apply/events/ApplyEventEmitterTest.java`, `…/ApplyEventPublishIT.java`.

**Interfaces:**
- Consumes: `EventPublisher` (already wrapped by `AfterCommitEventPublisher`), `NotifyEventShapes`, `NotifyTopics`.
- Produces: `ApplyEventEmitter.onStaged(...)`, `onRestartRequested(...)`, `onRestartObserved(...)`, `onConfigStateReached(...)`, `onHealthTransition(...)`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void aRolloutEventIsPublishedForEachOfTheThreeStepsAndNoOthers() {
    // step ∈ staged | restart-requested | restart-observed — the closed vocabulary
}
@Test void everyRolloutEventCarriesPlacementIdBecauseRolloutIsPlacementScoped() { }
@Test void aConfigStateEventCarriesNoPlacementIdBecauseItIsInstanceScoped() { }
@Test void nothingIsPublishedUntilTheTransactionCommits() {
    inTransaction(() -> { emitter.onStaged(...); assertThat(transport.sent()).isEmpty(); });
    assertThat(transport.sent()).hasSize(1);
}
@Test void aRolledBackTransactionPublishesNothing() { }
@Test void noPayloadCarriesAConfigValueASecretOrAMetricValue() { }        // I6
@Test void stateVersionIsMonotonicPerEntityAcrossEmissions() { }
@Test void aMalformedEventThrowsIntoTheCallerAndNeverReachesTheWire() { }
@Test void aTransportFailureIsSwallowedCountedAndNeverFailsTheApply() {
    transport.failNext();
    assertThatNoException().isThrownBy(() -> emitter.onStaged(...));
    assertThat(publisher.droppedCount()).isEqualTo(1);
}
@Test void theEmittedEventsValidateAgainstTheContractFixturesShapes() { }
@Test void noEventOutsideTheApprovedContractIsEverPublished() {
    // Replaces a test that celebrated the absence of a terminal event. The absence is a GAP,
    // not a property to protect (BE-D4-AMENDMENT-PROPOSAL.md). What must be guarded is that
    // nobody invents an event type ahead of its contract.
    assertThat(publishedEventKeys()).allMatch(NotifyEventShapes::isCatalogued);
}
@Test void theEmitterEmitsOnlyDpProducerEventsAndNeverClaimsCpCore() {
    assertThat(publishedEnvelopes()).extracting(EventEnvelope::producer)
            .containsOnly("dp-core");
}
```

- [ ] **Step 2: Run; all fail.** **Step 3: Implement**, publishing through the seam at the journal's own transition points — never from a controller, never from an adapter. **Step 4: Run green.**
⛔ **`catalog.package` is NOT emitted from here.** An earlier draft of this task added `onPackageLoaded(...)` to
`dp-core`'s emitter. `backend/design.md` §3's catalog row assigns that event to **`cp-core`**, and
`envelope.schema.json` closes `producer` to `['cp-core','dp-core']` — so a DP-side emitter would have to mislabel
its producer or claim the CP's id. It moves to **Task 5a**.

- [ ] **Step 4a: Export the drop counter** as a metric/actuator gauge. `console-integration.md` §2.1 records that today it is an in-process `AtomicLong` only, and "drops must remain observable" is not satisfied by a getter nobody reads. Add a test asserting the metric moves with the counter.
- [ ] **Step 5: Prove they fire.** Publish before commit → its test red. Drop `placementId` from a rollout event → its test red **and** the shape validator throws. Delete the drop increment → its test red (an R8 mutation run already proved this class of test works).
- [ ] **Step 6: Commit.** `feat(dp-core): publish contract-defined rollout, config-state and health notices after commit`

---

## Task 5a — `CatalogEventEmitter` in `cp-core`, because `catalog.package` is the CP's event

**Files:** Create `backend/cp-core/src/main/java/com/solace/sm/cp/events/CatalogEventEmitter.java` and `…/cp/events/CpTransactionBoundary.java`; Modify `backend/cp-core/pom.xml` (it has no `mesh-events` dependency and no test dependencies today); Modify `…/launcher/SolutionManagerConfiguration.java` (construct the **CP-local** after-commit wrapper over the **one shared raw transport**); Create `…/cp-core/src/test/java/.../CatalogEventEmitterTest.java` and `CpTransactionBoundaryTest.java`. ⛔ **No `AbstractEventPublisherContractTest` subclass** — see §3 below.

**Why this is permitted by the existing design rather than a new rule** — measured, and the design says it in terms:

- `PlaneBoundaryTest`'s comment above `eventsNeverImportCp`/`eventsNeverImportDp`: *"neutrality is exactly what
  makes it safe for **BOTH planes** to consume, which Task 9 onward will want."*
- `EventPublisher`'s javadoc: *"It lives in `mesh-events`, not in a plane module. The seam is plane-neutral by
  construction — **both planes publish through it**."*
- The two ArchUnit rules point the **other** way (`mesh-events` must not import a plane), so no rule is violated.

⛔ **But there IS a written constraint to reverse, and it needs a decision — not a POM edit.**
`backend/cp-core/pom.xml`'s own `<description>` reads: *"Control-plane implementation, owns `sm_control` … **Depends
on `cp-api` only — never on `dp-api`, `dp-core` or `mesh-events` (`I7`)**."* An earlier draft of this task claimed
"no design conflict to record" while citing a measurement of that very POM. The two quotes above establish that the
**shared module** is safe for both planes to consume; they do not establish that `cp-core` was left free to consume
it.

So Step 3 **records a decision** — ⛔ **allocating the next free id from the merged `DECISIONS.md`** after plan 005
merges (ruling **OR-11**); no id is reserved here:
`cp-core` depends on `mesh-events`; `I7` is unaffected because the seam is plane-neutral and the injected bean is
typed `EventPublisher`; and **the POM's `<description>` is corrected in the same change**, or the next reader
measures it and reaches the opposite conclusion.

**Interfaces:**
- Consumes: `EventPublisher` (the interface, from `mesh-events`) — ⚠️ **the CP's own wrapper instance, not the DP's**
  (see below).
- Produces: `CatalogEventEmitter.onPackageLoaded(TenantId, SolutionId, String packageVersion)`; and in `cp-core`,
  `CpTransactionBoundary implements TransactionBoundary`.

### ⛔ One shared raw transport, two plane-local after-commit wrappers

An earlier draft of this task said the launcher would *"inject the one concrete publisher into both emitters."*
Measured, that is the **wrapped** one: `MeshBridgeConfiguration` declares the raw
`@Bean(defaultCandidate = false) JcsmpEventPublisher meshEventPublisher(...)` and then a `@Primary`
`EventPublisher` bean built as `new AfterCommitEventPublisher(meshEventPublisher, new SpringTransactionBoundary())`.
Injecting **that** into `cp-core` hands the CP a **dp-core-owned wrapper** whose `TransactionBoundary` was
constructed by and for the DP — so the CP's after-commit ordering, the one property `BE-D1` rule 4 exists to
guarantee, would be governed by a bean the CP does not own and cannot reason about. With two datasources and two
transaction managers that is a correctness question, not a tidiness one.

**The shape instead — shared where sharing is correct, plane-local where it is not:**

| Layer | Instances | Why |
|---|---|---|
| **Raw broker transport** — `JcsmpEventPublisher` | ⛔ **exactly one**, the existing `meshEventPublisher` bean | *"no other module holds a broker session"*; `onlyMeshbridgeTouchesJcsmp` confines the SDK to `dp-core.meshbridge`, and the drop counter must stay a single number |
| **`AfterCommitEventPublisher` wrapper** | ✅ **one per plane** — DP's existing `@Primary` bean, plus a **new CP-local** one | Each wraps the *same* raw transport but with **its own plane's `TransactionBoundary`**, so "publish after *my* commit" means the plane's own transaction |
| **`TransactionBoundary`** | one per plane: `SpringTransactionBoundary` (dp-core, existing) and **`CpTransactionBoundary` (cp-core, new)** | ⚠️ `SpringTransactionBoundary` lives in `dp-core` and `cpNeverImportsDp` forbids the CP from touching it — so the CP needs its own, bound to the CP's transaction context |

**How the launcher wires it, precisely:** it resolves the raw bean by type
(`context.getBean(JcsmpEventPublisher.class)` — unaffected by `defaultCandidate = false`, which the bean's own
javadoc records) or by the load-bearing `@Qualifier("meshEventPublisher")`, and constructs
`new AfterCommitEventPublisher(rawTransport, new CpTransactionBoundary())` as a **`cp-core`-qualified**
`EventPublisher` bean that `CatalogEventEmitter` consumes. The DP's `@Primary` bean is left exactly as it is.

⚠️ **Both wrappers are already-kitted implementations of a tested contract** (`AfterCommitEventPublisher` has its
own contract test in `dp-core`), so a second *instance* adds no second implementation — which is why §3 below says
this task does not extend the publisher inventory.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void aCatalogPackageEventCarriesProducerCpCore() {
    assertThat(published().producer()).isEqualTo("cp-core");
}
@Test void itDerivesTheCatalogTopicAndCarriesNeitherInstanceNorPlacementIdentity() {
    // the schema forbids both; NotifyEventShapes rejects either
    assertThat(topic()).isEqualTo("sm/v1/acme/notify/catalog/package");
}
@Test void nothingIsPublishedUntilTheCpTransactionCommits() { }
@Test void aRolledBackPackageLoadPublishesNothing() { }
@Test void cpCoreImportsNoDpType() {
    // cpNeverImportsDp is the build-time half; this test says why it matters here.
}
@Test void theEmitterHoldsNoBrokerSessionAndNoJcsmpType() {
    // onlyMeshbridgeTouchesJcsmp confines the SDK to dp-core's meshbridge. cp-core receives
    // the publisher as an INTERFACE, injected by the launcher.
}
@Test void theCpWrapperIsBoundToTheCpsOwnTransactionContext() {
    // NOT the DP's wrapper. The CP's AfterCommitEventPublisher is constructed with
    // CpTransactionBoundary, so "after commit" means after the CP's commit.
    assertThat(boundaryOf(cpPublisher())).isInstanceOf(CpTransactionBoundary.class);
}
@Test void thereIsExactlyOneRawBrokerTransport() {
    // Shared where sharing is correct: one broker session, one drop counter.
    assertThat(context.getBeansOfType(JcsmpEventPublisher.class)).hasSize(1);
}
@Test void aDpCommitDoesNotFlushACpEventOrTheOtherWayAround() {
    // The failure the shared-wrapper shape would have allowed: two planes' orderings
    // entangled through one boundary instance.
}
```

⛔ **No `AbstractEventPublisherContractTest` subclass, and this task adds nothing to the publisher inventory.**
`CatalogEventEmitter` **is not an `EventPublisher` implementation** — it is an emitter that *calls* one. The kit's
obligation is **per implementation** (`PlaneBoundaryTest`: *"Every concrete `EventPublisher` implementation must be
listed here, and listing one is a PROMISE that its module carries a test class extending
`AbstractEventPublisherContractTest` over it"*), and the only two implementations — `JcsmpEventPublisher` and
`AfterCommitEventPublisher` — are **already listed and already kitted in `dp-core`**. The CP contributes a second
*instance* of an existing implementation, not a new one. So: **leave `PUBLISHER_IMPLEMENTATIONS` untouched**, and
do not subclass the kit — doing so would re-run it over `AfterCommitEventPublisher` and would force `cp-core` to
*instantiate* a publisher it otherwise only injects.

✅ **What the CP tests must prove, and do keep:** the **commit/rollback** pair above — nothing published until the
CP's own transaction commits, and nothing at all on rollback — plus `producer: "cp-core"`, the catalog topic, and
the forbidden-identity shape. Those are the CP-specific properties, and they are precisely what a shared kit
cannot check for it.

- [ ] **Step 2: Run; all fail** (no emitter, no dependency).
- [ ] **Step 3: Implement — and note what the POM actually needs.** Measured: `cp-core/pom.xml` has **no test
  dependencies at all**, and `backend/pom.xml` declares none outside `dependencyManagement`, so the parent
  contributes nothing at test scope. Required:
  1. `mesh-events` at **compile** scope — for `EventPublisher`, `AfterCommitEventPublisher`, `TransactionBoundary`,
     `EventEnvelope`, `NotifyTopics`.
  2. `mesh-events` with **`<type>test-jar</type>`** at test scope — ⚠️ **justified by a concrete utility the CP
     tests genuinely consume, not by the kit**: `com.solace.sm.events.contracttest.CapturingTransport`, which is
     what makes *"nothing published until the CP transaction commits"* assertable without a broker. `dp-core`
     already declares this edge for the same module's `ContractArtifacts`. **If the CP tests end up consuming no
     `mesh-events` test type, drop this edge** — an edge whose only justification was the kit would be dead weight
     the next reader has to re-derive.
  3. **`junit-jupiter`** (the aggregate artifact) at test scope — needed for any test at all in a module that
     currently has none, not because of the kit.

  Then: record the decision from the first bullet, correct the POM `<description>`, write `CpTransactionBoundary`,
  and have the launcher construct the **CP-local wrapper over the one shared raw transport** — so there is still
  exactly one broker session owner and exactly one drop counter, with per-plane commit ordering.
- [ ] **Step 4: Run green** — and confirm `cpNeverImportsDp`, `eventsNeverImportCp`, `eventsNeverImportDp`,
  `onlyMeshbridgeTouchesJcsmp` **and the publisher-inventory test** are all **still green**. The inventory test
  matters here precisely because this task adds no implementation to it.
- [ ] **Step 5: Prove they fire.** Emit with `producer: dp-core` → the first test red. Publish before commit → its
  test red. Have `cp-core` import `JcsmpEventPublisher` or `SpringTransactionBoundary` directly →
  `cpNeverImportsDp` red. **Inject the DP's `@Primary` wrapped `EventPublisher` into the CP emitter →
  `theCpWrapperIsBoundToTheCpsOwnTransactionContext` red.** **Construct a second `JcsmpEventPublisher` →
  `thereIsExactlyOneRawBrokerTransport` red.**
- [ ] **Step 6: Commit.** `feat(cp-core): CatalogEventEmitter — the CP publishes its own catalog.package`

---

## Task 6 — REST observation and backfill by `stateVersion`

**Files:** Create `…/gateway/http/ObserveController.java`; Modify `dp-api`'s `ApplyService`; Create `…/ObserveBackfillIT.java`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void observeReturnsTheJournalsTruthNotTheAdaptersLastAnswer() { }
@Test void observeCarriesTheEntitysStateVersionSoAClientCanDiscardStaleNotices() { }
@Test void observeIsReadableWithConfigViewAndRefusedWithout() { }
@Test void aClientThatMissedEveryEventReachesTheSameStateByObservingAlone() {
    // the healing property, asserted rather than assumed
}
@Test void observeIsIdempotentAndCarriesNoSideEffect() { }
@Test void aTerminalStateIsDistinguishableFromStillRunning() { }
```

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Implement.** The response is assembled **from the journal** (`apply_request` + `apply_step_intent` + `apply_attempt`), never from an adapter call — an observation must be answerable when the host is unreachable. It carries the entity's `stateVersion` per §6.1's normative rule, and a terminal state is distinguishable from a running one by an explicit field, not by the absence of one.
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Prove they fire.** Serve the adapter's cached answer instead of the journal → the first test red. Omit `stateVersion` → its test red.
- [ ] **Step 6: Commit.** `feat: REST observation and stateVersion-keyed backfill — the path every recovery uses`

---

## Task 6a — The read surface: inventory, designer, health, ladder, logs — and package load

**Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/{InstanceQueryService,ConfigService,ObservationService}.java`; Create `backend/dp-core/src/main/java/com/solace/sm/dp/apply/observe/ObservationServiceImpl.java` (⚠️ **in `dp-core`, and it may depend on `apply.runtime`'s `RuntimeStatus`/`RuntimeDiagnostics`** — the narrowed Task 12 rule permits a read, not a transport); Create `…/gateway/http/{InstanceController,ConfigController,GovernanceController,SolutionController}.java`; Create `backend/cp-api/.../SolutionCatalogService.java` and its `cp-core` implementation; Create the matching `*IT` tests.

**Why this task exists.** An earlier draft of this plan assigned §5.2/§5.3's read surface to plan 007 in the shared
contract and then **built none of it** — so Task 11's walkthrough called for reads nothing produced, Task 13's
parity check was against endpoints that were never planned, and Task 5a's `CatalogEventEmitter.onPackageLoaded`
had no caller.

**Operations** (`console-integration.md` §5.2/§5.3, all already mapped to verb and scope):
`POST /cp/solutions/load` · `GET /cp/solutions` · `GET /cp/home/summary` · `GET /cp/home/attention` ·
`GET /cp/targets` · `GET /cp/hierarchy/*` · `GET /dp/solutions/{id}/instances` ·
`POST /dp/solutions/{id}/instances` · `GET /dp/instances/{id}` · `GET /dp/instances/{id}/designer` ·
`POST /dp/instances/{id}/config` · `GET …/config/history` · `POST …/config/secrets/{fieldKey}` ·
`POST …/config/accept-runtime` · `POST …/config/restore-runtime` · `GET …/health` · `GET …/config-ladder` ·
`GET|POST …/log-levels/{component}` · `GET …/logs/{component}`.
⛔ **Not** `DELETE /dp/instances/{id}` or `POST …/actions/{actionKey}` — no approved verb (open item 39d/39e).

- [ ] **Step 1: Write the failing tests.**

```java
@Test void everyReadIsAuthorizedByItsMappedVerbAtItsMappedScope() { }        // §5's table, row by row
@Test void aSiblingEnvironmentsInstanceIsNotVisibleToAnEnvironmentScopedPrincipal() { }
@Test void healthAndStatusReadsReachTheHostOnTheRequestThreadAndAreBounded() {
    // A read is allowed to be synchronous; it is NOT allowed to be unbounded.
    assertThat(timed(() -> get("/dp/instances/i-1/health"))).completedWithin(readDeadline());
}
@Test void anUnreachableHostYieldsUnknownRatherThanA500() { }
@Test void aLogTailIsBoundedAndPaginatedByTimestamp() { }                    // the demo learning
@Test void aSecretFieldPostStoresAReferenceAndNeverReturnsAResolvedValue() { }   // I6
@Test void configHistoryIsRedactedServerSide() { }                              // I6
@Test void packageLoadEmitsCatalogPackageAfterTheCpTransactionCommits() { }      // Task 5a's caller
@Test void noControllerHoldsAnSshOrDockerTransportType() { }                     // Task 12's rule
```

- [ ] **Step 2: Run; all fail.** **Step 3: Implement**, reads assembled from the journal and the adapter's
  observation operations, with a bounded deadline and `UNKNOWN` rather than a fault when a host is unreachable.
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Prove they fire.** Drop one verb check → its row's test red. Return a resolved secret → its test
  red. Remove the read deadline → the bounded test red.
- [ ] **Step 6: Commit.** `feat: the authenticated read surface — inventory, designer, governance, package load`

---

## Task 6b — The production `stop` endpoint (ruling `OR-18`)

✅ **Assigned to this plan by ruling [OR-18](../../design/DECISIONS.md#owner-architecture-rulings--2026-09-16).**
⚠️ **Before OR-18 no plan owned it:** this plan's non-goals declined it and plan 008's table pointed here. That
gap is closed; the non-goals row is corrected to name this task.

✅ **The endpoint and end-to-end execution are buildable on the current integration baseline.** The historical
`2a0047e` measurement had `Operation.STOP`, `PlacementId.INSTANCE_WIDE`, `RuntimeAdapter.stop(TargetReplica)` and
the `AdmissionService` branch, but lacked the generation-free `replicas(PlacementRef)` observation. Plan 005's
merged `BE-D25` now supplies it, so the endpoint, DTO, authorization, audit, idempotency, command construction and
terminal integration tests can be implemented together. The endpoint itself remains unimplemented until this task
is executed.

⛔ **The request shape is MEASURED against Plan 005's engine, not invented.** Read
`backend/dp-core/src/main/java/com/solace/sm/dp/apply/model/ApplyCommand.java` and
`…/apply/model/PlacementId.java` on the **current integration baseline** before writing the DTO. The `ba7db2e`
measurement below is historical provenance for the Plan 005 behavior, not a branch checkout instruction. What the engine actually
accepts (measured on `ba7db2e`, now present through ancestry):

| Engine fact | Where it is proven | What the API must therefore do |
|---|---|---|
| `ApplyCommand(ScopeKey scope, Operation operation, Long generation, Strategy strategy, String outageConfirmedBy, String requestedBy)` | `ApplyCommand.java` | build exactly this record |
| `(operation == APPLY) != (generation != null)` — a compile-free invariant enforced in the constructor | `ApplyCommand.java` | ⛔ **`generation` MUST be `null` for a stop.** `apply_has_generation` makes a stop's generation NULL in the journal too |
| `lifecycle != (strategy != null)`, and the stop fixture passes `strategy = null` | `ApplyCommand.java`, `StopApplyIT.stop(…)` | ⛔ **no restart strategy on a stop** — the request carries none, and ⛔ **`outageConfirmedBy` is `null`** |
| `ScopeKey(TenantId, InstanceId, PlacementId)` | `StopApplyIT.scope(…)` | the path supplies the instance; the body optionally supplies the placement |
| `PlacementId.INSTANCE_WIDE` is the **nil-UUID sentinel** (`new UUID(0L,0L)`), with `isInstanceWide()` | `PlacementId.java` | ⛔ **omitting `placementId` means instance-wide** — send the sentinel, never a fabricated id |
| `BE-D26`: *"a stop is scope-granular — one placement, or a whole instance"* | `StopApplyIT` header | both targets are legal; **do not narrow the API to one** |
| An instance-wide stop *"addresses every placement the instance has a history with"*, all under **one** `apply_id` — legal because `placement_scoped_steps` does not pin `STOP` | `anInstanceWideStopAddressesEveryPlacementTheInstanceHasAHistoryWith` | one `202 + applyId` for an instance-wide stop, **not one per placement** |
| Idempotent: *"stopping an already-stopped replica succeeds without acting"* | `stoppingAnAlreadyStoppedReplicaSucceedsWithoutActing` | a repeat stop is `202`, not `409` |
| `requestedBy` must be a non-blank principal id | `ApplyCommand.java` | pass the **session principal**, never a client-supplied value (`I5`) |

**Files:**
- Modify: `contracts/openapi/console.yaml` — add the operation (Task 1 owns the document; this adds one path)
- Create: `backend/cp-api/…/dp/api/StopRequest.java` (or the `dp-api` DTO package Task 4 established)
- Modify: `…/dp/http/ApplyController.java` — the stop handler beside submit
- Test: `…/dp/http/StopEndpointIT.java`, and one `arch-tests` row for the verb mapping

**Interfaces:**
- Consumes: Task 4's submission path and `AdmissionService`; plan 006's `authorizationService.require`, `SessionService`, audit writer.
- Produces: `POST /dp/instances/{instanceId}/stop` → `202 {applyId, stateVersion}`.

- [ ] **Step 1: Write the failing tests.**

```java
// Authorization — instance.stop is Administrator-only, HIGH (catalog row 36)
@Test void stopRequiresInstanceStopAtTheOwningInstanceScope() { }
@Test void anOperatorIsRefusedStopWith403() { }          // Operator's 19 exclude instance.stop
@Test void aViewerIsRefusedStopWith403() { }

// OR-16: placement targeting authorizes through the OWNING INSTANCE, never a placement binding
@Test void aPlacementTargetedStopIsAuthorizedAtTheOwningInstanceScope() { }
@Test void aSiblingEnvironmentsInstanceCannotBeStopped() { }   // prefix rule, not a placement rule
@Test void thereIsNoPlacementScopedBindingPathInTheStopHandler() { }

// Engine semantics — measured, not invented
@Test void anOmittedPlacementIdBecomesInstanceWideAndReturnsOneApplyId() { }
@Test void aPlacementTargetedStopAddressesThatPlacementOnly() { }
@Test void theCommandCarriesNullGenerationAndNullStrategy() { }
@Test void requestedByIsTheSessionPrincipalAndNotTheRequestBody() { }

// Idempotency
@Test void stoppingAnAlreadyStoppedInstanceReturns202AndRecordsOneAuditLine() { }

// Audit — HIGH consequence
@Test void everyStopWritesOneAppendOnlyAuditLineWithKindHigh() { }
```

- [ ] **Step 2: Run; they fail.**
- [ ] **Step 3: Implement.**
  - **OpenAPI:** `POST /dp/instances/{instanceId}/stop`; body `{ "placementId": "uuid|null" }` — ⚠️ **optional, and
    `null`/absent means instance-wide**; response `202 {applyId, stateVersion}`; `403` for a refused verb, `404`
    for an instance outside scope (§3.4's discrimination), `409` only for a genuine admission conflict.
    ⛔ **No `generation`, no `strategy`, no `outageConfirmedBy` field** — the engine rejects all three on a stop,
    so accepting them would be an API that cannot execute.
  - **Authorization:** one `authorizationService.require(principal, Permission.INSTANCE_STOP, ResourceRef.instance(instanceId))`
    call. ⛔ **Resolve a placement-targeted stop to its owning instance's `ScopePath` and check there** (OR-16).
    ⛔ **No controller-local role check**, and no `isAdmin`.
  - **Audit:** one append-only line, `HIGH`, before the `202` returns; records principal, instance, placement (or
    the instance-wide sentinel) and `applyId`. ⚠️ **Idempotent repeat still writes a line** — the engine's
    no-op is not an audit no-op.
  - **Command:** `new ApplyCommand(new ScopeKey(tenant, instanceId, placementId == null ? PlacementId.INSTANCE_WIDE : placementId), Operation.STOP, null, null, null, principal.id().toString())`.
    ⚠️ **Note the `.toString()`** — `requestedBy` is a `String` and plan 006's `PrincipalRef` holds a `UUID`;
    passing `principal.id()` bare does not compile.
  - ⚠️ **Two post-admission failures the API must name, both measured** (`StopApplyIT` on `plan005-runtime-completion`):
    `stop.no.known.placement` — an instance-wide stop against an instance with no placement history — and
    `stop.replica.label.collision`. Both land **after** admission, so the endpoint still returns `202`; they
    surface as a terminal `STOP_FAILED` on the request, and the OpenAPI's `202` description must say so rather
    than implying success.
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Prove they fire.** Grant `instance.stop` to `Operator` in a fixture → the refusal tests red.
  Accept a `generation` field → the engine throws and `theCommandCarriesNullGeneration…` red. Check the verb at a
  placement scope → `aPlacementTargetedStopIsAuthorizedAtTheOwningInstanceScope` red. Drop the audit line → its
  test red.
- [ ] **Step 6: Commit.** `feat(dp): the authenticated stop endpoint — instance.stop, Administrator-only, audited, idempotent`

---

## Task 7 — Frontend handoff: `LiveStateClient`, the browser's one mesh seam

⛔ **HARD-GATED by open items 32/33/35** — see [Acceptance](#acceptance--what-this-plan-may-and-may-not-claim). Buildable and testable against a local broker; **not enablable for any shared or customer install**, and not usable to demonstrate scoped RBAC.

**Frontend-owned handoff, not backend-plan acceptance:** hand off the contract, invalidation table and worklist below to the frontend. The listed frontend files and tests are implemented and accepted by that consumer delivery; Plan 007 records the handoff and does not mark this work done or use it to close backend APIs.

**Interfaces:** `BE-D14` §4.3's interface, implemented verbatim:

```ts
interface LiveStateClient {
  start(tickets: TicketSupplier): void;
  stop(): void;
  readonly state: 'live' | 'degraded' | 'stopped';
  onNotify(handler: (n: NotifyEvent) => void): Unsubscribe;
  openTail(grant: StreamGrant, sink: TailSink): TailHandle;
  onResync(handler: (reason: 'connected' | 'renewed' | 'recovered') => void): Unsubscribe;
}
```

- [ ] **Step 1: Write the failing tests.**

```ts
it('exposes no publish API at all', () => {
  expect(Object.keys(client)).not.toContain('publish');
});
it('stores the ticket in memory only — never localStorage, sessionStorage or a cookie', () => { });
it('schedules renewal from the server-supplied renewAfter, never from exp', () => { });
it('treats renewal as a planned reconnect and fires onResync("renewed")', () => { });
it('fires onResync("recovered") after an expiry-driven disconnect', () => { });
it('bounds connect retries and enters degraded rather than retrying forever', () => { });
it('resyncs on an undifferentiated AUTH_FAILED and renders no cause it cannot know', () => { });
it('surfaces SUBSCRIBE_ACL_DENIED as actionable', () => { });
it('routes every one of the six catalog events and throws on an unknown event key', () => { });
it('invalidates query keys and never calls setQueryData from a payload', () => {
  const spy = vi.spyOn(queryClient, 'setQueryData');
  router.handle(healthEvent); expect(spy).not.toHaveBeenCalled();
});
it('discards a notice whose stateVersion is not newer than the state held', () => { });
it('never sorts or discards by occurredAtUtc', () => { });
```

- [ ] **Step 2: Run; all fail.** **Step 3: Implement**, with the invalidation table from `console-integration.md` §7 as the router's only routing rule. **Step 4: Run green.**
- [ ] **Step 5: Prove they fire.** Call `setQueryData` from a payload → its test red. Sort by `occurredAtUtc` → its test red. Persist the ticket → its test red.
- [ ] **Step 6: Commit.** `feat(frontend): the LiveStateClient seam — events invalidate, REST supplies, nothing publishes`

---

## Task 8 — Frontend handoff: visible degraded mode and the guard against fixture fallback

**Frontend-owned handoff, not backend-plan acceptance:** hand off this implementation and test worklist to the frontend. Its screen wiring, bundle guard and browser evidence remain required for overall EDA/browser acceptance, but are neither changed nor accepted by this backend plan.

- [ ] **Step 1: Write the failing tests.**

```ts
it('renders "Live" only while the session is up', () => { });
it('renders a degraded banner with a reason class, never a silent fallback', () => { });
it('keeps polling at the BE-D6 interval while degraded and stops on recovery', () => { });
it('does not flip the whole console to degraded because the apply screen polls', () => { });
it('never resolves a failed live request with fixture data', async () => {
  server.use(http.get('/api/dp/instances/:id/health', () => HttpResponse.error()));
  await expect(repo.health('i-1')).rejects.toBeInstanceOf(ApiError);   // not fixture data
});
it('has no runtime path from live mode to fixture mode', () => { });
```

Plus the build guard, in the established idiom — **a guard with a self-test that plants a violation and checks it
is reported**:

```sh
# check-guards.sh — no production entry point may import the test doubles
if grep -rqE "from '.*shared/testdoubles" src/app src/features src/shared --include='*.ts*' \
     --exclude='*.test.ts*' --exclude='*.testdouble.ts'; then
  echo "guard: production code imports shared/testdoubles"; exit 1
fi
```

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Implement.** The degraded poll interval is `BE-D6`'s, not a new number. The banner names a **reason class** (`mesh-unreachable` · `ticket-unobtainable` · `retries-exhausted`) and never a raw broker error — G1 measured that `AUTH_FAILED` covers five causes indistinguishably. The apply screen's §9 poll sets a **surface-level** indicator, not the session-level one.
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Prove the guard fires.** Add a production import of a fixture module → `check-guards.sh` red naming the file. Remove it. Assert the fixture modules are absent from the production bundle.
- [ ] **Step 6: Commit.** `feat(frontend): visible degraded mode, and a guard that makes fixture fallback impossible`

---

## Task 9 — The failure tests that settle the EDA claims

**Backend acceptance:** create `backend/dp-core/src/test/java/.../MeshResilienceIT.java` for scenarios **1–3 and 11**. **Frontend handoff:** scenarios **4–10** belong in `frontend/src/shared/mesh/resilience.test.ts`; they are not backend-plan completion evidence.

These are the tests the claim "event-driven with REST healing" stands or falls on. **Each must fail before the
behaviour exists.**

| # | Scenario | Asserted outcome |
|---|---|---|
| 1 | Broker down **before** an apply | The apply **completes and commits**; the journal is correct; `droppedCount()` rises; the console reaches the right state by observing |
| 2 | Broker dies **mid-apply**, after `staged`, before `restart-observed` | Same: durable state intact, one or more notices dropped and counted, REST heals |
| 3 | A notice is dropped (transport throws once) | No exception reaches the engine; the counter and the exported metric both move |
| 4 | Browser disconnects and reconnects | `onResync('recovered')` → refetch → the console equals the server's state |
| 5 | Ticket expires in a backgrounded tab | Reconnect + backfill; no stale render; no cause invented from `AUTH_FAILED` |
| 6 | A notice arrives with `stateVersion` ≤ held | Discarded; no refetch |
| 7 | Notices arrive out of order | Final state is the highest `stateVersion`, not the last received |
| 8 | Mesh unavailable for the whole session | Degraded banner visible throughout; every value still correct, just older |
| 9 | Mesh recovers | Banner clears; `onResync` fires; tails re-backfill from the watermark |
| 10 | A `500` on a query | Retried ≤ 3× with the specified backoff; **a mutation is never retried** |
| 11 | **The process is killed mid-apply and restarted, against the real SSH-Docker adapter** | Task 4a's startup sweep drives the request to a terminal state; the journal is correct; the pre-flight attempt it spends is within budget. ⚠️ This is plan 005's third routed watch item — resume was proven only against an in-memory adapter, so this is the scenario that turns "crash-safe resume" from a claim about a fake into a measurement |

- [ ] **Step 1: Write backend scenarios 1–3 and 11 as failing tests, and hand off scenarios 4–10 as the frontend failing-test worklist.** **Step 2: Run the backend set; all fail.** **Step 3: Implement what the backend set exposes.** **Step 4: Run the backend set green.**
- [ ] **Step 5: Prove the backend tests fire.** For scenarios 1–3, make the publisher rethrow → the apply fails and scenario 1 goes red, proving the test actually covers the swallow. The frontend separately proves the `stateVersion` comparison in scenarios 6–7.
- [ ] **Step 6: Record the frontend handoff and commit the backend test set.** The all-eleven result is an overall EDA/browser acceptance claim, not a backend-plan result.

---

## Task 10 — The mesh ticket path

⛔ **HARD-GATED by open items 32/33/35.** A ticket minted today grants organization-wide notify subscription, which an environment-scoped principal's REST scope forbids. Build it, test it, **do not enable it** until the subscription shape enforces the approved scope model.

**Files:** Create `…/gateway/mesh/TicketController.java`, `TicketMinter.java`, `StreamGrantRegistry.java`; Create `…/gateway/mesh/TicketIT.java`.

**Backend API acceptance only:** these are server-side ticket and grant tests. A browser consuming a ticket or renewing a session is Task 7's frontend handoff and is not evidence required to complete this backend task.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void mintingRequiresMeshTicketMint() { }
@Test void grantingRequiresMeshStreamGrant() { }
@Test void revokingRequiresMeshStreamRevoke() { }
@Test void theTicketCarriesTheFixedClaimSetAndNothingUserSupplied() { }     // §4.1
@Test void subIsGatewayMintedAndContainsNoTopicSeparator() {
    // documented by Solace and explicitly UNENFORCED by the broker — G1 quotes the vendor doc
}
@Test void tenantIsServerDerivedAndNeverReadFromTheRequest() { }           // I5
@Test void expIsAtMostFifteenMinutesFromMint() { }
@Test void theResponseCarriesAServerComputedRenewAfter() { }
@Test void grantsAccumulateOnOneSessionTicketRatherThanOpeningASecondSession() { }  // §4.5
@Test void everyMintRenewAndRevokeWritesAnAuditRecordCarryingJti() { }
@Test void revokingAGrantStopsThePublisherAtItsNextPublishDecision() { }
@Test void aSeriesWithoutALiveGrantIsNeverPublishedToASessionBranch() { }
```

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Implement, with the five things a ticket minter cannot be written without — each recorded as a decision, not chosen in code.** (1) The **signing algorithm and key source** (the broker validates against the gateway's JWKS per `BE-D5`/G1, so an asymmetric algorithm and a published JWKS are implied; state which). (2) **Where the JWKS is exposed** and how the broker reaches it. (3) **Key rotation** — and G1's measured fact that *rotation is not revocation*, so `exp ≤ 15 min` remains the only revocation lever for the ticket itself. (4) The **claim set exactly as `BE-D14` §4.1 fixes it** — `iss`, `aud`, `sub`, `tenant`, `grant_id`, `purpose`, `exp`, `jti` — a floor, not a ceiling. (5) **`sub` generation:** gateway-minted, opaque, per console session, and **must not contain `/`** — documented by Solace and explicitly *unenforced by the broker*, so the gateway enforces it with a test.
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Prove they fire.** Allow a `/` in `sub` → its test red. Extend `exp` past 15 minutes → its test red. Publish an ungranted series → its test red.
- [ ] **Step 6: Commit.** `feat(console-gateway): RBAC-gated, audited mesh tickets and stream grants`

⚠️ **Do not provision the broker ACL from this plan.** Open items 32/33 are the **project owner's** (ruling OR-1 — ⚠️ **not "the group's", as an earlier revision said**), and the install runbook's
ACL section cannot be published until they land (approval §3 item 5). Build against the **single-tenant** shape
G1 measured, and record that as a stated limit.

---

## Task 11 — Server-side walking skeleton and browser handoff

**Files:** Create `backend/demo/compose-skeleton.yml` (a **unique** Compose project name owned by this worktree), `docs/plans/backend/evidence/007-walkthrough.md`; Create `…/dp-core/src/test/java/.../SkeletonWalkthroughIT.java` (gated behind a property, as plan 005's Cache walkthrough is).

**Backend acceptance:** this task proves the authenticated REST, durable-worker and real-adapter path from a server-side walkthrough. **Frontend handoff:** browser navigation, `LiveStateClient`, degraded UI and browser event/backfill observation are owned by the frontend; do not report this task as a browser walkthrough or as overall EDA acceptance.

- [ ] **Step 1: Write the failing walkthrough test** — driven by a **separately provisioned test principal bound to `Operator` at an *environment* scope.** Three constraints on that principal, each for a reason:
  - ⛔ **Not the bootstrap/root principal.** It is bound at the install scope, so it exercises none of
    `SM-D33`'s prefix rule — demonstrating from the root account proves nothing about the model being shown.
  - ✅ **`Operator`, not `Administrator`.** ⚠️ **This step was rewritten 2026-09-16.** An earlier revision said
    *"Plan 006 seeds `Administrator` only, and the production built-in role set is a routed owner decision …
    Inventing `Operator` here would create an unapproved grant bundle"*, and used `Administrator` at a narrow
    scope as a labelled test fixture instead. ⛔ **Both halves of that are now wrong:** ruling
    **[OR-8](../../design/DECISIONS.md)** approves **three** built-in roles and
    [plan 006 Task 4](006-local-identity-and-scoped-rbac.md) seeds all three, so `Operator` is an **approved**
    bundle of 19 verbs and is exactly what a real operator gets. Using it is no longer an invention.
  - ✅ **`Operator` bound at `[org, unit, env]`, and it is a *production* shape, not a fixture.** That is what makes
    the prefix rule demonstrable *and* honest: it reaches that environment's instances and **not** a sibling
    environment's, and it is **least privilege as well as least reach**. ⛔ **`Administrator` must not be used
    here at any scope** (OR-12: bootstrap/emergency only) — narrowing the scope of a role holding all **38** verbs
    is least *reach* only. ⚠️ **State the example correctly:** such a principal could **override a config warning,
    set log levels, export metrics, force the raw tier, stop an instance and read the audit log** inside its
    environment. ⛔ **It could NOT load packages or edit roles** — an earlier revision of this line said it could,
    and that is wrong: `package.load` sits at **organization** (OR-14) and role definitions at **`[]`** (§3.2), so
    an environment-scoped binding reaches neither.
  - ⚠️ **Two consequences the walkthrough must state where it reports its setup**, because they are real limits of
    the role and not defects: an `Operator` **cannot open a live chart tail** (no `mesh.stream.grant`) and
    **cannot stop an instance** (`instance.stop` is Administrator-only) — amendment §1.8.

  ⛔ **Package load is NOT part of the Operator's leg — measured against the ratified lists, it cannot be.**
  `package.load` is in the amendment §1.8's **Administrator-only 19**, and ruling **OR-14** scopes it to
  **organization**, so an environment-scoped binding would not reach it even for an `Administrator` (§3.2's prefix
  rule). ⚠️ **An earlier revision of this step had the Operator principal load the package, and that test could not
  have passed.** So the walkthrough has **two actors**, in order:

  1. **Setup, as the bootstrap admin** (install-scoped, `must_rotate` already cleared): `package.load` for the
     Cache package. ⚠️ **Assert this is the admin's leg**, so nobody re-attributes it to the Operator later.
  2. **The demonstration, as `demo-operator`:** declare one SSH-Docker instance (`instance.create`,
     `instance.bindTarget`, `target.use`), read status/health/logs (`instance.view`, `health.view`, `logs.read`),
     stage a change (`config.edit`), apply with `manual` (`config.apply`, `instance.restart`) — ⚠️ **every one of
     these is in Operator's 19; check each against §1.8 before writing the test.**

  Then assert: `202 + applyId` **returned before any adapter call**, the three rollout notices on their exact topics,
  the config-state notice, the durable worker executing it, the REST observation reaching the right state, and the journal's
  terminal row. ⚠️ **Also assert the negative that the gate forces:** the sibling environment's instance is **not**
  visible to this principal over REST — and the *live-notify* half of the demonstration is **skipped with its
  reason recorded** (open items 32/33/35), because a tenant-wide notify subscription would demonstrate the
  opposite of the scope model.
- [ ] **Step 2: Run; fails.** **Step 3: Make it pass.** **Step 4: Run green, twice**, to prove it is not order-dependent.
- [ ] **Step 5: Write the evidence file** — exact commands, exact observed topics, exact counts, every skip with its reason, and the explicit frontend/browser handoff. Clean up **only** this project's containers.
- [ ] **Step 6: Commit.** `test: the single-placement SSH-Docker walking skeleton, driven as a scoped operator`

---

## Task 12 — One apply path, enforced

**Files:** Create `backend/arch-tests/src/test/java/.../ApplyPathBoundaryTest.java` + three planted-violation fixtures.

- [ ] **Step 1: Write the four rules and their four planted-violation fixtures.** Scope the mutating-`RuntimeAdapter` rule to production packages and separately prove no production package imports `com.solace.sm.demo..`. The retained demo module is a stated staging-only residual, not a production exception; full unscoped runtime isolation is deferred to Plan 009's complete module deletion.

```java
// INVERTED on purpose. A rule scoped to com.solace.sm.gateway.. would have passed today's
// actual violation -- demo-launcher's DemoInstanceService imports RuntimeAdapter and calls
// startOrUpdate/materializeConfig from a controller path -- and would go on permitting any
// future production module to do the same. The staging-only demo module is excluded here only
// because Plan 007 retains it intact; Plan 009 removes it and then runs this rule unscoped.
@ArchTest static final ArchRule onlyProductionCodeTouchesTheMutatingRuntimeAdapter = noClasses()
        .that().resideOutsideOfPackages("com.solace.sm.dp.apply.engine..",
                                        "com.solace.sm.dp.apply.runtime..",
                                        "com.solace.sm.demo..")
        .should().dependOnClassesThat().haveFullyQualifiedName(
                "com.solace.sm.dp.apply.runtime.RuntimeAdapter")
        .because("the only mutating apply path is worker -> ApplyExecutor -> RuntimeAdapter");

// The seam rule alone would still permit a controller reaching a host through a NEW
// transport, so the transports are fenced by name too. SSH and the Docker client are the
// two that exist; each is already confined, and this keeps the production HTTP layer out of both.
// Retained demo monitoring's read-only SshDockerMetrics wiring is the stated Plan 009 residual,
// not evidence that a controller may obtain RuntimeAdapter or perform a mutation.
//
// NOT "...apply.runtime.." -- the read surface (Task 6a) legitimately needs RuntimeStatus and
// RuntimeDiagnostics, which live there. What the HTTP layer must never hold is a TRANSPORT.
@ArchTest static final ArchRule noHttpLayerHoldsATransport = noClasses()
        .that().resideInAnyPackage("com.solace.sm.gateway..", "com.solace.sm.launcher..")
        .should().dependOnClassesThat().resideInAnyPackage(
                "net.schmizz..", "com.github.dockerjava..")
        .because("a 202 is only honest if the request thread cannot open a session to a host");

// Five roots, matching agent-gateway/design.md §2.6's agwHoldsNoDatasource. A single
// javax.sql.DataSource check lets a JPA entity or a Flyway type through.
@ArchTest static final ArchRule theGatewayHoldsNoDatasource = noClasses()
        .that().resideInAPackage("com.solace.sm.gateway..")
        .should().dependOnClassesThat().resideInAnyPackage(
                "javax.sql..", "jakarta.persistence..", "org.springframework.jdbc..",
                "org.springframework.data..", "org.flywaydb..");

@ArchTest static final ArchRule nothingProductionImportsTheDemoModule = noClasses()
        .that().resideOutsideOfPackage("com.solace.sm.demo..")
        .should().dependOnClassesThat().resideInAPackage("com.solace.sm.demo..");
```

- [ ] **Step 2: Run against the fixtures; each rule reports exactly its own violation.** Include a planted production class that obtains `RuntimeAdapter`, and a planted production import of `com.solace.sm.demo..`; both must be red. **Step 3: Exclude fixtures from the production scan.** **Step 4: Green for the production-scoped rules, while recording the retained staging-only module and that full runtime isolation is not claimed until Plan 009 deletes it.**
- [ ] **Step 5: Commit.** `test(arch-tests): one apply path, no remote work in the HTTP layer, no dependency on the demo module`

---

## Task 13 — Backend API parity and staging-only demo fence

⛔ **This task cannot delete `demo-launcher`, and an earlier draft of it claimed otherwise.** The demo launcher
serves **metrics, dashboards and Explore** (`DemoMonitoringController`, `DemoDashboardStore`, `DemoMetricHistory`,
`V004__monitoring_dashboard.sql`), and this plan's own non-goals put that whole surface **out of scope**. ⚠️ **The reason has changed, and the
conclusion has not.** An earlier revision said *"open item 21 … is unresolved, so `/api/console/v1/*` has no
production home."* ✅ **Item 21 is CLOSED** (ruling **OR-10**) and `console-api` **is** that home — see Task 3a.
⛔ **But it is not built here:** `console-api` and the monitoring replacement are
[plan 009](009-console-api-and-monitoring-replacement.md)'s *(⚠️ 2026-10-05: superseded in part by [plan 011](011-monitoring-backend.md), a draft, not approved: it now owns the replacement)*, so within *this* plan the surface still has no
implementation to reach parity against.
Deleting the module would therefore delete a working capability and replace it with nothing — and "prove parity
first" would be gated on a parity that **cannot exist** within this plan.

**The work splits in two; this plan does not partially delete the demo internals.** `DemoMonitoringController` and
`DemoMetricHistory` depend on `DemoSessionService` / `DemoInstanceService`, so deleting auth or instance internals
while retaining monitoring would require an unplanned extraction and re-authorization bridge. Plan 009 owns one
complete backend cleanup after all monitoring parity exists.

| | What | When |
|---|---|---|
| **13a — backend API parity and handoff** | Production auth/session, solutions, instances, designer, config, apply, restart, health, config-ladder and logs are served by authorized production endpoints; their contract and parity evidence go to the frontend | **here**; frontend adoption is a release gate |
| **13b — retain and fence the whole demo module** | `demo-launcher`, all its internals and `demo_control` remain intact but staging-only; production code cannot import it and production composition must not load it | **until [plan 009](009-console-api-and-monitoring-replacement.md) *(⚠️ 2026-10-05: superseded in part by [plan 011](011-monitoring-backend.md), a draft, not approved: it now owns the replacement)* reaches all-backend parity and deletes the entire module in one cleanup step** |

⛔ **Therefore: no claim of complete parity, and `demo_control` is not dropped here.** The schema still holds the
monitoring tables 13b keeps. Dropping it is the monitoring plan's step, not this one's.

**Files:** The backend API parity, contract and staging-only fence are accepted here. Do not delete demo controllers, services, module internals or `demo_control`; no frontend file is changed or accepted here.

- [ ] **Step 1: Prove parity for the *overlapping* capabilities only.** A checklist test asserting that every capability in 13a's row is served by an authorized production endpoint — achievable because **Task 6a** builds the read/config/catalog surface the demo also served. ⛔ **Do not extend the checklist to metrics, dashboards or Explore**: they have no production endpoint by design, and a checklist that included them could only be made green by lying.
- [ ] **Step 2: Fence the complete demo module as staging-only.** Production composition and production packages must not import or load `com.solace.sm.demo..`; retain all demo internals together. The production-scoped Task 12 rules prove this boundary. ⛔ Do not introduce a read-only bridge or re-authorize retained monitoring here.

  - ⛔ **Do not reclassify Cache-demo code as generic production capability.** `SshDockerConfigEditor` hard-codes `cache-engine` / `demo.config.*`, and `SshjDockerTransport` hard-codes the actuator port `8081` and JVM metric assumptions. Replace or remove those demo-specific behaviors only after a production package-driven configuration, health and metric contract proves parity. Retain reusable SSH transport and `RuntimeAdapter` pieces that do not encode those Cache-demo assumptions.

```java
// Production code may not reach the demo module AT ALL -- what survives Task 13 is reachable
// only from the demo composition, so "local/demo-only" is a rule, not a comment.
@ArchTest static final ArchRule nothingProductionImportsTheDemoModule = noClasses()
        .that().resideOutsideOfPackage("com.solace.sm.demo..")
        .should().dependOnClassesThat().resideInAPackage("com.solace.sm.demo..");

// Full demo/runtime isolation is NOT claimed while the intact staging module remains. It is
// proven only when Plan 009 deletes the module and runs the unscoped Task 12 rule.
```

  ⛔ **`demo_control` is NOT dropped.** It remains with the intact staging module. An earlier draft specified a drop
  migration and a rollback story for it; both move to the monitoring plan, which is the plan that will actually
  have somewhere for the data to go. This task retires **no** demo tables or internals: Plan 009 deletes the
  complete module and `demo_control` together after all-backend parity.
- [ ] **Step 3: Hand off the production contract, endpoint inventory and parity evidence to the frontend, and record the release gate.** The frontend owns real-API cutover and MSW/degraded-mode verification. This branch is **not deployable with the current frontend** until it adopts the endpoints.
- [ ] **Step 4: Backend full gate.** `mvn -f backend/pom.xml clean verify` · `./contracts/run-checks.sh --strict` · the DDL replay harness · `git diff --check`. This proves production APIs and the staging-only fence, not full demo/runtime isolation.
- [ ] **Step 5: Record 13b as a dated, scoped retention** — in the plan's Results and in
  [`demo-delivery-sequence.md`](../../tasks/demo-delivery-sequence.md): what is retained, why, that
  **[plan 009](009-console-api-and-monitoring-replacement.md)** *(⚠️ 2026-10-05: superseded in part by [plan 011](011-monitoring-backend.md), a draft, not approved: it now owns the replacement)* owns the replacement, and the fact that it is
  **not** authorized as production. A retention nobody wrote down
  becomes a production dependency by accident.
- [ ] **Step 6: Commit the backend parity and staging-fence result.** The release record carries the outstanding frontend consumer-cutover gate.

⚠️ **Sequenced last on purpose, and intact on purpose.** Plan 007 delivers production API parity while retaining
the entire demo module as a fenced staging-only residual; it makes no partial-retirement claim. Plan 009 deletes
`demo-launcher` and `demo_control` together after all-backend parity. The resulting backend-cleanup branch is
explicitly non-deployable with the current UI until frontend consumer cutover; that release gate does not block
backend cleanup.

⛔ **What the backend/API result may not claim:** frontend cutover, deployability with the current UI, complete
monitoring parity, or a dropped `demo_control`. **[Plan 009](009-console-api-and-monitoring-replacement.md) *(⚠️ 2026-10-05: superseded in part by [plan 011](011-monitoring-backend.md), a draft, not approved: it now owns the replacement)* owns
13b**; it removes the remaining module after backend monitoring parity. Consumer cutover remains the release gate.

---

## Task 14 — Close backend delivery and record EDA/browser handoff

- [ ] **Step 1: Backend full gate** as Task 13 Step 4, plus the server-side Task 11 walkthrough. Do not make a frontend test, browser run or consumer cutover a backend acceptance gate.
- [ ] **Step 2: Results section** — exact backend test counts, every failure and skip with its reason, Docker state before and after, backend resilience scenarios **1–3 and 11** with outcomes, each mutation with whether it fired, and the explicit frontend handoff for scenarios **4–10**, Tasks 7/8 and browser walkthrough evidence. The all-eleven and browser result remains open until the frontend reports it.
- [ ] **Step 3: Update `console-integration.md` §14** from planned to measured. It is the document a reviewer checks the claim against; leaving it stale makes it a liability.
- [ ] **Step 4: Record decisions by allocating the next free id from the merged `DECISIONS.md`** (ruling **OR-11**) — ⛔ never from a reserved number. Any design contradiction is **design-first: record it and stop**.
- [ ] **Step 5: Independent adversarial review.** It must specifically test for: EDA reduced to optional future work; REST treated as the only live mechanism; events treated as authoritative; missing reconnect/backfill; a duplicate demo apply path; a fixture fallback; an endpoint with no permission; and a poll introduced where an event exists.
- [ ] **Step 6: Commit.**

---

## Decisions this plan routes rather than taking

Three questions this plan **must not answer by writing code**, each with a recommendation and the concrete
consequence of leaving it open.

| # | Decision | Recommendation | Consequence while open |
|---|---|---|---|
| R5 | ✅ **RULED 2026-09-16: a new `instance.apply-outcome` notice.** Candidate (b) of [`BE-D4-AMENDMENT-PROPOSAL.md`](../../design/backend/BE-D4-AMENDMENT-PROPOSAL.md) §2.2 | **Placement-scoped**: `entity` = `kind`, `instanceId`, required `placementId`; `payload` = `applyId` + the terminal `state` key from `BE-D13`'s request states; envelope `stateVersion`. ⛔ **No error details** — a classifier can embed a host or container identifier (`I6`); a consumer needing *why* reads the journal over REST | ⚠️ **The shape is ruled; the amendment is not approved.** The schema branch, fixture, manifest entry, topic rule and negative are one reviewed single-writer `contracts/` change that has not happened — so **Task 5 may not implement it**, the apply surface stays in surface-level degraded mode, and this plan **cannot claim the EDA skeleton complete** |
| R6 | ⛔ **STILL GATED — `BE-D5`'s broker ACL shape** (open items 32/33) — ⚠️ **NARROWED 2026-09-16 by OR-5/OR-6, not closed** | ⛔ **Gated on *measured* evidence, not on a preference — and the two sub-questions have changed shape.** ⚠️ **An earlier revision of this row described item 32 as *"multi-tenant composition of the substitution profile"* and the notify-tier exception as *"not expressible in the chosen mechanism"*; OR-5/OR-6 supersede both readings.** **OR-5** gives each Organization its own Message VPN, so *"multi-tenant composition of one profile"* is largely **dissolved** — what remains under 32 is **sub-organization/environment ACL composition inside one VPN**. **OR-6** adds those levels to the taxonomy, so the notify-tier exception is now **expressible but UNMEASURED** rather than inexpressible. ⛔ **What has not changed is the gate:** the [§11.3 matrix](../../design/common/contracts/console-integration.md) is **written and not executed**, and nothing ratifies until it runs against a live broker. This plan may not execute it while the demo environment is active | Tasks 7/8/10 are buildable and testable against a local broker but **not enablable** for any shared or customer install. `BE-D5` cannot ratify, and the install runbook's ACL section cannot be written |
| R8 | ✅ **RULED: no v1 lease** — *contingent on two things this plan must therefore enforce.* Task 4a's claim is the **single atomic `REQUESTED → RUNNING` transition, which is also the semantic start**; `apply_request` has no `claimed_by`/`claimed_at` and `V002`'s `UPDATE` grant is column-scoped to six columns, so a crash mid-execution is recovered on **restart**, not by a lease deadline | **No lease**, and the ruling's two conditions are not optional: **(1) an enforced singleton worker topology** — one process, one bounded pool, asserted at startup and not merely assumed (a second process would let a `RUNNING` row strand until a restart that may not come); and **(2) the atomic start-as-claim design** — because it is what makes a `RUNNING` row unclaimable by a running process, and therefore what makes the absent lease safe rather than merely cheap. Adding the columns later means a migration widening a column-scoped grant whose own comment makes that scoping `I5`-load-bearing | A request whose worker died mid-execution stays `RUNNING` until the next application start — bounded, observable, and recovered by `resume`. ⚠️ **If either condition is broken the ruling lapses**, so Task 4a asserts the singleton topology and Task 3a fails startup if a second worker pool is configured |
| R7 | ⛔ **STILL GATED — below-organization notify isolation** (open item 35) — ⚠️ **the *architecture* is DECIDED; the *enforcement* is UNMEASURED** | ✅ **The candidate question is closed, and an earlier revision of this row left it open.** It read: *"Three candidates worth costing … The first is the one to cost first; **none may be chosen here**."* ⛔ **Ruling OR-6 chose the first** — a canonical topic prefix carrying organization / sub-organization / environment levels — so the per-session-substituted-level and gateway-fan-out candidates are **not live options** and must not be re-drafted (see Acceptance point 5). ⛔ **What remains is measurement only:** whether the broker actually enforces that prefix per subscriber is untested until the §11.3 matrix runs, and `BE-D5` cannot ratify before it does | An environment-scoped principal can subscribe to notify events for environments **its REST scope forbids it to read** — existence and change, not values. The scoped-RBAC demonstration cannot use live notify, and the **EDA acceptance criterion cannot be met** |

---

## Acceptance — what this plan may and may not claim

**Two acceptance results, never one substituted for the other.**

| Result | This plan may claim it when | It must not claim |
|---|---|---|
| **Backend/API cleanup completion** | The server-side tasks, OpenAPI contract, Java mirror, backend resilience scenarios 1–3 and 11, Task 11 server walkthrough, backend gates, frontend handoff, and demo-route/module cleanup after their respective backend parity checks are complete | Frontend code generation, screen wiring, ticket consumption, degraded UI, browser navigation, browser event/backfill evidence, or deployability with the current UI |
| **Release/deployability with the current UI** | The frontend separately adopts the handed-off endpoints and records consumer cutover after the backend cleanup | Backend API parity, handoff, route deletion, or module deletion alone |
| **Overall EDA/browser acceptance** | The frontend separately completes Tasks 7/8, Task 9 scenarios 4–10 and the browser portion of Task 11, then combines that evidence with the backend result and the external enablement gates below | Completion merely because backend APIs or contracts were handed off |

The frontend work is a consumer delivery, not silently completed backend work. It does not block **backend/API cleanup completion**; it is a release gate for deployability with the current UI and for any claim of **overall EDA/browser acceptance** or browser-ready event-driven deployment.

Two gates sit outside this plan and **neither is a non-goal**: a non-goal is something we chose not to build, and
these are things we are **not permitted to declare finished**. Both must close before the **overall event-driven
walking skeleton** is complete; they do not turn frontend implementation into a backend/API acceptance gate.

⚠️ **Owner, once: ruling [OR-1](../../design/DECISIONS.md) makes the project owner the final architecture
authority** — so the Owner column below says *"project owner"* outright. An earlier revision left *"architect
group"* in the cells with this note telling the reader to substitute; the cells are corrected instead.

| Gate | Owner | What it blocks here |
|---|---|---|
| ⛔ **The `BE-D4` terminal-outcome contract** ([proposal](../../design/backend/BE-D4-AMENDMENT-PROPOSAL.md), not approved) | **project owner** (OR-1) | Tasks 4–6 ship; **the apply surface stays in declared degraded mode**. No terminal event may be implemented. The EDA skeleton is **incomplete** |
| ⛔ **Open items 32/33 (`BE-D5` ACL composition) and 35 (below-organization subscription isolation)** | **project owner** (OR-1) | **Tasks 7, 8 and 10 are HARD-BLOCKED for any real deployment.** A mesh ticket minted today grants organization-wide notify subscription, so an environment-scoped principal can observe **which instances exist and when they change** outside its REST scope. That is a confidentiality gap, not untidiness |

**What that means concretely, so nobody reads it as permission to substitute polling:**

1. ⚠️ **The REST half is not wholly executable now — but ⛔ no *decision* and no *measurement* gates it any more.**
   What remains is a **sequencing** dependency on plan 006's code existing, which is a different thing and must not
   be reported as a blocked decision:

   | Former gate | Status as of 2026-09-16 | What it blocks now |
   |---|---|---|
   | `package.load`'s scope | ✅ **RULED: organization** (**OR-14**) | nothing. Row 7's scope is fixed and the OpenAPI can be written |
   | Identity authorization semantics | ✅ **RULED: authentication only** (**OR-15**) | nothing. `GET /cp/auth/identity` and `MustRotateGate`'s third operation are specified |
   | The **password-hash decision** | ✅ **RULED (OR-7):** Argon2id through `DelegatingPasswordEncoder`, `bcprov-jdk18on` **1.86** pinned explicitly | nothing |
   | The **development-host measurement** | ✅ **DONE, 2026-09-16** — [`evidence/006-argon2id-benchmark.md`](evidence/006-argon2id-benchmark.md): the full sweep, the recommended `384 MiB / t=4 / p=1`, and the `{bcrypt}`-default trap | nothing. ⚠️ An earlier revision of this row called this a blocker on *"the password-hash decision and measurement"* — **both closed on 2026-09-16**, and plan 006 Task 3 is unblocked |
   | The **install-time re-measurement** | ⛔ **still required** — ⚠️ **and it gates DEPLOYMENT, not code.** Argon2 cost is hardware-specific and a smaller production host is *slower* at the same parameters | ⛔ **No task in either plan performs it** — it can only run on the target host — so it is an **install prerequisite**, alongside the **declared heap floor**. ⚠️ **The concurrency cap is NOT in that category: it is code**, and plan 006 Task 3 ships it (the benchmark calls it *"part of implementing this parameter set"*). ✅ **The prerequisite does have an owning artifact:** plan 006 Task 3 Step 3c writes `docs/runbooks/install-prerequisites.md` — floor, cap, heap floor and the re-measurement step — so it is not a floating obligation (`L19`). Calling the re-measurement a task blocker would be wrong in both directions: it never stops `CredentialHasher` being written, and it must never be skipped before a real install |
   | **Plan 006 landing** | ⚠️ **sequencing, not a decision** | Plan 006's code does not exist yet, so anything here that resolves a session waits for it to land. ⚠️ **"Decision-free" would be an overclaim, and an earlier revision of this row made it:** plan 006 carries **two open owner questions** — whether to add `solution_id` to the scope tree, and whether **OR-2**'s vocabulary clause overrides `D11-AMENDMENT-PROPOSAL.md` §3.2's *"a placement is not a grantable level"* ruling. ⛔ **Neither blocks a task in plan 006 or here**, and neither is a *measurement* — but they are outstanding decisions and must not be reported as settled |

   So the **exact independently executable subset today** — where *independently executable* means **"no decision
   and no measurement outstanding, AND every predecessor's code already exists"** — is **Tier 1 below**. ⚠️ **An earlier revision of this section listed nine here** and justified five of them with *"they need
   `authorizationService.require` to **exist**"* — which is the definition's own disqualifier, so those rows
   contradicted the sentence above them. Three tiers, kept apart because they need different actions:

   **Tier 1 — independently executable today. Start here; nothing is owed.**

   | Task | Why it needs no predecessor |
   |---|---|
   | **Task 1** — the OpenAPI and check 11 | OR-14 and OR-15 supply the last two undecided authorization cells; a document, not code |
   | **Task 2** — generated TS types, mirror-locked Java models | consumes only Task 1 |
   | **Task 3a** — the composition module | module wiring only; no authorization semantics |
   | **Task 5** — DP event emission at the journal transition points | ⚠️ **Tier 1, not Tier 2** — it lands under `dp/apply/events` and touches **no** authorization, so it does not wait on `require`. An earlier revision of this section put it in Tier 2 and said it waited for `require`, which was wrong |
   | **Task 5a** — the CP emitter | independent of authorization entirely |
   | **Task 4b** — startup recovery (tenant enumeration + `resume`) | ⚠️ **Tier 1, by the same argument that moved Task 5** — it touches no authorization; it needs Task 3a's composition module and plan 005's `resume`. An earlier revision had it in Tier 2 waiting on `require` |
   | **Task 9** scenarios 1–3 | the backend resilience scenarios that exercise the journal and publisher failure without a browser session |

   **Tier 2 — ⚠️ sequencing only: no decision and no measurement is owed, but plan 006's code must land first.**
   Nothing here is *blocked*; each clears the day plan 006 lands, and plan 006 is itself unblocked (OR-7 plus the
   completed development-host measurement).

   | Task | Waits for |
   |---|---|
   | **Tasks 4, 6** — submission, observation | `authorizationService.require` to exist — plan 006 Tasks 1, 2, 4, 5 |
   | **Task 4a** — the durable worker | **Task 4**, in this plan. ⚠️ An in-plan dependency, not a plan-006 one |
   | **Task 9** scenario 11 (kill mid-apply, restart) | **Task 4a**'s startup sweep — so it inherits Task 4a's in-plan dependency. ⚠️ An earlier revision listed it in Tier 1, which put a test of a Tier 2 component in Tier 1 |
   | **Task 3** (session, CSRF, error shape) | plan 006 Task 3's `CredentialHasher` — ⚠️ **and Tasks 5 and 6's `SessionService`, `AuthorizationService` and `MustRotateGate`**, per this task's own Interfaces block. An earlier revision named only the hasher |
   | **Task 6a** — ⚠️ **all of it, reads included** | `require` to exist. ⛔ **An earlier revision put the non-mutating rows in Tier 1 on the grounds that they are "reads only"**, which is wrong: **every** read in §5.2/§5.3 calls `require`, this task's own tests are `everyReadIsAuthorizedByItsMappedVerbAtItsMappedScope` and the sibling-environment negative, and Prerequisite 2 says there is no development bypass. Mutating-vs-reading is orthogonal to whether `require` exists |
   | **Task 6b** — the `stop` endpoint (`OR-18`) | `require` to exist, plus Task 4's submission path. ⚠️ **No decision is owed** — OR-18 assigns it and plan 005's engine already executes stop |
   | **Task 11**, **Task 13** | both drive a real sign-in, so they inherit Task 3's dependency |
   | **Task 12** — the boundary rules | ⚠️ **Task 13**, per its own note — so it is Tier 2 *behind* a Tier 2 task, not executable now. An earlier revision listed it as executable now while listing Task 13 as not executable |

   **Tier 3 — ⛔ what an open decision or unmeasured fact actually withholds.** ⚠️ **Read the two columns
   separately.** An earlier revision of this tier was headed *"genuinely blocked"* and listed Tasks 7, 8 and 10 as
   not executable — which contradicted point 2 below, Task 10's own header (*"Build it, test it, do not enable
   it"*) and R6. **Building is not enabling**, and calling buildable work blocked is the same error as calling
   blocked work sequenced, in the other direction.

   | Task | What is withheld | What is NOT withheld |
   |---|---|---|
   | **Task 10** | ⛔ **ENABLEMENT** for any shared, customer or production install, until open items **32/33/35** close on **measured** broker evidence. The §11.3 spike matrix is **written and not executed** — ⚠️ **and running it as written is not sufficient for `BE-D5`:** its leg 14 (stream tier) still needs the client-username / `sub` provisioning spelled out | ✅ Building and testing the **backend** ticket API against a local broker. Its output may not be turned on, and its tests wait for plan 006's `require` code |
   | **Tasks 7, 8; Task 9 scenarios 4–10; Task 11 browser portion** | ⛔ **Overall EDA/browser acceptance and production browser enablement**, until the frontend implements the handoff and open items 32/33/35 close on measured broker evidence | ✅ The frontend may implement and test locally after handoff; that evidence is reported by the frontend delivery, not this backend plan |
   | **The terminal-outcome half of Tasks 4–6** | ⛔ **implementing the terminal event**, on the **`BE-D4` amendment**; until it lands the apply surface stays in **declared degraded mode** | ✅ the rest of Tasks 4–6 (Tier 1/2 above) |
   | **Overall EDA/browser acceptance** | ⛔ cannot close while any row above is withheld | — |
2. **The backend ticket path (Task 10) may be built and tested against a local broker**, because the mechanism is
   G1-measured. It may **not** be enabled for a shared, customer or production install until 32/33/35 close. Tasks 7/8 and the browser scenarios are frontend-owned handoff work, with the same enablement limit.
3. ⛔ **The scoped-RBAC demonstration must not be performed with a tenant-wide notify subscription.** Doing so would
   demonstrate the opposite of the model being shown. Until item 35 closes, the walkthrough (Task 11) demonstrates
   scope through **REST** authorization, and the live-notify demonstration waits.
4. ⛔ **REST polling is not the architectural substitute for either gate.** Polling is degraded mode (rule 9). A plan
   that closed these gates by polling would have deleted the architecture and kept the acceptance criteria.
5. ⚠️ **The topic shape is DECIDED — do not re-draft it.** Ruling **OR-6** chose the per-environment topic level;
   the canonical prefix is `console-integration.md` §11.2. What may **not** be chosen here is the **ACL/exception
   mechanism that enforces** it (items 32/33) or its **measurement** (item 35). ⛔ **One design question is
   genuinely open**, and candidates may be drafted for it: how an organization-*terminating* fact such as
   `catalog.package` reaches a below-organization subscriber at all — §11.4. And `BE-D5` cannot ratify until the
   **stream-tier** legs run either (§11.3 leg 14).

## Scope and non-goals — stated exactly

| Not here | Why |
|---|---|
| Multi-placement apply | Plan 008. `BE-D23` makes v1 single-placement, and this skeleton is deliberately single-placement |
| ~~Any `stop` endpoint~~ | ⛔ **NO LONGER A NON-GOAL — ruling [OR-18](../../design/DECISIONS.md#owner-architecture-rulings--2026-09-16) assigns it to THIS plan, [Task 6b](#task-6b--the-production-stop-endpoint-ruling-or-18).** ⚠️ **An earlier revision of this row said *"this plan does not build the endpoint … a later plan ships it"*, while plan 008's table pointed here — so no plan owned it.** `instance.stop` is Administrator-only, HIGH, audited and idempotent, and Task 6b builds it against plan 005's measured targeting semantics |
| `restart-all`, `rolling-no-validation` | No strategy-assertion verb; refused with `501` |
| The ten `/cp/admin/groups*` operations | No `group.*` verbs (open item 39c) |
| **The rest of the Admin surface — §5.2 rows 12–22** (`/cp/admin/permission-catalog`, `/admin/roles`, `/admin/principals`, `/admin/scope-tree`, `/admin/assignments` ×3, `/admin/effective-permissions`, `/admin/users` ×2) | The delivery sequence's W3 ships **no principal or role-binding endpoint** — adding two would widen the slice by the whole Admin surface to create one account. The demo operator is provisioned by an **authenticated setup command** that calls the same services under the same `require` checks. Plan 006 supplies the decision logic; an **Admin plan** ships these |
| **The `instance.version` and `metrics.quarantine` events** | Both need producers this slice does not contain: version state needs the observed-version ladder, and quarantine needs the metric-typing path. Neither is a licence to drop them — rule 2·3 binds whichever plan builds those producers. (`catalog.package` **is** emitted, by **Task 5a** in `cp-core`, with **Task 6a**'s `POST /cp/solutions/load` as its caller) |
| Undeclare (`DELETE /instances/{id}`) and package actions | No verb; `console-integration.md` §5.3 rows 37 and 47 |
| Metrics, dashboards, Explore — **the whole of `/api/console/v1/*`, `GET /capabilities` included** (an earlier draft carved capabilities out of this row while no task here built it) | ✅ Open item 21 is **closed** (OR-10 approves `console-api`), so the blocker is gone — but the surface is still **out of this plan's scope** and belongs to **[plan 009](009-console-api-and-monitoring-replacement.md)** *(⚠️ 2026-10-05: superseded in part by [plan 011](011-monitoring-backend.md), a draft, not approved: it now owns the replacement)*, which is also what must land before `demo_control` can be retired |
| A transactional outbox | Not needed while no event is authoritative. **If one becomes authoritative, build the outbox — do not over-claim after-commit delivery** |
| Agent Gateway, agents, `.proto` | Different path (`console-integration.md` §13); `AGW-2` and `AGW-17`–`AGW-21` are open |
| Broker ACL provisioning | Open items 32/33 — ⚠️ **and these are gates, not non-goals**: see [Acceptance](#acceptance--what-this-plan-may-and-may-not-claim) |
| Multi-tenancy | v1 is single-tenant by the `BE-D14` §4.4 **proposal** (unratified). ⚠️ Open item 35 is a **hard gate**, not a bound — see [Acceptance](#acceptance--what-this-plan-may-and-may-not-claim) |
| Microservices, Kafka, a workflow engine | **No requirement asks for any of them.** The mesh, PostgreSQL and one reactor are the architecture |

## Results — Tasks 4 (engine), 4a, 4b

**Status: the engine half of Task 4, Task 4a and Task 4b are executed, and the safety properties
R8's "no v1 lease" ruling rests on are proven** — guard-before-sweep ordering, the bounded claim,
and the claim itself, each with a mutation proof (see "Mutation proofs" below, now covering both
this task's original four and the two safety fixes the external review added). **"Executed" does
not mean every plan-listed test and mutation exists** — a named set did not, until this pass wrote
the missing ones (see the inventory at the end of this section) — **or that every gap is closed** —
finding 4 below is a genuine, unresolved design gap, reported rather than fixed. This line is
corrected rather than repeated: round 1 rated the identical overclaim MAJOR, and a second external
review round (2026-09-28) found the correction below had itself repeated it in five places — see
"External review" further down for the full disposition.
Task 4's **HTTP half is explicitly out of this slice** — no `ApplyController`, no `dp-api`
`ApplyService` interface, no controller test. What was built is the split `execute()` was refactored
into, the durable worker over it, and the startup sweep beneath it, on the module boundaries
`arch-tests` actually enforces (which, measured, are narrower than the plan's own file list — see
"A boundary the plan's file list got wrong" below).

### What was built

**dp-core, `com.solace.sm.dp.apply.engine`:**
- `AdmissionResult` (new) — the sum type Task 4 calls for: `Admitted(ApplyId)`,
  `OperationUnsupported(Operation)`, `StrategyUnsupported(Strategy)`, `ScopeUnsupported(ScopeKey)`,
  `NotAdmitted(RefusalReason, String)`. **Deviation from the plan's literal pseudocode:** `NotAdmitted`
  carries the engine's own `RefusalReason` enum, not a bare `String` — this type is dp-core-internal
  (no dp-api/HTTP consumer exists yet in this slice), so nothing outside the engine depends on its
  shape, and a typed reason is strictly more useful to whatever controller consumes it later.
- `ApplyExecutor.admit(ApplyCommand) → AdmissionResult` — every pre-admission refusal `execute()` used
  to return inline, unchanged in order and meaning, plus the real `AdmissionService.admit` call. No
  adapter call, no `REQUESTED → RUNNING` transition.
- `ApplyExecutor.runAdmitted(TenantId, ApplyId) → ApplyOutcome` — the durable worker's only entry
  point. Reconstructs the `ApplyCommand` from the persisted row (via the new `OutcomeService.requestOf`),
  requires the row already `RUNNING` (throws `IllegalStateException` otherwise — this is the guard
  the "put `start()` back" mutation trips), and dispatches to the untouched `run()`/`runStop()`.
  **Never calls `outcomes.start`.**
- `ApplyExecutor.resumeRunning(TenantId) → List<ApplyOutcome>` (new, Task 4b) — `RUNNING` rows only,
  reusing `resumeOne`/`strand` (no second state machine), isolated per request. A `REQUESTED` row is
  left untouched for the next poll, on purpose.
- `ApplyExecutor.execute(ApplyCommand)` — kept, **test-only**, now `admit()` → `outcomes.start()` →
  `runAdmitted()`. Every pre-existing test in `ManualApplyIT`, `RollingApplyIT`, `StopApplyIT`,
  `ResumeIT`, `StopDuringApplyIT`, `CacheApplyWalkthroughIT`, `ManualApplyDockerWalkthroughIT`,
  `EnsureIntentRaceIT` calls this method unchanged and passed unchanged — that whole suite is this
  refactor's regression proof.
- `OutcomeService.requestOf(TenantId, ApplyId) → ApplyRequestRow` (new) — a transactional read, no
  lock, added because `runAdmitted` needs the whole row, not just the state `stateOf` already exposed.

**dp-core, `com.solace.sm.dp.apply.persistence`:**
- `ApplyClaimRepository.claimNextAsStarted(TenantId) → Optional<ApplyId>` (new) — one transaction:
  `SELECT apply_id ... WHERE state='REQUESTED' ORDER BY requested_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED`,
  then the same guarded `ApplyRequestRepository.transition(REQUESTED→RUNNING)` `OutcomeService.start`
  itself calls — reused, not reimplemented. Writes only `state`/`terminal_at` (both inside V002's
  column-scoped grant). No `claimed_by`/`claimed_at` — the schema has neither, and none were added.
- `TenantEnumerationRepository.tenantsWithNonTerminalWork() → List<TenantId>` (new) — the one
  deliberately unscoped cross-tenant read (`I5`'s stated exception), used by **both** the worker's
  poll and the startup sweep. Reuses `RequestState.blocksScope()` (bound as a parameter list) rather
  than a second hand-typed SQL literal, so it can never drift from `OutcomeService.nonTerminal`'s own
  state set.
- `WorkerSingletonGuard` (new) — the cross-process guard R8's "no v1 lease" ruling rests on: a
  PostgreSQL session-level advisory lock (`pg_try_advisory_lock`, one fixed key, never tenant-derived)
  held on a dedicated `Connection` for the process's life. `ApplyPersistenceConfiguration` builds it
  over the runtime `DataSource` so no other class ever holds that `DataSource` directly.

**dp-core, `com.solace.sm.dp.apply.worker` (new package):**
- `ApplyWorker` — a `SmartLifecycle`, phase `Integer.MIN_VALUE + 2000`. **Corrected by the external
  review (finding 7):** this bullet used to assert, as "measured", that `WebServerStartStopLifecycle`
  uses `SmartLifecycle`'s default phase (0) — false; `javap` against the pinned Boot version
  (3.5.16) shows `Integer.MAX_VALUE - 2048`. What was actually measured is narrower: an early build
  that relied on registration order instead of an explicit phase started and warned *after* "Tomcat
  started" in the log — the ORDERING conclusion was always right; only the stated REASON for it was
  wrong, and both this bullet and the class's own javadoc now say so. Bounded thread pool (`sm.dp.apply.worker.pool-size`, default 2); a single-thread scheduler
  polls every `poll-interval` (default 1s) and, per tenant, drains every claimable row that tick, not
  just the first. `nudge()` exists (an optional immediate extra poll) but **nothing calls it in this
  slice** — Task 4's HTTP submission path is what would.
- `ApplyStartupRecovery` — a `SmartLifecycle`, phase `Integer.MIN_VALUE + 1000` (ahead of the worker).
  Enumerates tenants, calls `resumeRunning` per tenant, isolated per tenant on top of `resumeRunning`'s
  own per-request isolation.
- `ApplyWorkerProperties` (`@ConfigurationProperties("sm.dp.apply.worker")`, `poolSize`/`pollInterval`/
  `shutdownGrace`), `ApplyWorkerStatus`, `ApplyRecoveryStatus` (queryable running/ran + reason, always
  registered), `ApplyWorkerConfiguration` (the `@Configuration` the launcher imports).

**Both `ApplyWorker` and `ApplyStartupRecovery` take `ObjectProvider<ApplyExecutor>`, not a required
bean** — see "The production-wiring gap" below.

**launcher:** one line — `SolutionManagerConfiguration` now also imports `ApplyWorkerConfiguration`.
Nothing else changed; the launcher owns none of the new code (see the boundary finding next).

### A boundary the plan's file list got wrong, measured, not assumed

The plan's own Task 4a file list says `ApplyWorker.java`/`ApplyWorkerProperties.java` live in
`backend/launcher/src/main/java/com/solace/sm/launcher/`. **The first `-pl arch-tests -am verify`
run against exactly that layout failed three rules, each with its own pre-existing planted-violation
proof** (`CompositionBoundaryTest.launcherDoesNotTouchDataAccessOrMigrations`,
`.launcherNeverDependsOnAnApplyExecutor`, `PlaneBoundaryTest.jdbcIsConfinedToPersistencePackages`):
the launcher is a pure composition root that may **never** hold an `ApplyExecutor` or a `DataSource`
directly, only import configurations that already own their own beans — exactly what
`SolutionManagerConfiguration`'s own javadoc already said, and what a code-reading agent should have
caught before writing `ApplyWorker.java` into `com.solace.sm.launcher` on the plan's word alone. This
is the "genuine design gap" case the task brief calls out — except it resolved without inventing new
semantics: `ApplyWorker`/`ApplyStartupRecovery`/`ApplyWorkerProperties`/`ApplyWorkerStatus`/
`ApplyRecoveryStatus`/`ApplyWorkerConfiguration` moved to a new `com.solace.sm.dp.apply.worker`
package in **dp-core**, exactly parallel to `ApplyEngineConfiguration`/`ApplyPersistenceConfiguration`;
`WorkerSingletonGuard`'s raw JDBC moved into `com.solace.sm.dp.apply.persistence`, the one place
`jdbcIsConfinedToPersistencePackages` permits it; and the launcher's own change shrank to the single
`@Import` line above. A second, narrower violation (the `DataSource` PARAMETER TYPE on `ApplyWorker`'s
constructor and `ApplyWorkerConfiguration.applyWorker(...)`, even though neither method body touches
JDBC) was closed by having `ApplyPersistenceConfiguration` build the `WorkerSingletonGuard` bean
itself and hand the **finished guard**, not the `DataSource`, to the worker package. Both fixes are
committed as the current layout, not left as a follow-up.

### The production-wiring gap (stated, not hidden)

**No production `ApplyExecutor` bean exists yet** — no production `RuntimeAdapter`, no production
`RenderedConfigSource`; only `demo-launcher` builds those, and production may not import it (plan
007's own constraint). `ApplyWorker`/`ApplyStartupRecovery` are therefore always registered, in
every context, and both resolve their `ApplyExecutor` via `ObjectProvider.getIfAvailable()` rather
than a required constructor dependency:
- when absent (today's production `SolutionManagerApplication`), `start()` logs a WARN naming
  exactly what is missing and marks `ApplyWorkerStatus.running()=false` /
  `ApplyRecoveryStatus.ran()=false` with a reason string containing `"ApplyExecutor"` — proven by
  `ApplyWorkerAbsenceIT`, which boots the **real** `SolutionManagerApplication` (no test executor)
  and asserts on the WARN text, both status beans, and that the context still boots clean;
- when present (a test configuration supplying one over `InMemoryRuntimeAdapter`), both become fully
  operational with no code change — proven by `ApplyWorkerLifecycleIT` and
  `ApplyWorkerPoisonAndDrainIT`, which boot the same real application class with an `@Import`ed test
  executor.

`@ConditionalOnBean(ApplyExecutor.class)` was considered and rejected: conditional evaluation across
ordinary (non-auto-configuration) `@Configuration` classes is not guaranteed to see a bean a later
class defines, so it would have been a second, less honest kind of gap. `ObjectProvider` defers
resolution to the moment it is actually needed and needed no such guarantee.

### Test counts

**Corrected by the external review, round 2 (finding 1): these numbers are as of the CURRENT state**
(after both review passes), not a stale snapshot from before either — round 2 found the table
below still reporting round 1's pre-fix launcher count.

| Module | Command | Result |
|---|---|---|
| `dp-core` | `-pl dp-core -am verify` | surefire 247 (1 skipped — `broker.host` gate) / failsafe 334 total, 4 skipped, **0 failures** |
| `launcher` | `-pl launcher -am verify` | surefire 6 / failsafe 17, **0 failures** |
| `arch-tests` | `-pl arch-tests -am verify` | 43, **0 failures** (see the boundary finding above for what this caught) |
| Full reactor | (arch-tests pulls every module in) | `cp-api` 43, `mesh-events` 127, `cp-core` 53, `console-gateway` 142 — all unaffected, **0 failures** |

New dp-core test files: `AdmitAndRunAdmittedIT` (9), `ApplyClaimRepositoryIT` (9 — 6 original plus 3
from round 2's operation/scope-shape fix), `TenantEnumerationRepositoryIT` (5),
`ResumeRunningIT` (5), `WorkerSingletonGuardIT` (3), `ApplyWorkerPropertiesTest` (4). New launcher
test files: `ApplyWorkerAbsenceIT` (1), `ApplyWorkerLifecycleIT` (3 — the original 2 plus round 1's
multi-tenant addition), `ApplyWorkerPoisonAndDrainIT` (2, one renamed in round 2 — see "External
review, round 2" below), `ApplyWorkerBoundedClaimIT` (1) and `SecondWorkerPoolRefusedBeforeSweepIT`
(1) — round 1's own two safety-fix tests, omitted from this list until round 2 — plus round 2's
three new files: `WorkerSingletonLockHeldOnUndrainedShutdownIT` (1),
`GracefulShutdownWithinGraceLandsTerminalIT` (1) and `NoNewClaimsAfterStopIT` (1). Plus the shared
`TwoSchemaTestDatabase` provisioning helper (not a test itself).

### Mutation proofs — actually run, not asserted

Each: broken, red observed, reverted, green confirmed.

| # | Mutation | Red |
|---|---|---|
| 1 | Put `outcomes.start()` back into `runAdmitted` (the exact regression this task's own design note warns about) | `AdmitAndRunAdmittedIT` — 3 of 9 tests failed with `IllegalStateException: start() walks REQUESTED → RUNNING; ... is RUNNING`, thrown from inside `runAdmitted` itself |
| 2 | Remove the claim's row lock (`FOR UPDATE SKIP LOCKED`) and its guarded `WHERE state='REQUESTED'`, replacing both with an unconditional `UPDATE` | `ApplyClaimRepositoryIT.eightConcurrentClaimersProduceExactlyOneWinner` — `expected: <1> but was: <8>`, reproduced on 3 consecutive runs |
| 3 | Force `WorkerSingletonGuard.acquireOrRefuseToStart` to treat every lock attempt as won | `WorkerSingletonGuardIT.theSingletonWorkerTopologyIsEnforcedAtStartupNotAssumed` — `Expected IllegalStateException to be thrown, but nothing was thrown` |
| 4 | Drop `resumeRunning`'s `row.state() != RUNNING` filter, so the sweep touches `REQUESTED` rows too | `ResumeRunningIT.resumeRunningLeavesARequestedRowForTheNextPollRatherThanTouchingIt` — `expected: <true> but was: <false>` |
| 5 | **(this pass, re-verified)** Move `ApplyStartupRecovery.start()`'s `guard.acquireOrRefuseToStart()` call to AFTER the tenant enumeration, restoring the ordering the BLOCKER finding named | `SecondWorkerPoolRefusedBeforeSweepIT.aSecondContextIsRefusedBeforeItsSweepTouchesARowTheFirstContextIsStillWorking` fails: context B's sweep reaches `resumeRunning` before the guard refuses it, so the "exactly one attempt" assertion on the crashed row no longer holds |
| 6 | **(this pass, re-verified)** Remove `ApplyWorker`'s `Semaphore` bound — `drainOneTenant` claims without acquiring a permit first | `ApplyWorkerBoundedClaimIT.theWorkerPoolIsBoundedAndRespectsItsConfiguredSize` fails: more than `pool-size` rows reach `RUNNING` simultaneously |

Every mutation was reverted immediately after the red was observed; the full three-module verify
(above) is the green state with none of them present. Rows 5 and 6 are the two safety fixes the
disposition table below (findings 1 and 2) says were "run... but the completion record does not
say so" — this pass re-ran both mutations itself rather than taking that claim on faith, and both
reproduce.

### What was deliberately not done

- **Task 4's HTTP half** — no `ApplyController`, no `dp-api` `ApplyService` port, no `202`/`Location`
  header, no `501` mapping for `restart-all`/`rolling-no-validation`/`scheduled`. Those tests
  (`submitAnswers202WithAnApplyIdAndALocationHeader`, `the202IsReturnedBeforeTheAdapterIsTouchedAtAll`,
  `noControllerEverConstructsOrCallsARuntimeAdapter`, and the three `501` refusal tests) belong to
  that slice and were not written here, per the task assignment's own scope line.
- **`nudge()`'s caller.** The method exists and is unit-testable in isolation, but nothing in this
  slice invokes it — it is wired for, not yet used by, the HTTP submission path.
- **No `claimed_by`/`claimed_at` columns, no migration.** The schema was not touched. Restart-only
  reclaim stays exactly the shape design and R8 describe; `WorkerSingletonGuard` is what makes that
  safe rather than merely cheap, per R8's own conditional.
- **Corrected by the external review (finding 3): this bullet is false, and contradicts the
  disposition table's own finding-4 row below.** The launcher/dp-core package boundary was NOT a
  genuine design gap — that part of the original claim stands, and it was fixed by relocating
  code, not by inventing new semantics, exactly as stated. But finding 4 IS a genuine, unresolved
  design gap this task deliberately did not close: `ApplyWorker.pollOnce` widens
  `TenantEnumerationRepository`'s `I5` exception from a one-shot startup read (the condition it was
  reviewed and ratified under) into a continuous, once-per-poll-tick, steady-state cross-tenant
  read, with no decision authorizing that widening. Per the task brief's own instruction not to
  invent semantics a decision has not made, no code changed for it — both classes' javadocs now
  name the departure and the three owner options explicitly (see finding 4's disposition below),
  and it is recorded here rather than glossed over a second time.

### External review (2026-09-28) — findings and disposition

An independent review of the above found **1 BLOCKER, 3 MAJOR and 8 MINOR** issues. This section is
the honest correction the MAJOR finding on this very Results section demanded: it replaces the
"executed" claim above with what is actually true after the review — the safety properties the
BLOCKER and the claim-bounding MAJOR named are now fixed and proven; one MAJOR is a genuine,
unresolved design gap, reported rather than silently closed; the rest are fixed, already-handled, or
disputed with evidence, each stated below rather than folded silently into "executed."　Full verify
after every fix: `dp-core` surefire 247/1 skipped, failsafe 331/4 skipped; `launcher` surefire 6,
failsafe 14; `arch-tests` 43 — **0 failures across all three.**

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | BLOCKER | The startup sweep ran before `WorkerSingletonGuard` was acquired, so a second process could resume a first process's live work before discovering it must refuse to start | **Fixed.** `ApplyStartupRecovery.start()` now calls `guard.acquireOrRefuseToStart()` before enumerating a single tenant (`ApplyWorker.start()`'s own call is now a same-session no-op, kept as defense in depth). Proven by a NEW test, `SecondWorkerPoolRefusedBeforeSweepIT.aSecondContextIsRefusedBeforeItsSweepTouchesARowTheFirstContextIsStillWorking` — two REAL `SpringApplicationBuilder` contexts against one database, the first blocked mid-`prepare()`, the second's boot asserted to fail on the guard with the crashed row's step-intent count unchanged (never touched by the second context) |
| 2 | MAJOR | Claims were unbounded by the pool: the poller claimed every `REQUESTED` row of every tenant per tick regardless of `poolSize`, handing rows already `RUNNING` to an unbounded in-memory queue | **Fixed.** `ApplyWorker` now holds one `Semaphore(poolSize)`; `drainOneTenant` acquires a permit BEFORE claiming, `submit` releases it on every exit path — a claim and a free worker slot are now the same event. Proven by NEW test `ApplyWorkerBoundedClaimIT.theWorkerPoolIsBoundedAndRespectsItsConfiguredSize` (pool size 1, several requests, asserts at most one adapter call in flight at a time and every unclaimed row stays `REQUESTED`) |
| 3 | MAJOR | This Results section's own "executed" claim was not true: several tests and mutation proofs the plan calls for were never written, and the section did not say so | **Fixed by this section.** See the row-by-row table above and the test/mutation inventory below; what remains genuinely untested is named, not implied complete |
| 4 | MAJOR | `ApplyWorker.pollOnce` widened `TenantEnumerationRepository`'s `I5` exception — reviewed and stated as "startup only" — into a continuous, once-per-second, steady-state cross-tenant read, with no decision authorizing that | **Not resolved — reported, per the task brief's own instruction not to invent semantics a decision hasn't made.** No code changed for this finding. Both classes' javadocs now state the departure explicitly (`TenantEnumerationRepository`'s own doc, and a matching note on `ApplyWorker.pollOnce`) and name the three owner options the review identified (a steady-state enumeration exception, a cross-tenant claim, or another tenant source) as an open item for the architect group. This is the one item in this pass that stayed a flagged gap rather than a fix |
| 5 | MINOR | `WorkerSingletonGuard.release()`'s javadoc claimed the connection was "never returned to a pool," but it is Hikari-pooled, and `release()` never called `pg_advisory_unlock` — the lock could outlive the session that took it | **Fixed.** `release()` now issues `SELECT pg_advisory_unlock(?)` explicitly, before the `close()` that returns the connection to the pool; the javadoc's pooling claim is corrected. Covered by existing `WorkerSingletonGuardIT.releasingTheFirstGuardLetsASecondOneAcquire` |
| 6 | MINOR | `stop()`'s unordered `scheduler.shutdown()`/`pool.shutdown()` could let an in-flight tick's `pool.execute()` throw `RejectedExecutionException`, and any uncaught exception in a poll tick would silently kill every later tick while `ApplyWorkerStatus` kept reporting `running()` | **No change needed — already handled.** `submit()` already catches `RejectedExecutionException` exactly at this race (logs, releases the permit, leaves the row `RUNNING` for the next sweep) and `start()`'s scheduled lambda already wraps `pollOnce()` in a `catch (RuntimeException)` safety net so `scheduleWithFixedDelay`'s "one throw ends every future run" contract can't silently stop polling. No dedicated test isolates this exact race window (both catches are defense-in-depth against a scenario the existing IT suite doesn't deliberately provoke) — a residual, accepted test gap, not a code gap |
| 7 | MINOR | The `PHASE` javadoc claimed, as "measured," that `WebServerStartStopLifecycle` uses `SmartLifecycle`'s default phase 0 — false; `javap` shows `Integer.MAX_VALUE - 2048` | **Fixed.** Both `ApplyWorker` and `ApplyStartupRecovery`'s javadoc now state the actual measured value and the corrected causal story (an earlier build relied on default-phase/registration-order ordering and, measured, warned *after* "Tomcat started") |
| 8 | MINOR | `ApplyClaimRepository` calls `ApplyRequestRepository.transition` directly rather than `OutcomeService.start`, silently falsifying `OutcomeService`'s own class-doc claim that "every state-changing operation on a scope takes that scope's `apply_scope` row lock" | **Fixed (documentation).** `OutcomeService`'s class javadoc now names this one deliberate exception explicitly — reviewed reasoning: `REQUESTED`/`RUNNING` are identical under `RequestState.blocksScope`, no watermark changes on this walk, and by construction at most one non-terminal apply/restart exists per scope, so no concurrent `land()` ever races a `REQUESTED` row. The reviewer found no correctness defect here, only documentation drift; the drift is closed |
| 9 | MINOR | `ApplyExecutor.execute(ApplyCommand)` is public and racy against a live `ApplyWorker` (a worker can claim the row between `admit()` and `outcomes.start()`, throwing `IllegalStateException`), and nothing enforces its "test-only" javadoc | **Disputed — no change.** Confirmed by grep: no production caller anywhere in the reactor invokes `execute(ApplyCommand)`; it is called only from 8 IT classes in a **different** package (`com.solace.sm.dp.apply.persistence`). Restricting visibility would require restructuring all 8 across that package boundary, which is not a "cheap" MINOR fix, and the race already fails LOUD (`IllegalStateException`) rather than corrupting state — the same fail-loud posture the codebase uses elsewhere for exactly this class of ordering hazard. Left as a documented, test-only convenience |
| 10 | MINOR | An exception from `tenants.tenantsWithNonTerminalWork()` inside `ApplyStartupRecovery.start()` was uncaught, contradicting the plan's "the sweep never throws into startup," and the plan's per-request classifier log was missing | **Fixed (code).** `start()` now wraps the enumeration call in `try/catch`, marks `ApplyRecoveryStatus.markSkipped(...)` with a reason naming what failed, logs a WARN, and returns without aborting boot; `resumeRunning`'s per-outcome classifier log (`Landed` vs. other) is now emitted. No dedicated automated test isolates JUST this catch (constructing one needs either enabling Spring bean-definition overriding to swap `TenantEnumerationRepository`, itself `final`, or an invasive DB-permission fault injection) — verified instead by direct code inspection; a residual test gap, noted rather than hidden |
| 11 | MINOR | `TwoSchemaTestDatabase`'s `PostgreSQLContainer` is never explicitly stopped; cleanup is left to Testcontainers' Ryuk reaper | **Disputed — no change.** This is the SAME pattern already used by `ProductionAuthenticationIT`, `ApplyPersistenceConfigurationIT` and `SmDataPostgres` elsewhere in this reactor (all pre-date this task). Ryuk reclaims every one of these containers at JVM/session end regardless of whether a test stops its own; fixing only the newest helper would be an inconsistent, out-of-scope partial fix for a resource-usage question, not a correctness one |

**New test added by this pass** (beyond the review's own required fixes): `ApplyWorkerLifecycleIT.theStartupSweepRecoversEveryTenantNotJustOne` — a SECOND, wholly separate tenant with its own crashed `RUNNING` row, seeded before boot alongside the first tenant's, asserted terminal by the time the test runs. This is the multi-tenant startup-sweep proof finding #3 named as missing (`startupRecoversRecoverableWorkForEveryTenant`, under a different name). **Another mutation proof from that review pass** (not numbered in the table above, to avoid
colliding with this pass's rows 5–6): `TenantEnumerationRepository`'s query was changed to `... LIMIT 1`, restricting the sweep to one tenant — RED (the pre-existing single-tenant assertion in the same class failed with the seeded row still `RUNNING`, plus a cascading `ClassCastException` in the forward-path test from the still-blocked scope); reverted; full three-module verify GREEN again with 0 failures.

### External review, round 2 (2026-09-28) — the correction above was itself incomplete

A second independent review found the section above — written to correct round 1's MAJOR overclaim
— repeated the same pattern in five more places, plus six MINORs. **This subsection is the honest
completion of that correction**; the Status line, the `ApplyWorker` phase bullet, the design-gap
bullet and the test-counts table above are now edited in place rather than left standing beside this
correction, which is what round 1's own correction failed to do.

**What finding #3's original test list still did not have a dedicated automated test, before this
pass** — closed items marked, the rest still named rather than implied fixed:

- `shutdownDrainsInFlightWorkAndStopsClaimingNewWork` — **CLOSED this pass.** Split into its two
  halves, each now its own test: `GracefulShutdownWithinGraceLandsTerminalIT` (work claimed before
  `stop()` that finishes within the grace window lands terminal, not `RUNNING`) and
  `NoNewClaimsAfterStopIT` (a row admitted strictly after `stop()` returns is never claimed, across
  several poll intervals). `ApplyWorkerPoisonAndDrainIT`'s renamed
  `gracefulShutdownBeyondTheGraceDeadlineLeavesAnInFlightRowRunningNotCorrupted` (was
  `gracefulShutdownDrainsAndLeavesAnInFlightRowRunningNotCorrupted` — the old name claimed "drains"
  for a test that asserts the NOT-drained, beyond-grace outcome) covers the third case.
- `theSweepRunsAfterMigrationsAndBeforeTheHttpConnectorAccepts` — still open. Proven today only by
  the `javap`-measured phase ordering plus log evidence, not a runtime HTTP-connector probe.
- `resumeSpendsOnePreflightAttemptAndTheBudgetAccountsForIt` — still open, as a property of
  `resumeRunning` specifically rather than `resume`.
- `aLoserOfTheClaimNeverTouchesTheAdapter` — still open.
- `thereIsExactlyOneRequestedToRunningTransitionPerRequest` (journal-based) — still open.
- `theClaimWritesNoColumnOutsideTheSixTheGrantPermits` (the 42501 mutation) — still open.
- `theWorkerNeverBypassesTheEngine` — still open.
- `aRowCommittedWhileNoWorkerIsAwakeIsStillExecutedWithinThePollInterval` — still open.
- The "then left alone" half of `aTenantWhoseOnlyNonTerminalRowIsApplyIndeterminate...` — still open.
- The three-tenant startup-sweep test — still only two (`ApplyWorkerLifecycleIT.theStartupSweepRecoversEveryTenantNotJustOne`
  seeds `TENANT` and `TENANT_2`, not a third).

**Mutations the plan lists that were never run, before this pass**: moving the sweep after the port
opens; dropping per-request isolation; writing a seventh column. **None run this pass either** —
each needs its own scaffolding (an HTTP connector probe, a multi-request isolation harness, a grant
fixture respectively) large enough that writing it is new test-authoring work, not a documentation
correction, and is out of the scope this round's findings named. Recorded here, not silently
dropped, so the next pass does not have to rediscover it. None of the still-open items above block
the BLOCKER/MAJOR fixes, which are the safety properties R8's "no lease" ruling actually rests on
and are proven by the tests and mutations that DO exist (see "Mutation proofs" above, now six deep).

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | MAJOR | Round-1 finding 3's correction was itself incomplete: the false "executed" Status line, the false phase-0 "measured" bullet, and the self-contradicting "no design gap" bullet were all left standing beside the correction; the test-counts table and file lists undercounted; the closing inventory named 3 gaps while at least 7 tests and 3 mutations were missing; neither of round 1's own two safety-fix mutations (guard ordering, semaphore bound) had a table entry | **Fixed.** The Status line, the `ApplyWorker` phase bullet, the "no design gap" bullet, the test-counts table, and the file lists are now edited in place (see each, above). The closing inventory above is the completed one — 7 named test gaps (1 closed this pass, 6 still open) and 3 named mutation gaps (still open, with a stated reason). Mutation table rows 5–6 (above) cover the two previously-unrecorded safety-fix mutations, RE-VERIFIED by this pass, not merely asserted from the earlier claim |
| 2 | MINOR | `ApplyWorker.stop()` released `WorkerSingletonGuard` unconditionally, including when `awaitTermination` timed out and `shutdownNow()` only interrupted threads that might be inside a non-interruptible adapter call (SSH/Docker I/O) — a second process could then win the lock and double-execute the still-live row | **Fixed.** `stop()` now releases the guard only when the pool actually terminated (directly, or after a bounded second wait once `shutdownNow()` is called); otherwise it is left held and a WARN is logged, on the theory that process exit / Hikari connection discard is what should drop it, not this method pretending an undrained shutdown was clean. New test `WorkerSingletonLockHeldOnUndrainedShutdownIT` (an adapter that ignores interruption entirely, unlike the existing poison/drain test's interruptible `CountDownLatch.await()`) proves a second, independent guard is still refused after `stop()` returns. Mutation: reverted the conditional to unconditional release — RED (`Expected IllegalStateException ... but nothing was thrown`); restored — green |
| 3 | MINOR | `ApplyRequestRepository.BLOCKING_STATES` was a hand-typed SQL literal, independently spelled from `TenantEnumerationRepository`'s derivation off `RequestState.blocksScope()` — the exact drift the class's own javadoc claimed could never happen, and the one behavioural test offered as proof seeded only two of the three blocking states | **Fixed.** `BLOCKING_STATES` is now derived from `RequestState.blocksScope()` at class-init, the same method `TenantEnumerationRepository` filters by — one Java spelling of the predicate, not two. `TenantEnumerationRepositoryIT.everyEnumeratedTenantHasNonEmptyNonTerminalWorkPerTheRequestRepository` now seeds all three blocking states (`REQUESTED`, `RUNNING`, `APPLY_INDETERMINATE`) plus two terminal ones. Mutation: reverted `BLOCKING_STATES` to a literal missing `RUNNING` — RED (`expected: <false> but was: <true>`); restored — green |
| 4 | MINOR | `TenantEnumerationRepository` and `ApplyWorker.pollOnce`'s own javadoc said the `I5` widening was "tracked as an open item for the architect group," but no such item exists in `docs/design/DECISIONS.md` | **Fixed (wording).** Both javadocs now say the departure is recorded in this plan's Results section and must still be RAISED as a `DECISIONS.md` open item before a production `ApplyExecutor` makes the read live — not that it already is one. `docs/design/DECISIONS.md` itself is outside this task's ownership (`backend/dp-core/**`, `backend/dp-api/**`, `backend/launcher/**`, `backend/arch-tests/**` only), so adding the item there is left to the owner this wording now points at |
| 5 | MINOR | `ApplyClaimRepository.claimNextAsStarted` claimed any `REQUESTED` row regardless of operation or scope shape; `ApplyExecutor.resumeRunning` skips `RESTART` and instance-wide `APPLY` on purpose, and a row of either shape would be claimed, throw inside `runAdmitted`, and sit `RUNNING` forever with no recovery path if a future caller ever inserted one directly through the public `AdmissionService.admit` | **Fixed.** The claim's `WHERE` clause now excludes `operation = 'restart'` and instance-wide `apply` rows — a no-op against every row `ApplyExecutor.admit` can write today (it already refuses both before insertion), and a real guard against the caller that does not go through it. Three new tests in `ApplyClaimRepositoryIT`: `aRestartRowIsNeverClaimed`, `anInstanceWideApplyRowIsNeverClaimed`, `anOrdinaryRowIsStillClaimedEvenWhenAnOlderUnclaimableRestartRowExists` (the filter narrows the candidate set, it must not blind the claim to everything else). Mutation: removed the narrowing clause — RED (all three new tests fail); restored — green |
| 6 | MINOR | The only shutdown test (`ApplyWorkerPoisonAndDrainIT`) covered just the beyond-grace half of `shutdownDrainsInFlightWorkAndStopsClaimingNewWork`, was misnamed for asserting the NOT-drained outcome, and neither the "drains within grace" nor the "stops claiming new work" half had its own test | **Fixed.** Renamed to `gracefulShutdownBeyondTheGraceDeadlineLeavesAnInFlightRowRunningNotCorrupted`. Two new dedicated tests: `GracefulShutdownWithinGraceLandsTerminalIT` (work claimed before `stop()`, finishing well within the grace window, lands `STAGED`) and `NoNewClaimsAfterStopIT` (a row admitted after `stop()` returns stays `REQUESTED` across several poll intervals). Mutation for the first: skipped the grace `awaitTermination` wait (`0` instead of the configured grace) — RED (`expected: <STAGED> but was: <FAILED_NO_EFFECT>`); restored — green. Mutation for the second: removed both the `running = false` and `scheduler.shutdown()` signals from `stop()` — RED (`expected: <REQUESTED> but was: <RUNNING>`); restored — green |
| 7 | MINOR | A `WorkerSingletonGuard`-enforced single-process topology cannot complete a Kubernetes rolling update with `replicas: 1` and the default `RollingUpdate` strategy — the new pod can never become Ready while the old pod holds the lock, and the old pod is not torn down until the new one is Ready | **Not code-fixable from this task's ownership, and recorded rather than silently dropped.** This is a deployment-topology consequence of a plan-mandated design (the singleton guard), not a defect in `backend/dp-core`, `backend/dp-api`, `backend/launcher` or `backend/arch-tests` — the fix is an install-runbook line (`strategy: Recreate` or equivalent), and `docs/runbooks/` is outside this task's ownership. Recorded here so the runbook owner has the pointer: a v1 install on Kubernetes with `replicas: 1` needs `strategy: Recreate`, not the default `RollingUpdate`, for exactly this reason |

**Verify after every fix in this round**: `dp-core -pl dp-core -am verify` — surefire 247 (1
skipped), failsafe 334 (4 skipped), **0 failures**; `launcher -pl launcher -am verify` — surefire 6,
failsafe 17, **0 failures**; `arch-tests -pl arch-tests -am verify` — 43, **0 failures**. Full
reactor (arch-tests pulls every module in): `cp-api` 43, `mesh-events` 127, `cp-core` 53,
`console-gateway` 142 — all unaffected.
