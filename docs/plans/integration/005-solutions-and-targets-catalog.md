# Solutions & Targets catalog — Integration Plan 005

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire the Solutions catalog (list + load package) and the read side of Targets to the production gateway.

**Supersedes:** plan 002 Task 3.

## Operations

| Operation | Route | Served | Frontend repository |
|---|---|---|---|
| `listSolutions` | `GET /cp/solutions` | ✅ | `frontend/src/features/inventory/solutions/solutionsRepository.ts` |
| `loadPackage` | `POST /cp/solutions/load` | ✅ | same |
| `listTargets` | `GET /cp/targets` | ✅ | `frontend/src/features/targets/targetsRepository.ts` |

## Task 1: Contract-conformance re-check

- [ ] Re-run (or extend) `frontend/src/shared/api/contractConformance.test.ts` for these three calls; confirm the route/body shape matches `console.yaml` exactly (plan 002 Task 1 found the frontend was still on fixture-era shapes for `listSolutions`).
- [ ] Confirm whether `GET /cp/solutions/{solutionId}/package` has gained a contract operation. If not, it stays in bucket F (no-contract, fixture-backed) — do not invent a mapping onto `getDesigner`.

## Task 2: Solutions adapter

- [ ] Failing test: `solutionsRepository.list()` maps `SolutionList { items: Solution[] }` (not the fixture card model) into the catalog view model. Do not synthesize `kind`, `icon`, `authoring`, entitlement, or telemetry fields the contract doesn't carry (plan 002's explicit prohibition — still correct).
- [ ] Implement. Confirm `loadPackage`'s pending/confirm/discard flow: `console.yaml` has only `loadPackage` itself — the demo's `/solutions/load/confirm|discard` steps are **not** in the contract. If the UI's load dialog assumes a two-step confirm, flag it (designer/owner decision) rather than inventing the missing step.

## Task 3: Targets (read) adapter

- [ ] Failing test: `targetsRepository.list()` maps `TargetList` (envelope rule per OR-38 — no top-level `stateVersion` requirement) into `TargetRow[]`.
- [ ] Implement. This unblocks the target picker needed by plan 007's `declareInstance` form.

## Task 4: Component tests + browser verification

- [ ] Component tests for the Solutions and Targets list screens against the real envelope shape.
- [ ] Playwright: login → Solutions list renders from backend → load a package → Targets list renders from backend.
- [ ] Record in `reports/005-task-04-report.md`.

## Decisions carried forward

None specific to this plan beyond the load-package confirm/discard question in Task 2 — raise it as a new decision if the UI needs it, don't silently drop the step.

## Out of scope

- Target *write* operations (register/scan/edit/rotate/test/retire) — plan 006.
- Instance declaration (needs this plan's target list) — plan 007.

## Global constraints

Same as plan 004 — see that plan's closing section.
