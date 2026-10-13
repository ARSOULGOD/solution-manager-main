# Apply, rollout, schedules, fleet — Integration Plan 009

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire Designer's apply/rollback/stop flow to the production gateway, and resolve D-5 (fleet apply has no UI) and the scheduled-strategy work plan 002 never saw (scheduling didn't exist as a backend feature at the time).

**Supersedes:** plan 002 Task 6 and D-5.

## Operations

| Operation | Route | Frontend repository |
|---|---|---|
| `submitApply` | `POST /dp/instances/{instanceId}/apply` | `frontend/src/features/designer/applyRepository.ts` |
| `observeApply` | `GET /dp/instances/{instanceId}/apply/observe` | same |
| `rollbackApply` | `POST /dp/instances/{instanceId}/apply/rollback` | same |
| `stopInstance` | `POST /dp/instances/{instanceId}/stop` | not yet called — D-5 |
| `listApplySchedules` | — (new, 2026-10-08) | not yet called — new |
| `cancelApplySchedule` | — (new, 2026-10-08) | not yet called — new |
| `submitFleetApply` | `POST /dp/fleet-applies` | not yet called — D-5 |
| `observeFleetApply` | `GET /dp/fleet-applies/{fleetId}` | not yet called — D-5 |

Confirm exact `listApplySchedules`/`cancelApplySchedule` routes against `console.yaml` directly — they postdate every doc in this folder except `ENDPOINT-INVENTORY.md`'s 2026-10-08 note (plan 012 T7, `OPS-Q9` Sch-2).

## Task 1: Apply + observe + rollback (existing UI)

- [ ] `placementId` is required on `submitApply`/`rollbackApply` (OR-53/plan 011's `BE-D23`) — v1 refuses instance-wide apply. Confirm `applyRepository.ts` already requires it; if not, that's the first failing test.
- [ ] `observeApply` now returns an `ApplyObservationList`, not a bare observation (shape drift plan 002 flagged, re-verify it's current) — `ApplyOutcome`/`ReplicaObservationFixture` type-only imports in `applyRepository.ts` likely need regenerating.
- [ ] `ApplyObservation.state` is the engine's 12-value `RequestState` verbatim (OR-36) — map all 12, including `APPLY_INDETERMINATE` (OR-58/59), to a distinct `RolloutCard`/`ReplicaTable` state. Don't collapse unknown states into a generic "running."
- [ ] `Strategy` accepts 5 tokens; confirm current 501/422 status per-strategy against `console.yaml` directly (per `API-INVENTORY.md`'s 2026-10-08 correction: `scheduled` is now a `202`+`schedule` window, not a 501; `restart-all`/`rolling-no-validation` need their own current check, don't assume plan 002's table). The strategy picker must only offer strategies that don't 501/422-refuse.

## Task 2: Scheduled apply/rollback

- [ ] `submitApply`/`rollbackApply` optionally take `schedule: {notBefore, notAfter}`; the accepted answer is `202` with a `scheduleId` and **no `applyId`** — nothing is admitted yet. This is a genuinely new UI state (a scheduled-but-not-yet-admitted apply), not a variant of the existing rollout card.
- [ ] Build `listApplySchedules` (view pending schedules) and `cancelApplySchedule` (cancel one) — new UI, scope it with the designer before building (bounded brainstorming pass) since there's no reference-design screen for this.
- [ ] `restoreRuntimeConfig` and `submitFleetApply` refuse `scheduled` with `422` naming the `schedule` field — surface that as a clear "not schedulable" state on those two flows specifically, not a generic validation error.

## Task 3: `stopInstance` (D-5)

- [ ] No UI today. Scope with the designer: where does a stop action live (instance header? Designer toolbar?) and what confirmation does it need given it's destructive-adjacent. Body is `{placementId}`.

## Task 4: Fleet apply (D-5)

- [ ] `submitFleetApply` body `{solutionId, members, strategy, outageConfirmedBy, batchSize, onFailure, proceedWithAuthorized}`; fleet refuses whole-fleet-apply by default (OR-57). Owner ruling already says fleet apply is v1 scope (OR-47) but "needs its own design before the apply slice" — this plan's Task 4 **is** that design-then-build step, scoped narrowly to fleet apply only (not a general multi-instance UI).
- [ ] `observeFleetApply` for progress. No existing screen owns this — likely a new route/page, not a tab on the existing Instance detail.

## Task 5: Component tests + browser verification

- [ ] Playwright: submit apply with `placementId` → observe → rollback; submit a scheduled apply → view in schedule list → cancel; stop an instance; submit a fleet apply → observe. Record in `reports/009-task-05-report.md`.

## Decisions carried forward

- D-5 is resolved by this plan building the UI (Tasks 3/4), per the owner's existing ruling that fleet apply is in v1 scope.

## Out of scope

- `restart-all`/`rolling-no-validation` if they're still 501 when Task 1 checks — render "not available," don't attempt.

## Global constraints

Same as plan 004 — see that plan's closing section.
