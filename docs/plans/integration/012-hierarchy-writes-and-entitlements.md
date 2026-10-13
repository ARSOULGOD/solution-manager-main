# Hierarchy writes & entitlements — Integration Plan 012

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build and wire the write side of the hierarchy (create/rename sub-organization/environment/organization) and entitlements (list/set), neither of which existed when plan 001 built the hierarchy's read side.

**This is new UI, same caveat as plan 006/007 Task 3** — plan 001 only built the org/sub-org/environment *cascade picker* (read-only). There is no existing screen for creating or renaming a node, or for viewing/setting entitlements.

## Operations

| Operation | Route |
|---|---|
| `createSubOrganization` | `POST /cp/hierarchy/organizations/{organizationId}/sub-orgs` |
| `renameOrganization` | confirm exact route in `console.yaml` (grouped near `listOrganizations`) |
| `renameSubOrganization` | confirm exact route |
| `createEnvironment` | `POST /cp/hierarchy/.../sub-orgs/{subOrganizationId}/environments` |
| `renameEnvironment` | confirm exact route |
| `listEntitlements` | `GET /cp/entitlements` |
| `setEntitlement` | `POST /cp/entitlements` (confirm verb) |

## Task 1: Design the screens (brainstorming required before code)

- [ ] No reference-design mockup covers hierarchy create/rename or entitlements. Before building: a bounded design pass with the designer — where do these actions live (Admin tab? inline on the hierarchy picker?), and what does an entitlement list/edit UI look like. Don't guess layout from the operation shapes alone.

## Task 2: Hierarchy write adapters

- [ ] Failing tests → adapters for create/rename on all three levels. `I5`: `organizationId` in the path is never client-asserted as authority beyond "the id the user is currently scoped to or explicitly navigated to" — same rule as plan 007's `environmentId`.
- [ ] Confirm whether rename is a `PATCH`/`PUT` with optimistic concurrency (a `stateVersion`-style precondition) — if so, the UI needs a conflict state, not a blind overwrite.

## Task 3: Entitlements

- [ ] `listEntitlements`/`setEntitlement` adapters. Confirm against `console.yaml` exactly what an entitlement record names (solution? target? scope level?) before designing the UI — this plan found the routes but hasn't read the schema bodies; do that first, in Task 1's design pass, not after building.

## Task 4: Component tests + browser verification

- [ ] Playwright: create a sub-organization → rename it → create an environment under it → set an entitlement → list entitlements and see it. Record in `reports/012-task-04-report.md`.

## Decisions carried forward

None from plan 002 — this surface didn't exist in its scope.

## Out of scope

- Admin RBAC screens (roles/assignments/access/users/groups) — still no contract operation, stays fixture-backed (`README.md` D-4). Do not conflate "entitlements" (this plan, contracted, resource-scoped) with "RBAC role assignment" (admin screens, uncontracted, principal-scoped) — they are different things even though both gate access.

## Global constraints

Same as plan 004 — see that plan's closing section.
