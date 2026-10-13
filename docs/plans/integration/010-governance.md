# Governance — Integration Plan 010

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire the Governance tab — health, config ladder, log viewer, log level control — to the production gateway.

**Supersedes:** plan 002 Task 7.

## Operations

| Operation | Route | Frontend repository |
|---|---|---|
| `getHealth` | `GET /dp/instances/{instanceId}/health` | `frontend/src/features/governance/governanceRepository.ts` |
| `getConfigLadder` | `GET /dp/instances/{instanceId}/config-ladder` | same |
| `readLogs` | `GET /dp/instances/{instanceId}/logs/{component}` | same |
| `getLogLevel` | `GET /dp/instances/{instanceId}/log-levels/{component}` | same |
| `setLogLevel` | `POST /dp/instances/{instanceId}/log-levels/{component}` | same |

## Task 1: Health

- [ ] `Health` dropped the retired REST vocabulary for the S0-11 notify vocabulary, per placement, with **no top-level `state`/`stateVersion`** (OR-39/OR-55) — confirm `governanceRepository.ts`'s `HealthSnapshot` type doesn't still assume a top-level state field; that's the most likely stale assumption.
- [ ] Includes the `unreachable` sub-state (OR-54) — the health hero and replica cards need a distinct visual state for it, not a fallback to a generic error tone.
- [ ] Failing test → adapter → implement.

## Task 2: Config ladder

- [ ] `ConfigLadder` gained a top-level `reached` and per-placement `placements[]` (five-rung ladder everywhere per OR-37) — confirm the ladder component renders per-placement rungs, not one ladder for the whole instance.

## Task 3: Logs

- [ ] `readLogs` gained `placementId`/`before`/`after` query params — the log viewer needs a placement selector if the instance has more than one placement (plan 007 territory for multi-placement UI, but this plan wires the query once that selector exists).
- [ ] `getLogLevel`/`setLogLevel` take `placementId`. `setLogLevel` returns `202 ApplyAccepted` (not a bare `Versioned`) and its body names `placementId`/`mode`. ⚠️ **Correction (2026-10-08, plan 010 execution):** this plan originally said `mode: config` was the 501 gap — verified wrong against `backend/console-gateway/.../LogLevelOrchestrator.java`. **`mode: config` is the built path** (202 via a real apply); `mode: live` is the one that's refused (501, or 422 on direct-HTTP acquisition). The log-level control must render `live` as unavailable, not `config`.

## Task 4: Component tests + browser verification

- [ ] Playwright: open Governance tab → health hero + replica cards render, including an `unreachable` replica → config ladder shows per-placement rungs → view logs for a placement → change log level (runtime mode only). Record in `reports/010-task-04-report.md`.

## Decisions carried forward

None open from plan 002 specific to Governance beyond the shape-drift re-checks above (OR-37/39/54/55 are rulings, not open decisions — this plan verifies the frontend actually reflects them, it doesn't re-decide them).

## Out of scope

- `setLogLevel` with `mode: live` — stays 501/422, render "not available." (Corrected — see Task 3 note; this was originally written as `mode: config`, which is backwards.)

## Global constraints

Same as plan 004 — see that plan's closing section.
