# Remaining Backend Integration — Integration Plan 002

> ⛔ **SUPERSEDED 2026-10-08.** Written when the production gateway served 7 of 32 contract operations. It now serves **all 64** operations in `console.yaml` (verified by diffing `@ConsoleOperation` annotations against `operationId`s — see [README](README.md#operation-status)), including monitoring, fleet, schedules, target registration and entitlements, none of which existed when this plan was drafted. Task 1's audit, the D-1…D-5 decision write-ups, and Task 4/5/6/7's shape-drift notes are still accurate background — kept below for reference — but the task breakdown and gating ("Tasks 2–7 are gated" on backend landing) no longer reflects reality. **Superseding plans:** [004](004-home.md) (Home) · [005](005-solutions-and-targets-catalog.md) (Solutions/Targets) · [006](006-target-registration-and-connections.md) (target registration) · [007](007-instances-and-placements.md) (Instances) · [008](008-designer-and-config.md) (Designer) · [009](009-apply-rollout-schedules-fleet.md) (Apply/fleet) · [010](010-governance.md) (Governance) · [011](011-monitoring.md) (Monitoring) · [012](012-hierarchy-writes-and-entitlements.md) (hierarchy writes/entitlements). Do not execute this plan's tasks; go to the plan matching the screen instead.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Status: SUPERSEDED. Tasks 0 and 1 (below) are done and still valid as a historical record (2026-10-01). Everything from Task 2 on is replaced by plans 004–012.**

### Update 2026-10-05 — why the three GETs still return 404

The frontend already reaches the exact contract routes through the shared control-plane client:

- `frontend/src/features/home/homeRepository.ts` → `GET /home/summary` and `GET /home/attention`, which resolve to `/api/cp/home/*`.
- `frontend/src/features/inventory/solutions/solutionsRepository.ts` → `GET /solutions`, which resolves to `/api/cp/solutions`.
- `frontend/src/shared/api/contractConformance.test.ts` recognizes all three calls as contract operations.

The 404 is therefore not a missing frontend URL. The production `console-gateway` currently has no handlers annotated with `getHomeSummary`, `getHomeAttention`, or `listSolutions`; its unconditional no-route handler correctly returns 404. The current `sm_control`/`sm_data` runtime APIs also do not yet provide the catalog/package and instance-health composition required by the normative response shapes.

This update deliberately does not mark the operations served, add empty/stub responses, or invent a frontend mapping. The contract contains response shapes that do not match the fixture-era view models: `SolutionList` is `{items: Solution[]}`, `HomeSummary` is `{solutions: [...]}`, and `HomeAttention` is `{items: [...]}`. In addition, the documented D-1/D-2 decisions remain open for attention-row links, summary derivations, and the unknown staged-config counter. The backend work is owned by backend plans 007/010, especially Task 6a/T10.5; the frontend adapter and browser verification should be completed after those handlers land.

### Task 0 results (2026-10-01)

- **Merge:** `origin/integration/dp-remote-demo` is fully merged at `58e2293`; `git log HEAD..origin/integration/dp-remote-demo` is empty. The "newer commits" seen mid-merge were already in the merge.
- **Served vs contract:** 7 of 32 served; 25 unserved (the 24 listed below plus `getHealth`). The grep must exclude test sources by *path*, not with `-h` (which strips paths) — the first pass miscounted 8 for that reason.
- **`./contracts/run-checks.sh`:** PASSED (3 checks skipped: no `file-mesh-ui` schema dump on this machine).
- **`mvn verify` (backend):** ❌ FAILS in `mesh-events`, as CLAUDE.md predicted — `SchemaMirrorLockTest`, `NotifyEventShapesTest`, `NotifyTopicsTest`, `EnvelopeGoldenTest`, `AfterCommitEventPublisherTest`, `PublisherContractKitSelfTest` (the `tenant` vs `organization` rename left unreconciled in `contracts/events/`). The reactor stops there, so **modules after `mesh-events` were not verified**. Not fixed here: it needs a deliberate reconciliation against `D20`/`FE-D51`/`FE-D53`.

**Goal:** Take the frontend from "auth + hierarchy are real" ([plan 001](001-auth-hierarchy.md)) to "every operation the backend serves is real, and every operation it does not serve yet is wired contract-first so it lights up the moment the backend lands" — without building on the expired demo waivers.

**Decision recorded (2026-10-01, developer):** integrate against the **production gateway (`backend/launcher` → `console-gateway`) only**. `backend/demo-launcher` is not a target.

## What is true at the start (verified 2026-10-01, after merge `58e2293`)

- `contracts/openapi/console.yaml` is the normative contract: **32 operations**.
- `console-gateway` serves **7**: `login`, `logout`, `getIdentity`, `rotateOwnCredential`, `listOrganizations`, `listSubOrganizations`, `listEnvironments`. Plan 001 wired all of them. (`getHealth` appears in the gateway's *test* sources only — a fixture controller, not a served operation.)
- The other **25** have no production controller: `getHomeSummary`, `getHomeAttention`, `listSolutions`, `loadPackage`, `listTargets`, `listInstances`, `declareInstance`, `getInstance`, `getDesigner`, `stageConfig`, `getConfigHistory`, `setConfigSecret`, `acceptRuntimeConfig`, `restoreRuntimeConfig`, `submitApply`, `observeApply`, `rollbackApply`, `stopInstance`, `submitFleetApply`, `observeFleetApply`, `getHealth`, `getConfigLadder`, `readLogs`, `getLogLevel`/`setLogLevel`.
- `origin/integration/dp-remote-demo` has commits newer than the merge (plan 010, a Java Designer projection in `cp-core`). They look like docs/contract/library work; **no new production controller was found**. Re-check after the next merge (Task 0).
- ⚠️ The frontend calls routes **that are not in `console.yaml` at all**: `/cp/admin/*` (permission-catalog, roles, principals, scope-tree, assignments, users, groups), `/cp/connections*`, `/cp/connection-types`. Per the contract these screens have no backend operation to bind to. They stay fixture-backed until a ruling adds operations; this plan does **not** invent endpoints for them.

## Global Constraints

- `I5` org scope is never client-supplied; `I6` secrets never in config/storage/logs; the 38-verb catalog is unchanged by this plan.
- No CORS (gateway is same-origin only). Keep the Vite proxy (`frontend/vite.config.ts`) as the only dev bridge.
- `console.yaml` wins over the frontend's current shapes. Where they disagree, change the frontend adapter, never the contract, and never both at once without a ruling (contract is single-writer).
- The real adapter is selected by `VITE_USE_REAL_AUTH`; do not add a second flag per feature. Unserved operations keep using the fixture/MSW path **under the same flag** until their backend op lands (see Task 1's mechanism).
- Verification includes a **real-browser** run (Playwright, as done for plan 001) — wire-level curl alone missed the StrictMode CSRF bug.

## Review Focus

- A repository must not silently fall back to fixtures when the real server returns 404/501 for a served operation — that hides real defects. Fallback is only for operations on the explicit "not yet served" list.
- Shape drift: `ScopeNodeList` taught us the real wire envelopes differ from fixtures (`items`, `stateVersion`, names-as-ids). Expect the same for `Paged<InstanceRow>`, `HealthSnapshot`, `ApplyOutcome`.
- `stateVersion` is exempt for non-entity responses (OR-38/OR-55); do not require it on `LogPage`, `LogLevel`, `ScopeNodeList`, `TargetList`, home summary top level.
- 428 `MUST_ROTATE` on any non-exempt call must still route to the rotation dialog from every new repository.

---

## Task 0: Sync and baseline

- [ ] **Step 1:** `git fetch origin && git log HEAD..origin/integration/dp-remote-demo --oneline`. Merge only after the developer confirms (CLAUDE.md: the `organization`/`tenant` contract rename is an unresolved conflict — flag, do not silently resolve).
- [ ] **Step 2:** Regenerate the served-operation list: `grep -rhoE '@ConsoleOperation\("[a-zA-Z]+"' backend --include='*.java' | grep -v /test/ | sort -u`, diff against `console.yaml` operationIds. Record the served/unserved table at the top of this plan.
- [ ] **Step 3:** `./contracts/run-checks.sh` and `cd backend && mvn -q verify` (expect `SchemaMirrorLockTest`/`tenant` failures per CLAUDE.md; record exactly which, do not fix here).

## Task 1: Contract-conformance audit (no behaviour change) — ✅ DONE 2026-10-01

**Files:** Create `frontend/src/shared/api/contractConformance.test.ts`; nothing else changed. Report: [`reports/002-task-01-report.md`](reports/002-task-01-report.md).

- [x] **Step 1:** Test scans every `cpClient`/`dpClient` call and checks it against `console.yaml`, with explicit, justified allow-lists (`NO_CONTRACT_OPERATION`, `KNOWN_DRIFT`, `UNCALLED_CONTRACT_OPERATIONS`). It is a ratchet in both directions: a new unlisted call fails, and a listed row that no longer matches reality fails (no stale rows). Mutation-proven (3 planted violations, all caught).
- [x] **Step 2:** Drift found and listed (see report): **3 real drifts** (`POST /dp/solutions/{}/instances` should be `declareInstance` on `/dp/environments/{environmentId}/instances`; `GET /cp/solutions/{}/package` and `POST /dp/instances/{}/actions/{}` have no contract operation), **2 no-contract surfaces** (`/cp/admin/*`, `/cp/connection*`), **4 contract operations never called** (`declareInstance`, `stopInstance`, `submitFleetApply`, `observeFleetApply`), and `/console/v1/*` (Dynamic Metrics) has **no OpenAPI document in the tree at all**.
- [x] **Step 3 — REPLACED, not implemented as drafted.** The draft asked real-mode repositories to consult a `NOT_YET_SERVED` set and use fixtures for those operations. That is a behaviour change (contradicts this task's "no behaviour change") and the draft's own Review Focus forbids silent fixture fallback. Verified today: with `VITE_USE_REAL_AUTH=true` an unserved operation simply returns the real server's 404 and the screen shows its error state ("Couldn't load … Not found"), which is honest. **Decision: no fallback mechanism.** Unserved operations stay visibly unserved in real mode; the status table in [`README.md`](README.md) is the record. Revisit only if the developer wants a demo mode, as a separate plan.
- [x] **Step 4:** Commit (developer-approved 2026-10-01).

## Task 2: Home (`getHomeSummary`, `getHomeAttention`)

Contract-first against `console.yaml` (Task 1 found no route drift). The repository route calls are present, but the response adapter is not complete because the production handlers are absent and D-1/D-2 still govern fields the current UI expects. Once the backend lands: failing test → wire-shape adapter → component tests → browser check. Preserve per-row `stateVersion` and no top-level `stateVersion` (OR-55). Do not add fixture fallback in real mode.

## Task 3: Solutions + Targets (`listSolutions`, `loadPackage`, `listTargets`)

The `listSolutions` route call is present, but the repository still exposes the fixture-era card model because the production response is not served. Align it to `SolutionList` (`{items}`) only after the backend response is available and the card fields are resolved against the contract; do not synthesize `kind`, `icon`, `authoring`, entitlement, or telemetry fields. `TargetList` remains the same envelope rule (OR-38). `loadPackage` must go through the pending/confirm/discard dialog contract only if the contract defines it; otherwise flag (the demo's `/solutions/load/confirm|discard` are **not** in `console.yaml`).

## Task 4: Instances (`listInstances`, `declareInstance`, `getInstance`)

⚠️ **Task 1 finding, corrected after inspecting the contract body (2026-10-01):** this is **not** a one-line route fix. The frontend declares at `POST /dp/solutions/{}/instances`; the contract (OR-52) is `POST /dp/environments/{environmentId}/instances` with a **different body and response**:

| | Contract `declareInstance` | Frontend today (`DeclareInstanceInput` / `InstanceRow`) |
|---|---|---|
| Address | `environmentId` **UUID** in the path | solution id in the path; env/org/subOrg **names** in the body |
| Required body | `solutionId` (catalog id), `name`, **`targetId`**, `declaredPackageVersion`, **`placement{containerName, configDirectory, containerConfigPath, fileName, healthPort, healthAcquisition}`** (`additionalProperties: false`) | `name`, `packageVersion`, `env`, `org`, `subOrg`, `driver`, `locus`, `replicas`, `componentTypes?`, `declaredVersion?`, `connections` |
| Response | `201 Instance` (`instanceId`, `environmentId`, `placements[]`, per-row `stateVersion`) | `InstanceRow` (`id`, `replicas`, `healthRollup`, `version{}`, `configState`, …) |
| Errors | `409` `CONFLICT` (placement collision, OR-29) / `ENTITLEMENT_REFUSED` (OR-40); `422` | generic |

So Task 4 needs: a **target picker** (`listTargets`) and **placement fields** in the declare form (new UX, not in the reference design — flag to the designer), an env-name→id mapping (the hierarchy repository already caches ids), a wire→`InstanceRow` adapter, and handling of the two 409 codes. It cannot be browser-verified until `listTargets` **and** `declareInstance` are served. Swapping the URL alone would turn today's honest 404 into a 400 on a body the server rejects, so it was **not** done.

`GET /dp/solutions/{solutionId}/instances` (paged), `POST /dp/environments/{environmentId}/instances` (declare is environment-addressed, OR-52), `GET /dp/instances/{id}`. This is what unblocks the declare dialog and the hierarchy cascade UI path that plan 001's browser run could not reach.

## Task 5: Designer + config (`getDesigner`, `stageConfig`, `getConfigHistory`, `setConfigSecret`, `acceptRuntimeConfig`, `restoreRuntimeConfig`)

Server re-projects the stage (OR-34) and refuses a mismatch — the frontend must surface that refusal, not assume its own `project()` is authoritative. Secrets are references only (`I6`).

## Task 6: Apply + rollback + stop + fleet (`submitApply`, `observeApply`, `rollbackApply`, `stopInstance`, `submitFleetApply`, `observeFleetApply`)

`placementId` required on apply (OR-53). `ApplyObservation.state` is the engine's 12-value `RequestState` verbatim (OR-36). Fleet refuses whole by default (OR-57). Includes `APPLY_INDETERMINATE` handling (OR-58/59).

## Task 7: Governance (`getHealth`, `getConfigLadder`, `readLogs`, `getLogLevel`, `setLogLevel`)

Five-rung ladder everywhere (OR-37). Health uses the event vocabulary incl. the `unreachable` sub-state (OR-54).

## Task 8: Real-browser acceptance

Re-run the plan-132 Playwright journey extended through: login → rotate → hierarchy → solutions → declare instance → designer stage → apply → health → logout → session expiry. Record results in `docs/plans/integration/reports/002-task-NN-report.md`.

## Decisions needed before Tasks 2–5 can finish (found 2026-10-01)

Tasks 1, 2 and 4 each ran into a place where the **contract and the frontend design disagree**. None is a wiring problem, and none can be settled by the implementer: a contract change is single-writer and an ordinary implementation approval is not an amendment (CLAUDE.md). Each row says who decides, what the *status quo* is, what each option changes, and what does **not** change.

**What does not change under any option:** the 7 served operations and their browser-verified behaviour; the 38-verb catalog; I5/I6; `console.yaml` stays the normative contract; no fixture fallback in real mode (Task 1 decision).

### D-1 · Home attention feed has no instance name and no `solutionId` — owner (contract) + designer
`getHomeAttention` items are `{instanceId, placementId?, reason, state?, stateVersion}`. The feed row needs a **name** ("cache-stage-01 · replica not responding") and a **link** (`/solutions/:solutionId/instances/:instanceId/…` — there is no instance-only route).

| Option | Contract change | Frontend delta | Real-mode result |
|---|---|---|---|
| **A. Extend the items** (add `instanceName`, `solutionId`) | Yes — `console.yaml` + `ENDPOINT-INVENTORY.md`, then the backend | Adapter only; keeps the reference design | Full feed, same as the reference |
| **B. Frontend resolves** | None | N+1 `getInstance` per item (or cache from `listInstances`), plus generated text from `reason`/`state` | Works and needs no contract change (`getInstance` returns `name` and `solutionId`), but costs one extra request per item |
| **C. Degrade** | None | Title from `reason`/`state` + short id, link to the solutions list | Conformant but visibly poorer than the reference (violates `FE-D30` visuals) |

**Recommendation: A** — two additive fields, no new call, and the backend has both values in hand when it builds the item. B is the fallback if the contract can't move.

### D-2 · Home summary: one counter has no contract source, one needs a derivation ruling — owner
`getHomeSummary` is `{solutions[{solutionId, name, instanceCount, byHealth, stateVersion}]}`. Derivable: `solutionsManaged` = `solutions.length`; `instancesDeclared` = Σ `instanceCount`; `needAttentionNow` = Σ `byHealth` over the `err`-tone states (`RED`, `unreachable`, per `STATE-VOCABULARY.md` §2 — the derivation itself needs the owner's nod, it is recorded as Q-C1-rollup). **`configStagedNotApplied` has no source** → render `null` ("—", the existing "unknown is never 0" rule, G10). **Decision:** accept the `null`, or add a count to the contract.

### D-3 · Declare-instance form needs a target and a placement — designer + owner
The contract requires `targetId` and a six-field `placement` that the reference-design dialog has never shown (full table under Task 4). **Options:** (A) add a target picker (`listTargets`) + a "Placement" step to the dialog — new UX, designer to draw it; (B) have the server default the placement from a per-target profile (contract change, `OR-52`'s "each target fences its paths" suggests the server already knows the allowed root) — fewer fields for the operator, but it moves a decision into the backend. **Not blocking:** the 4 declared-input fields the dialog has today (`driver`, `locus`, `replicas`, `componentTypes`) are not in the contract at all; D-3 must also rule where they go (package-derived, or dropped).

### D-4 · Surfaces with no contract operation — owner
`/cp/admin/*` (RBAC screens), `/cp/connections*` + `/cp/connection-types`, `GET /cp/solutions/{}/package`, `POST /dp/instances/{}/actions/{}`, and the whole `/console/v1/*` Dynamic Metrics API (no OpenAPI document exists; only the demo launcher serves it). Each needs either a contract (then integrate) or a ruling that the screen is out of the first release. Until then they stay fixture-backed and are listed in the audit's allow-list.

### D-5 · Contract operations nothing calls — product
`stopInstance` (OR-9/OR-18), `submitFleetApply` / `observeFleetApply` (OR-47, fleet apply is v1 scope per the owner) have **no frontend UI**. Fleet apply "needs its own design before the apply slice" (OR-47). **Decision:** schedule the UI (designer first), or defer. This is the only item that is genuinely missing frontend work rather than a disagreement.

### Unblocked right now
Nothing in Tasks 2–7 can be completed before D-1…D-3 are decided **and** the backend serves the operation. What *is* unblocked: the contract-conformance test (done), and Task 6/7 shape work for `getHealth`/ladder/logs once a decision on their (unaudited) shapes is made — re-run the Task 1 audit as each backend operation lands.

## Out of scope (explicit)

- Admin / connections / connection-types screens (no contract operations).
- Any backend controller work — this plan consumes what the backend serves; backend plans 007/009/010 own building it.
- Reconciling `contracts/events/` `tenant` vs `organization`, and root `DECISIONS.md`'s ratification matrix (CLAUDE.md open items).
- Plan 001 follow-ups: false "session ended" banner for first-time visitors; bootstrap-credential runbook step.

## Tasks 2–7 are gated

Each of Tasks 2–7 is executed only for operations that exist in `console-gateway` at the time (Task 0's table). Operations not yet served are completed contract-first (fixtures conform to `console.yaml`) and re-verified in the browser when the backend op lands.
