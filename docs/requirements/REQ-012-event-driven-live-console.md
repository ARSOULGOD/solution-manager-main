# REQ-012 — Event-driven backbone and live console

| | |
|---|---|
| **Status** | ACCEPTED |
| **Source** | backend architecture direction (parikshit.tyagi@solace.com, 2026-08-27) |
| **Raised** | 2026-08-27 |
| **Priority** | must |
| **Phase** | v1 (notify events) · phase 2 (live chart streams) |

## Requirement

An operator with the console open sees state changes — health, config-state, version, rollout progress —
reflected live, within seconds, without a manual refresh or a poll interval's lag. Staleness must be
**bounded and honest**: when live delivery is unavailable the console states so and degrades to a slower
mechanism rather than presenting stale data as current. Live delivery must not weaken tenant isolation
(`I5`) or carry values the operator is not authorized to read (`I6`). Commands and queries remain
transactional request/response.

*(The selected mechanism — the customer's event mesh, the Solace WebApp SDK, gateway-minted tickets — is
recorded in `BE-D1`…`BE-D9`, not here; per the requirements guide, a different mechanism satisfying the
acceptance below would still comply.)*

## Why

SM is a Solace product managing Solace-connected integrations for Solace customers — every deployment site
already runs an event mesh, and push-mode MIs' business/reporting telemetry already travels on it (File Mesh
command-center events). Polling an event-mesh company's own console is both an operational lag (a replica
goes down and the operator waits out a poll interval) and a dogfooding failure. REQ numbering note:
`REQ-010`/`REQ-011` are reserved by requests recorded in [`traceability.md`](traceability.md) and are not
used here.

## Acceptance

With the console open: a health or config-state transition on a visible instance is reflected in the UI
within seconds without a manual refresh; when the mesh is unreachable the console states so and degrades to
polling rather than showing stale data as live; no event topic readable by a browser ever carries a config
value, secret, or metric value beyond an explicitly granted chart stream; a browser holding tenant A's
session cannot subscribe to tenant B's topics.

## Satisfied by

`BE-D1`…`BE-D9` ([`../design/backend/decisions.md`](../design/backend/decisions.md)) · design at
[`../design/backend/design.md`](../design/backend/design.md)

## Conflicts

⚠️ Supersedes `DM-D17` (polling) as the default freshness mechanism — polling is retained as the fallback.
⚠️ Amends the frontend family's transport posture (`FE-D19` gains a same-origin websocket; `FE-D20`'s
refetch triggers gain event-driven invalidation). Flagged in
[`../design/backend/decisions.md`](../design/backend/decisions.md), not silently resolved (`I4`).
