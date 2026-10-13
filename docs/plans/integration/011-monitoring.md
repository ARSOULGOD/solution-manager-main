# Monitoring — Integration Plan 011

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire the Monitoring tab, Explore, and Dashboard screens to the production `console-api` (`/api/console/v1/*`), which did not exist in any form (not even demo-launcher) when plan 002 was drafted.

**This is not a plan-002 supersession** — plan 002 explicitly deferred monitoring entirely ("Dynamic Metrics... no OpenAPI document exists... needs an owner-approved contract first"). That contract now exists as part of `console.yaml` (backend plan 011, `console-api`); this is new integration work, not a stale-plan rewrite.

## Operations

| Operation | Route | Frontend repository |
|---|---|---|
| `getCapabilities` | `GET /console/v1/capabilities` | `frontend/src/features/monitoring/metricsRepository.ts` |
| `listEntityTypes` | `GET /console/v1/entity-types` | same |
| `listMetricInstances` | `GET /console/v1/entity-types/{entityType}/instances` | same |
| `getMetricsCatalog` | `GET /console/v1/metrics/catalog` | same |
| `declareMetricType` | `POST /console/v1/metrics/catalog/{name}/type` | same |
| `getLabelValues` | `GET /console/v1/labels/{labelKey}/values` | same |
| `queryRange` | `POST /console/v1/query/range` | same |
| `queryInstant` | `POST /console/v1/query/instant` | same |
| `exportRange` | `GET /console/v1/export/range` | same |
| `getDashboard` | `GET /console/v1/dashboards/{dashboardId}` | `frontend/src/features/monitoring/dashboardRepository.ts` |
| `saveWidget` | `POST /console/v1/dashboards/{dashboardId}/widgets` | same |
| `setDashboardLayout` | `PUT /console/v1/dashboards/{dashboardId}/layout` | same |
| `setWidgetHidden` | `PATCH /console/v1/dashboards/{dashboardId}/widgets/{widgetId}` | same |
| `resetDashboard` | `POST /console/v1/dashboards/{dashboardId}/reset` | same |

⚠️ **`exportRange` caveat:** per `ENDPOINT-INVENTORY.md`, it is wired and tested but **not counted "served"** until backend's P11-8.1 records the 105M-row stream measurement. Treat it as functionally present but unvalidated at scale — don't block the rest of this plan on it, but don't claim it's production-proven either.

## Task 1: Capabilities + entity/metric catalog

- [ ] Failing tests → adapters for `getCapabilities`, `listEntityTypes`, `listMetricInstances`, `getMetricsCatalog`, `getLabelValues` against the real contract schemas. `metricsRepository.ts` currently imports `CatalogEntry`/`MetricType` from `@shared/testdoubles/data/metrics` as types — confirm these become generated types from `console.yaml`, not hand-maintained fixture-era ones (`frontend/README.md`'s replacement sequence, step 3).
- [ ] `declareMetricType` — confirm this is the "set metric type" action the Explore UI already calls under a different assumed route; align to the real one.

## Task 2: Query

- [ ] `queryRange`/`queryInstant` adapters. Error codes to route distinctly, not as one generic failure: `SERIES_CAP_EXCEEDED`, `AGGREGATION_NOT_APPLICABLE`, `GROUP_BY_REQUIRES_AGGREGATION`, `STAT_REQUIRES_AGGREGATION`, `METRIC_UNTYPED` (from `ENDPOINT-INVENTORY.md`'s error-code additions).
- [ ] Composition caveats from `ENDPOINT-INVENTORY.md` §1a: an inactive replica is not yet composed into a query series (`replicaComposition.inactive` is always `0`, series `state` is always `ok`), and `acrossReplicas` is validated but not applied. The Explore UI must not claim cross-replica composition is happening when it isn't — surface what the backend actually does, not what the request parameter implies.

## Task 3: Export

- [ ] `exportRange` adapter + download flow (`frontend/src/shared/api/download.ts` already exists — reuse it, don't build a second download path). Given the scale caveat above, add a visible "large export may be slow" note if the UI doesn't already have one; don't block the feature on P11-8.1 landing.

## Task 4: Dashboards

- [ ] `getDashboard`/`saveWidget`/`setDashboardLayout`/`setWidgetHidden`/`resetDashboard` adapters in `dashboardRepository.ts`. Confirm the add-widget and layout-rearrange flows already present in the Dashboard screen (per `FE-131`/`FE-132` in the frontend plan) call these exact routes, not demo-era ones (the demo launcher served the same route shapes, so drift risk here is lower than elsewhere — verify, don't assume).

## Task 5: Component tests + browser verification

- [ ] Playwright: Monitoring tab → Explore (pick entity/metric, run a range query, see an error-code case) → export → Dashboard (add widget, rearrange layout, hide a widget, reset). Record in `reports/011-task-05-report.md`.

## Decisions carried forward

None from plan 002 (monitoring was entirely out of scope there). New open item surfaced above: whether the UI needs copy changes to avoid overstating cross-replica composition (Task 2) — raise as a decision if the existing reference-design copy claims otherwise, don't silently rewrite the reference design's wording without a note.

## Out of scope

- Nothing — this plan covers all 14 monitoring operations.

## Global constraints

Same as plan 004 — see that plan's closing section.
