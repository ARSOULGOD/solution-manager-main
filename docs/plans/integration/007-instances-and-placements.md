# Instances & placements — Integration Plan 007

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire the Instances screen (list, detail header, declare dialog) and placement add/retire to the production gateway, resolving plan 002's D-3.

## ✅ Item 1 resolved 2026-10-09 (commit `ea69066f`)

Task 1 (declareInstance drift) and the detail-header half of Task 2 are done: the Instances
feature — repository, declare dialog/form, list grid, detail header/shell, routes — was rewritten
against the real `declareInstance`/`listInstances`/`getInstance` shapes from `console.yaml`. See the
commit message for the full file list. `hierarchyRepository.ts` gained `environmentId`/
`environmentPath` so a declared instance's environment resolves to real org/sub-org/env names.

**Verified:** full `vitest run` — 358/359 files, 2570/2608 tests passing (2 expected-fail). The one
failing file, `src/app/dev/devPackageFlow.test.ts` (33 failures), is the dev-sandbox package-flow
suite, which still declares against the pre-007 fixture shape (`name/packageVersion/env/org/subOrg/
driver/locus/replicas`) and 422s against the new `declareInstance` validation — **known and
deliberately deferred**, not fixed in this pass (it needs its own migration to the new
`DeclareInstanceInput` shape, same as `devDeclaredInstances.test.ts` got here). Playwright was not
run in this pass.

**Still open, carried forward:** the list grid's 7 orphaned columns (`env`/`org`/`subOrg`, `driver`,
`locus`, health rollup, version/config state, `lastSeen`) remain a design decision — see "Needs one
consolidated design session" in the plan README. `addPlacement`/`retirePlacement` (Task 3) are
untouched.

**Supersedes:** plan 002 Task 4 and D-3.

## Operations

| Operation | Route | Served | Frontend repository |
|---|---|---|---|
| `listInstances` | `GET /dp/solutions/{solutionId}/instances` | ✅ | `frontend/src/features/inventory/instances/instancesRepository.ts` |
| `getInstance` | `GET /dp/instances/{instanceId}` | ✅ | same |
| `declareInstance` | `POST /dp/environments/{environmentId}/instances` | ✅ | same (currently drifted — see below) |
| `addPlacement` | `POST /dp/instances/{instanceId}/placements` | ✅ | not yet called anywhere |
| `retirePlacement` | `DELETE /dp/instances/{instanceId}/placements` (confirm exact verb/path in `console.yaml` — grouped under the same route) | ✅ | not yet called anywhere |

## Task 1: Fix the `declareInstance` drift

Plan 002's Task 1 audit found a real drift, unchanged by the backend now serving everything: the frontend declares at `POST /dp/solutions/{solutionId}/instances` with a fixture-era body (`name, packageVersion, env, org, subOrg, driver, locus, replicas, componentTypes?, declaredVersion?, connections`); the contract is `POST /dp/environments/{environmentId}/instances` with:

```
path: environmentId (UUID)
body: { solutionId, name, targetId, declaredPackageVersion,
        placement: { containerName, configDirectory, containerConfigPath, fileName,
                     healthPort, acquisition: { mode: direct-http | via-ssh } },
        metricsPorts?: {...} }   // acquisition carries no host — OR-82
```

This is not a URL fix — it's a different body and response (`201 Instance` with `instanceId`, `environmentId`, `placements[]`, per-row `stateVersion`; `409 CONFLICT`/`ENTITLEMENT_REFUSED`; `422`).

- [ ] **Step 1:** Confirm current `declareInstance` schema against `console.yaml` directly (it has moved since plan 002 — `placement.healthAcquisition` became `acquisition`, `metricsPorts` was added). Don't rely on this plan's quoted shape without checking.
- [ ] **Step 2 (D-3 — needs designer input, same as plan 006 Task 1):** The dialog has never shown a target picker or placement fields. Design the addition: target picker (consumes plan 005's `listTargets`) + placement fields (`containerName`, `configDirectory`, `containerConfigPath`, `fileName`, `healthPort`, `acquisition.mode`). The 4 fields the dialog has today (`driver`, `locus`, `replicas`, `componentTypes`) are **not** in the contract — rule where they go (package-derived, or dropped) rather than silently keeping them.
- [ ] **Step 3:** Env-name→id mapping: the hierarchy repository (plan 001) already caches ids — reuse it, don't add a second lookup.
- [ ] **Step 4:** Failing test → wire→`InstanceRow` adapter → implement → handle `409 CONFLICT` (placement collision, OR-29) and `409 ENTITLEMENT_REFUSED` (OR-40) as distinct UI states, not one generic error.

## Task 2: List + detail

⚠️ **Found 2026-10-08 (this plan's execution): Task 2 is blocked by the same root cause as D-3, reached from the list side, and it's worse than the instance-detail-header case alone.** The real `Instance` schema (`instanceId`, `name`, `solutionId`, `environmentId`, `declaredPackageVersion`, `acquisition.mode`, `placements[{placementId, targetId, containerName, probeStrength}]`, `stateVersion`) has almost no field overlap with the fixture `InstanceRow` (`frontend/src/shared/testdoubles/data/instances.ts`) the list grid currently renders — that type's own header admits every field is "INVENTED: no endpoint anywhere." 7 of the grid's 9 columns (`env`, `org`/`subOrg`, `driver`, `locus`, health rollup, `version.{state,observed,declared}`, `configState`, `lastSeen`) have no real source: health belongs to Governance's placement-scoped `getHealth` (plan 010), config/version state to Designer/config-ladder territory (plan 008/010), and `driver`/`locus` don't appear in the contract at all. `ReplicaDetailPanel.tsx`, `UndeclareInstancesDialog.tsx` and `BulkRestartDialog.tsx` all also consume `InstanceRow[]`.

- [ ] The instance **detail header** (id/name/solutionId/environmentId only) is buildable in isolation and unblocked — do this part.
- [ ] The **list grid**'s 7 orphaned columns need one of: drop them (visible UX regression), N+1-fetch Governance's health/config per row (perf/architecture call), or an explicit fixture/real split visible to the user. All three are UX/design calls — **fold this into the same design session that resolves D-3** (the declare dialog's own invented `driver`/`locus`/`componentTypes` fields), since both need one simultaneous field-set decision for the whole Instances feature, not two separate ones that might disagree.
- [ ] `listInstances`/`getInstance` adapter against the real `Paged<Instance>` / `Instance` envelopes — scope to the detail-header fields only until the grid-column decision lands.

## Task 3: Placement add/retire

- [ ] Confirm whether the UI has any entry point for `addPlacement`/`retirePlacement` today (likely none — check before assuming this is a fixture swap vs. new UI, same caveat as plan 006).
- [ ] If new UI is needed, design it before building (bounded brainstorming pass, scoped narrowly).

## Task 4: Component tests + browser verification

- [ ] Playwright: Solutions → Instances list → declare instance (target + placement) → instance detail header renders on all three tabs.
- [ ] Record in `reports/007-task-04-report.md`.

## Decisions carried forward

- D-3 (declare-instance target/placement UX) — resolved in this plan's Task 1 Step 2, or escalated if the designer disagrees with the proposed approach.

## Out of scope

- Designer tab content (plan 008), Governance tab content (plan 010), Apply (plan 009) — this plan only gets the instance identified and listed; the tabs' own operations are separate plans.

## Global constraints

Same as plan 004 — see that plan's closing section. Additionally: `I5` applies hard here — `environmentId`/`targetId` are never client-asserted as authority, only as the id the user picked from a server-sourced list.
