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

`D10` (Azure-style RBAC, policy-as-code) · `D13` (Keycloak, deferred from v1) ·
**the [ratified `D10` amendment](../design/D10-G2-APPROVAL-2026-09-15.md)** — ✅ **`LOCKED` 2026-09-16**: the
**38-verb** catalog, **three built-in roles** (Administrator bootstrap-only, Viewer, Operator), scope-evaluated
bindings with downward inheritance, install-seeded bootstrap with forced rotation, minimal local identity,
durable/revocable sessions with CSRF, append-only CP audit and one enforcement point ·
`BE-D15` (the machinery) · `SM-D32`/`SM-D33` (the scope tree and its prefix algorithm) · `FE-D22` (the console's
rendering overlay, never a boundary)

## Acceptance — clause by clause, as of 2026-09-16

| Acceptance clause | Status | By what |
|---|---|---|
| *"effective permissions derive from **scoped** assignments"* | **buildable** | The amendment §1.2 approves scope-evaluated bindings with downward inheritance and **rejects** `BE-D15` §5.1's whole-install v1 narrowing. `role_binding` references a canonical scope record, never a name or an id array |
| *"every request passes one centralized deny-by-default check"* | **buildable** | `authorizationService.require(...)` as the single point; no controller-local role checks, no production super-admin bypass; an ArchUnit rule with a planted-violation proof (amendment §1.5) |
| *"no endpoint is reachable without it"* | **buildable for the ratified surface only** | The catalog is closed at **38**, so an endpoint needing an unapproved verb cannot ship. ✅ `stop`, the audit read and non-status user edits are now **authorizable** (amendment §1.7). ⚠️ **Six** surfaces remain **deliberately unavailable**, not unguarded — open item 39's six remaining letters: the high-consequence restart strategies incl. `instance.restart.overrideSafety` (b), the ten `/cp/admin/groups*` operations (c), `instance.undeclare` (d), package actions (e), `AGW-10`'s agent verbs (h), and `instance.version.redeclare` with archive/restore (i). ⚠️ **An earlier revision said "two"**, naming only (b) and (c) (amendment §3; open item 39) |

⚠️ **"Buildable" is not "built."** Nothing above is implemented on any branch; it is
[plan 006](../plans/backend/006-local-identity-and-scoped-rbac.md)'s scope. The only authorization code in the tree
is `backend/demo-launcher`'s four hard-coded strings, which the amendment §6 records as non-authoritative and whose
authorizing waiver expired on 2026-09-14.

## Conflicts

⚠️ `D10` requires `target.use` authorization on a Target, but the Target model had **no owner** until `SM-D23` added `ownership_scope`.

✅ **Resolved** — the catalog gap that made this requirement unbuildable (open item 24: 30 permissions, 2 grantable) is closed by the [`D10` amendment](../design/D10-G2-APPROVAL-2026-09-15.md): **buildable from 2026-09-15** (the project-owner authorization) and **ratified `LOCKED` 2026-09-16**, at **38** verbs with three built-in roles.

⚠️ **Recorded, not resolved** — alongside the ratified **38**, the tree still holds a **different 35-member** catalog (`frontend/src/shared/authz/catalog.ts`) and a 4-string demo vocabulary; the delta is enumerated in that record §6.
