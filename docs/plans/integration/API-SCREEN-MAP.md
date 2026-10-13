# API → parameters → feature → screen map

*Generated 2026-10-03 from `contracts/openapi/console.yaml` (parameters, request bodies), the `@ConsoleOperation` annotations and controllers under `backend/`, and the frontend routes/repositories under `frontend/src`. Companion to [`API-INVENTORY.md`](API-INVENTORY.md) (which says what is served/contracted/planned) and the README status table.*

⚠️ **Superseded 2026-10-08: the `Served` column below is stale.** All **64** `console.yaml` operations are now served by the production gateway (`demo-launcher` is deleted) — every ❌/🧪 row in §1 below is now ✅. The parameters/body/feature/screen columns are still accurate and are the reason this file is kept rather than deleted. Per-operation **integration** (shape alignment, decisions, browser verification) status now lives in plans [004](004-home.md)–[012](012-hierarchy-writes-and-entitlements.md), not in a `Served` column here. §1's "32" operation count is also stale — see §1a's new rows and the 32 added by backend plans 012/013 (target registration, fleet schedules, hierarchy writes, entitlements) for the other 32.

Legend — **Served**: ✅ production gateway · ❌ contract only · 🧪 demo-launcher only · ⛔ 501. Every `POST`/`DELETE` also needs header `X-SM-CSRF` (required). Path params are the contract's names; request bodies list top-level fields.

**Verification update 2026-10-05:** the frontend repositories issue the three read routes `GET /cp/home/summary`, `GET /cp/home/attention`, and `GET /cp/solutions` through the shared client. They remain marked ❌ because the production gateway has no handlers for them; a real-mode 404 is therefore expected until backend plans 007/010 land the operations. No fixture-shaped response mapping is promoted to the real adapter while the contract-to-UI decisions in integration plan 002 (D-1/D-2) remain open.

Screens (routes from `frontend/src/app/routes.ts`): **Login** `/login` · **Home** `/` · **Solutions** `/solutions` · **Instances** `/solutions/:solutionId/instances` · **Designer / Governance / Monitoring** (instance tabs) `/solutions/:solutionId/instances/:instanceId/:category` · **Explore** `…/monitoring/explore` · **Dashboard** `…/monitoring/dashboards/:dashboardId` · **Targets** `/targets` · **Connections** `/connections` · **Admin** `/admin/:tab` (tabs: roles, assignments, access, groups, users).

## 1. Contracted operations (`console.yaml`, 32)

| Operation | Method + route | Parameters / body | Served | Feature | Screen |
|---|---|---|---|---|---|
| `login` | POST `/cp/auth/login` | body `{username, credential}` | ✅ | auth | Login |
| `logout` | POST `/cp/auth/logout` | — | ✅ | auth | account menu (any screen) |
| `getIdentity` | GET `/cp/auth/identity` | — | ✅ | auth | app bootstrap / route guard |
| `rotateOwnCredential` | POST `/cp/auth/credential` | body `{currentCredential, newCredential}` | ✅ | auth | forced-rotation dialog (`mustRotate`) |
| `getHomeSummary` | GET `/cp/home/summary` | — | ❌ | home | Home (counters) |
| `getHomeAttention` | GET `/cp/home/attention` | — | ❌ | home | Home (attention feed) |
| `listOrganizations` | GET `/cp/hierarchy/organizations` | — | ✅ | shared/hierarchy | scope pickers: Declare-instance dialog, Instances filters |
| `listSubOrganizations` | GET `…/organizations/{organizationId}/sub-orgs` | path `organizationId` | ✅ | shared/hierarchy | same |
| `listEnvironments` | GET `…/sub-orgs/{subOrganizationId}/environments` | path `organizationId`, `subOrganizationId` | ✅ | shared/hierarchy | same |
| `listSolutions` | GET `/cp/solutions` | — | ❌ (🧪) | inventory/solutions | Solutions |
| `loadPackage` | POST `/cp/solutions/load` | body `{package}` | ❌ (🧪) | inventory/solutions | Solutions (Load package) |
| `listTargets` | GET `/cp/targets` | — | ❌ | targets | Targets; Declare dialog target picker (planned) |
| `listInstances` | GET `/dp/solutions/{solutionId}/instances` | path `solutionId` | ❌ (🧪) | inventory/instances | Instances |
| `declareInstance` | POST `/dp/environments/{environmentId}/instances` | path `environmentId`; body `{solutionId, name, targetId, declaredPackageVersion, placement{containerName, configDirectory, containerConfigPath, fileName, healthPort, healthAcquisition}}` | ❌ | inventory/instances | Instances → Declare instance dialog (frontend still calls the drifted `POST /dp/solutions/{}/instances`) |
| `getInstance` | GET `/dp/instances/{instanceId}` | path `instanceId` | ❌ (🧪) | inventory/instances | Instance header (all three tabs) |
| `getDesigner` | GET `/dp/instances/{instanceId}/designer` | path `instanceId` | ❌ (🧪) | designer | Designer |
| `stageConfig` | POST `/dp/instances/{instanceId}/config` | path `instanceId`; body `{baseStateVersion, componentSelection, draftValues, rendered}` | ❌ (🧪) | designer | Designer (Save / SaveBar) |
| `getConfigHistory` | GET `/dp/instances/{instanceId}/config/history` | path `instanceId` | ❌ | designer | Designer (version history / diff) |
| `setConfigSecret` | POST `/dp/instances/{instanceId}/config/secrets/{fieldKey}` | path `instanceId`, `fieldKey`; body `{value, baseStateVersion}` | ❌ | designer | Designer (secret field) |
| `acceptRuntimeConfig` | POST `…/config/accept-runtime` | path `instanceId`; body `{baseStateVersion, placementId}` | ❌ | designer | Designer → Accept runtime dialog (diverged banner) |
| `restoreRuntimeConfig` | POST `…/config/restore-runtime` | path `instanceId`; body `{placementId, strategy}` | ❌ | designer | Designer → Restore runtime dialog |
| `submitApply` | POST `/dp/instances/{instanceId}/apply` | path `instanceId`; body `{generation, strategy, placementId, outageConfirmedBy}` | ❌ (🧪) | designer | Designer → Apply / Strategy picker / Rollout card |
| `observeApply` | GET `…/apply/observe` | path `instanceId`; query `applyId`, `sinceStateVersion`, `limit` | ❌ (🧪) | designer | Designer → Rollout card / Replica table |
| `rollbackApply` | POST `…/apply/rollback` | path `instanceId`; body `{applyId, generation, placementId, strategy}` | ❌ | designer | Designer → Rollback dialog |
| `stopInstance` | POST `…/stop` | path `instanceId`; body `{placementId}` | ❌ | — (no UI; plan 002 D-5) | none |
| `submitFleetApply` | POST `/dp/fleet-applies` | body `{solutionId, members, strategy, outageConfirmedBy, batchSize, onFailure, proceedWithAuthorized}` | ❌ | — (no UI; D-5) | none |
| `observeFleetApply` | GET `/dp/fleet-applies/{fleetId}` | path `fleetId` | ❌ | — (no UI; D-5) | none |
| `getHealth` | GET `…/instances/{instanceId}/health` | path `instanceId` | ❌ (🧪) | governance | Governance (health hero, replica cards) |
| `getConfigLadder` | GET `…/config-ladder` | path `instanceId` | ❌ (🧪) | governance | Governance (config ladder) |
| `readLogs` | GET `…/logs/{component}` | path `instanceId`, `component`; query `limit`, `placementId`, `before`, `after` | ❌ (🧪) | governance | Governance → Log viewer |
| `getLogLevel` | GET `…/log-levels/{component}` | path `instanceId`, `component`; query `placementId` | ❌ (🧪) | governance | Governance → Log level control |
| `setLogLevel` | POST `…/log-levels/{component}` | path `instanceId`, `component`; body `{placementId, level, mode}` (`mode: config` ⛔ 501) | ❌ (🧪) | governance | Governance → Log level control |

Backend module that serves the ✅ rows: `console-gateway` (`AuthController`, `HierarchyController`); everything else is not in the production gateway.

## 2. Frontend calls with no contract operation

| Route (as the frontend calls it) | Parameters | Status | Feature | Screen |
|---|---|---|---|---|
| `GET /cp/admin/permission-catalog`, `/roles`, `/principals`, `/scope-tree`, `/assignments`, `/users` | — | planned, uncontracted | admin | Admin → roles, assignments, access, users |
| `GET /cp/admin/effective-permissions` | scope query | planned, uncontracted | admin | Admin → access |
| `POST /cp/admin/assignments`, `DELETE /cp/admin/assignments/{assignmentId}` | body `{principalId, roleId, scope[]}` / path | planned, uncontracted | admin | Admin → assignments |
| `POST /cp/admin/users`, `PATCH /cp/admin/users/{id}` | body `{…}` / `{active}` | planned, uncontracted | admin | Admin → users |
| `POST /cp/admin/roles` | body `{name, description, permissionIds[]}` | ⚠️ blocked by data model | admin | Admin → roles |
| `/cp/admin/groups*` (list, get, members, remove member, assignments) | path `groupId`, … | ⛔ 501 (no verb) | admin | Admin → groups |
| `GET/POST /cp/connections`, `GET/PUT/DELETE /cp/connections/{id}`, `PUT /cp/connections/{id}/status` | query filter; body = connection input | no contract | connections | Connections; Designer broker dropdowns |
| `GET /cp/connection-types` | — | no contract | connections | Connections (type picker) |
| `GET /cp/solutions/{solutionId}/package` | path `solutionId` | no contract | inventory/solutions | Solutions, Designer (package load) |
| `POST /dp/instances/{instanceId}/actions/{actionKey}` | path; body = action input | ⛔ 501 | designer | Designer → Package actions |
| `POST /dp/solutions/{solutionId}/instances` | body = `DeclareInstanceInput` | drift → `declareInstance` | inventory/instances | Instances → Declare dialog |

## 3. Dynamic Metrics (`/console/v1/*`) — demo-launcher only, no OpenAPI

Base path in the demo: `/api/console/v1`. Production `console-api` is backend plan 009.

| Route | Parameters / body | Feature | Screen |
|---|---|---|---|
| `GET /capabilities` | — | monitoring | Monitoring tab, Explore |
| `GET /entity-types` | — | monitoring | Explore |
| `GET /entity-types/{entityType}/instances` | path `entityType` | monitoring | Explore (entity picker) |
| `GET /metrics/catalog` | query filters | monitoring | Explore (metric picker) |
| `POST /metrics/catalog/{name}/type` | body `{type}` | monitoring | Explore (set metric type) |
| `GET /labels/{labelKey}/values` | path `labelKey` | monitoring | Explore (label filter) |
| `POST /query/range` | body = range query | monitoring | Explore result, Dashboard widgets |
| `POST /query/instant` | body = instant query | monitoring | Explore result, Dashboard widgets |
| `GET /dashboards/{dashboardId}` | path `dashboardId` | monitoring | Dashboard |
| `POST /dashboards/{dashboardId}/widgets` | body = widget | monitoring | Dashboard (add widget) |
| `PUT /dashboards/{dashboardId}/layout` | body = layout | monitoring | Dashboard (rearrange) |
| `PATCH /dashboards/{dashboardId}/widgets/{widgetId}` | path; body = patch | monitoring | Dashboard (edit widget) |
| `POST /dashboards/{dashboardId}/reset` | path | monitoring | Dashboard (reset) |

## 4. Demo-launcher-only routes with no frontend screen

`GET/POST /api/cp/setup-status`, `/setup` (first-run), `POST /api/cp/packages` (multipart upload), `POST /api/cp/solutions/load/confirm|discard`, `POST /api/cp/ssh-keys`, `/ssh-host-key`, `POST /api/cp/instance`, `GET /instance/status|logs`, `POST /instance/restart|config/ttl`, `POST /api/dp/instances/{id}/restart`, `DELETE /api/dp/instances/{id}`. None is production, none is in the contract.

## Caveats

- Screen assignments for sections 1–3 come from which repository calls which route plus the route table; I did not click through every screen to confirm which dialog fires which call. Treat dialog-level names (Rollback dialog, Accept runtime dialog, …) as the component that owns the call, not a verified UI flow.
- Section 2/3 request bodies are summarised from the frontend repositories, not from a contract — there is none to check them against.
- The `Served` column is correct as of the last merge (`58e2293`); `origin/integration/dp-remote-demo` had no newer commits when checked 2026-10-02.
