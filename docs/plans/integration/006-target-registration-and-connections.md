# Target registration & connections — Integration Plan 006

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build and wire the Targets screen's write path — host-key scanning, registration, connection editing, credential rotation, connection testing, and retirement — none of which has existing frontend UI (confirmed: `targetsRepository.ts` today only calls `GET /targets`).

**This is new UI, not a fixture swap.** Unlike plans 004/005/007/008, there's no "replace the fixture" step — the Targets screen has a read-only list and nothing else. This plan is closer in shape to plan 001 (new screen against a real backend from day one) than to plan 002's remaining tasks.

## Operations

| Operation | Route | Rate budget shared with |
|---|---|---|
| `scanHostKeys` | `POST /dp/host-key-scans` | `registerTarget`, `editTargetConnection`, `testTargetConnection` (10/min, `429 RATE_LIMITED` + `Retry-After`) |
| `registerTarget` | `POST /cp/targets` | same |
| `getTargetConnection` | `GET /cp/targets/{targetId}/connection` | — |
| `editTargetConnection` | `PATCH /cp/targets/{targetId}/connection` | same budget; at least one field required |
| `rotateTargetCredential` | `POST /cp/targets/{targetId}/connection/credential` | — (confirm with `testTargetConnection` per the contract's own runbook note) |
| `testTargetConnection` | `POST /cp/targets/{targetId}/connection/test` | same budget; one test per target at a time |
| `retireTarget` | `POST /cp/targets/{targetId}/retire` | — |

Read `contracts/openapi/console.yaml` lines ~760–1070 before touching this — the shared rate budget, the host-key pin-confirm flow (scan → user confirms fingerprint → `registerTarget` re-scans and refuses a pin mismatch), and the `testTargetConnection` four-flag cumulative shape (`hostKeyPinned` implied by `authenticated`, etc.) are load-bearing and easy to get wrong.

## Task 1: Design the flow (brainstorming required before code)

- [ ] This is new UX with no reference-design screen (the mockup at `docs/design/frontend/reference/` predates target registration). Before writing a repository or component: confirm with the designer whether a wizard (scan → confirm fingerprint → credential → test → register) or a single form with inline validation is wanted. **Do not invent this UI without a design pass** — re-invoke `superpowers:brainstorming` (bounded path) for this sub-piece specifically, scoped to "Targets screen: add registration wizard."

## Task 2: `targetsRepository` additions

- [ ] Add `scanHostKeys`, `registerTarget`, `getConnection`, `editConnection`, `rotateCredential`, `testConnection`, `retire` methods, each with a failing test against the contract request/response shapes first.
- [ ] Rate-limit handling: a shared `429 RATE_LIMITED` + `Retry-After` must surface as one UI state across all four budget-sharing calls, not four separate ad hoc error handlers.
- [ ] `I6`: credential values never logged, never rendered back, never cached client-side past the single rotate call.

## Task 3: UI (per Task 1's approved design)

- [ ] Scan step, fingerprint confirmation, registration form, connection detail/edit panel, test-connection action with the cumulative flag display, retire action with confirmation.

## Task 4: Component tests + browser verification

- [ ] Component tests per step, including the rate-limit and pin-mismatch error paths.
- [ ] Playwright: scan → register → test → edit connection → rotate credential → test again → retire. Record in `reports/006-task-04-report.md`.

## Decisions carried forward

- D-4 (connections screen, separate from targets, still has no contract operation) is **not** resolved by this plan — `/cp/connections*` stays fixture-backed. Don't conflate "target connection" (this plan, contracted) with the "Connections" screen (`/cp/connections*`, uncontracted, out of scope here). **Resolved by [plan 013](013-connections-screen-and-targets-removal.md)** (`BE-D66`/`OR-130`), which also removes the `/targets` preview screen built by this plan's Task 3 UI — the Target domain/API stay; only that screen goes.

## Out of scope

- `/cp/connections*`, `/cp/connection-types` (no contract operation — bucket F, `README.md` D-4).
- Instance declaration's target *picker* (consumes `listTargets` read, plan 005) — this plan only builds target *write* operations.

## Global constraints

Same as plan 004 — see that plan's closing section.
