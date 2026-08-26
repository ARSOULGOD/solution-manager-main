# REQ-006 — RBAC for access and control

| | |
|---|---|
| **Status** | ACCEPTED |
| **Source** | parent objectives (2026-06-17) |
| **Raised** | 2026-06-17 (backfilled 2026-08-10) |
| **Priority** | must |
| **Phase** | v1 |

## Requirement

Scoped, inheritable role-based access control over solutions, environments and deployment targets.

## Why

Multiple orgs and environments in one console; without scoped authz the platform cannot be given to a customer's own teams.

## Acceptance

A user's effective permissions derive from scoped assignments; every request passes one centralized deny-by-default check; no endpoint is reachable without it.

## Satisfied by

`D10` (Azure-style RBAC, policy-as-code) · `D13` (Keycloak, deferred from v1)

## Conflicts

⚠️ `D10` requires `target.use` authorization on a Target, but the Target model had **no owner** until `SM-D23` added `ownership_scope`.
