# Complete backend API inventory (integration view)

*Synced 2026-10-08 from `contracts/openapi/console.yaml`, `contracts/openapi/ENDPOINT-INVENTORY.md`, and the `@ConsoleOperation` annotations in `backend/` (production). **Regenerate, don't hand-edit, the served list** with the command in [README](README.md#operation-status).*

⚠️ **Buckets A/B below are superseded.** This file was written when 7 of 32 contract operations were served. As of 2026-10-08 the contract has **64** operations (`demo-launcher` is deleted per `OR-112`(4), `P11-9.1`) and **all 64 are served**. Buckets C–F (uncontracted, 501, and no-contract-surfaces) are still accurate and still block the areas they name.

Integration target is the **production gateway only** (`backend/launcher` → `console-gateway`).

## Summary

| Bucket | Count | Frontend can bind to it? |
|---|---|---|
| A. Contracted and **served** | 64 (all of them) | per-operation shape/verification status lives in plans [004](004-home.md)–[012](012-hierarchy-writes-and-entitlements.md), not here |
| C. Planned, **not contracted** | ~21 (admin 11, connections, package fetch) | ❌ no — needs a contract first |
| D. **501** by design (no verb / unbuilt) | see below — narrower now that strategies/schedules/targets/fleet/entitlements moved to A | ❌ must render as "not available", never as 403 |
| E. **Demo-launcher** | deleted (`OR-112`(4), `P11-9.1`) | n/a — folder removed |
| F. Frontend calls with **no contract** | 5 surfaces | fixture-backed until a ruling |

## A. The 64 contracted operations

Full per-operation table (route, shape-verification status, browser-verified): split across plans [004](004-home.md)–[012](012-hierarchy-writes-and-entitlements.md), one table per screen. `contracts/openapi/ENDPOINT-INVENTORY.md` §1/§1a is the backend's own authoritative list if you need the full 64 in one place.

## C. Planned, not contracted

Verbs exist in the ratified catalog; no plan builds them, so they are absent from `console.yaml`.

| Group | Operations | Frontend consumer | Owner of next step |
|---|---|---|---|
| Admin (11) | `GET /cp/admin/permission-catalog`, `roles`, `principals`, `scope-tree`, `assignments`, `effective-permissions`, `users`; `POST /cp/admin/assignments`; `DELETE /cp/admin/assignments/{id}`; `POST /cp/admin/users`; `PATCH /cp/admin/users/{id}` | `frontend/src/features/admin` | owner: contract the 11 ops, then a backend plan |
| `POST /cp/admin/roles` | role create | admin | ⚠️ blocked by data model (`sm_control.role` is SELECT-only; v1 = built-in roles). Needs a decision |
| Connections | `/cp/connections*`, `/cp/connection-types` (type schemas live in `contracts/connection-types/`) | `features/connections` | plan 013 (`BE-D66`/`OR-130`) |
| Package fetch | `GET /cp/solutions/{id}/package` | designer | owner: contract or derive from `getDesigner` (D-4) |
| Dynamic Metrics | `/console/v1/*` — see section G | `features/monitoring` | plan 009 builds `console-api`; needs an OpenAPI document |

## D. Answers `501` (not `403`)

From `ENDPOINT-INVENTORY.md` §3 — the console must show "not available", not a permission error.

| Operation | Reason |
|---|---|
| `DELETE /dp/instances/{id}` (undeclare) | no verb (open item 39 d) |
| `POST /dp/instances/{id}/actions/{actionKey}` | package actions have no verb (e) — frontend currently calls it |
| Ten `/cp/admin/groups*` ops | no `group.*` verb approved (c) |
| `setLogLevel` with `mode: live` | build gap (`T9.4`); ⚠️ **corrected 2026-10-08** — this row previously said `mode: config`, which plan 010's execution verified backwards against `LogLevelOrchestrator.java`: `config` is the built 202 path, `live` is the one refused |
| `instance.version.redeclare`, archive/restore | resource not identified (i) |
| Agent Gateway operations | human actions not enumerated (h) |

⚠️ **No longer 501** (superseded row, kept so the history is traceable): `restart-all`/`rolling-no-validation`/`scheduled` strategies on `submitApply`/`rollbackApply`/`restoreRuntimeConfig`/`submitFleetApply`. Per `ENDPOINT-INVENTORY.md`'s 2026-10-08 update (plan 012 T7, `OPS-Q9` Sch-2), `scheduled` is now built as a window (`submitApply`/`rollbackApply` take an optional `schedule: {notBefore, notAfter}`, answering `202` with a `scheduleId`, no `applyId`); `restoreRuntimeConfig` and `submitFleetApply` still refuse `scheduled` with a `422` naming the field, which is a validation refusal, not a 501. **No operation answers `501` for a strategy any longer.** See plan [009](009-apply-rollout-schedules-fleet.md).

## E. Demo-launcher — deleted

`backend/demo-launcher` and `demo_control` were deleted (`P11-9.1`, `OR-112`(4)). No production endpoint ever fell back to it. If you find a reference to `/api/cp/setup-status`, `/api/cp/packages`, `/api/cp/ssh-keys`, or any other demo-only route in older docs or code, it describes a module that no longer exists — do not integrate against it.

## F. Frontend calls with no contract operation

Tracked in `frontend/src/shared/api/contractConformance.test.ts` allow-lists (`NO_CONTRACT_OPERATION`, `KNOWN_DRIFT`, `UNCALLED_CONTRACT_OPERATIONS`).

1. `/cp/admin/*` → bucket C
2. `/cp/connections*`, `/cp/connection-types` → bucket C
3. `GET /cp/solutions/{}/package` → bucket C
4. `POST /dp/instances/{}/actions/{}` → bucket D (501)
5. `POST /dp/solutions/{}/instances` → **drift**; must become `declareInstance` (Task 4, needs target picker + placement fields)

Contract operations the frontend **never calls**: `declareInstance`, `stopInstance`, `submitFleetApply`, `observeFleetApply` (plan 002 D-5).

## G. Dynamic Metrics (`/console/v1/*`)

⚠️ **Superseded.** `console.yaml` now contains the full monitoring surface (`getCapabilities`, `listEntityTypes`, `listMetricInstances`, `getMetricsCatalog`, `declareMetricType`, `getLabelValues`, `queryRange`, `queryInstant`, `exportRange`, `getDashboard`, `saveWidget`, `setDashboardLayout`, `setWidgetHidden`, `resetDashboard`), served by the production `console-api` under `/api/console/v1` (backend plan 011, `OR-...`). `exportRange` is wired and tested but not counted "served" until P11-8.1's 105M-row measurement lands (`ENDPOINT-INVENTORY.md`). Integration: plan [011](011-monitoring.md).

## What is needed to reach "all APIs integrated"

| # | Needed | From | Status |
|---|---|---|---|
| 1 | Backend serves every contracted op | backend | ✅ done — all 64 served |
| 2 | Rulings D-1…D-5 | owner / designer | per-plan — see [README](README.md#decisions-waiting-on-the-owner--designer) |
| 3 | Contract for admin (11), connections, package fetch | owner | still open — no plan schedules it |
| 4 | Verbs for groups, undeclare, actions (else they stay 501 and the UI hides them) | architect group | still open |
| 5 | `mesh-events` `tenant`/`organization` reconciliation so `mvn verify` passes beyond that module | owner | still open |
| 6 | Shape-align each repository to the real wire contract and browser-verify | frontend | plans [004](004-home.md)–[012](012-hierarchy-writes-and-entitlements.md) |
