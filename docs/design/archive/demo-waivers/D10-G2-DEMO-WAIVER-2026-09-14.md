# G2 Demo Waiver - 2026-09-14

> ⏹ **EXPIRED 2026-09-14 23:59 Asia/Kolkata, and superseded for its purpose on 2026-09-15.** This document's **substantive body is preserved as historical evidence** of what one local demonstration was authorized to build; only this archival banner and relative links rebased for relocation differ from the original. Nothing below is current authority. Standing authorization for auth-bearing code and endpoints now comes from [`D10-G2-APPROVAL-2026-09-15.md`](../../D10-G2-APPROVAL-2026-09-15.md), which is narrower in some respects and wider in others: it grants the full **38**-verb catalog, three built-in roles and scoped bindings, and it does **not** authorize the demo surface this waiver permitted. The §Follow-up below is discharged: open item 24 is closed.

| | |
|---|---|
| **Status** | Active only for the scope and time stated below. It is not a `D10` amendment or a ratification. |
| **Authorizing owner** | Parikshit Tyagi, written approval in the project session on 2026-09-14. |
| **Expires** | 2026-09-14 23:59 Asia/Kolkata. |
| **Waives** | The interim G2 restriction on auth-bearing backend code and endpoint exposure. |
| **Does not waive** | `D10`, open item 24, open item 34, `BE-D5`, G3, or any other decision or security requirement. |

## Purpose

Permit one local, browser-driven Cache demonstration: a single operator signs in, loads a Cache
Solution Package, declares one EC2 Docker instance, reads its status/health/bounded logs, stages an
allowlisted configuration change, and explicitly restarts the declared container.

The target Cache container remains customer-deployed. Solution Manager does not pull, build, replace,
or otherwise deploy its artifact.

## Narrow authority

The implementation may create only the following local-demo authorization surface:

- One installer-seeded Bootstrap Super Admin with forced credential rotation on first sign-in.
- Cookie-authenticated login, identity lookup, logout, CSRF protection, lockout and session expiry.
- One fixed demo role granted only to that administrator. There is no Admin UI, user CRUD, group CRUD,
  role editor, assignment editor, SSO, broker ticket, browser-mesh, or general RBAC administration.
- The following permission checks, at one centralized deny-by-default enforcement point:
  `package.load`, `solution.view`, `instance.view`, `instance.create`, `config.view`, `config.edit`,
  `config.apply`, `instance.restart`, `health.view`, and `logs.read`.
- The following authenticated HTTP actions only: login/logout/identity; Cache package upload and
  solution read/create; one EC2 Docker instance registration/read; status, health and bounded log
  reads; allowlisted configuration staging/apply; and an explicit restart command.

Every mutating endpoint requires the authenticated Bootstrap Super Admin and CSRF protection. There
is no unauthenticated fallback, no development-only production bypass, and no browser-originated SSH
or Docker command.

## Limits and expiry handling

- Deployment is local/demo-only. It is not an approval for a shared, customer, internet-exposed, or
  production installation.
- The only managed runtime is the declared Cache Docker container on the demo EC2 host. SSH remains
  behind `RuntimeAdapter`; the frontend and controllers never issue SSH or Docker commands.
- Credentials, SSH private keys, broker secrets, raw config secrets and raw remote errors must not be
  persisted, returned by an API, committed, or written to logs.
- This waiver expires without extension. At expiry, the implementation must not be represented as
  authorized for deployment. Continuing endpoint exposure requires either a replacement written
  waiver or G2 ratification by the architect group.

## Follow-up

Open item 24 remains the permanent decision: approve, amend or reject
[`D10-AMENDMENT-PROPOSAL.md`](../../D10-AMENDMENT-PROPOSAL.md). This waiver decides none of its wider
catalog, scope, identity, audit, or administrator-lifecycle questions.
