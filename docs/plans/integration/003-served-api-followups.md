# Served-API follow-ups — Integration Plan 003

**Status: ✅ COMPLETE 2026-10-02.** Closes the six follow-ups listed in [plan 001's review](001-auth-hierarchy.md#review--plan-closed-2026-10-01) that can be closed without a backend or contract change. Scope: the 7 operations the production gateway serves. No new operations; `console.yaml` and `backend/` code untouched.

| # | Follow-up (plan 001) | Result |
|---|---|---|
| 1 | First-time visitors see "Your session ended" | ✅ **Fixed.** `AuthProvider` treated the 401 on the initial identity probe as a session expiry. It now only reports `arrival='session'` when a session was actually established (`hadSessionRef`, set on identity/login, cleared on expiry/logout). Test written first and seen failing (`Received: "session"`), then passing. |
| 2 | Login takes 5–8 s with no feedback | ✅ **Already done.** `LoginForm` disables the button and shows `signingIn` ("Signing in…") while submitting; double-submit guarded by a ref. No change needed. |
| 3 | Stale MSW hierarchy fixtures | ✅ **Already done** (commit `473fab4`): handlers return the `ScopeNodeList` envelope, addressed by id. Hierarchy + testdouble suites pass (100 tests). |
| 4 | No runbook step for the bootstrap credential hash | ✅ **Done.** New section in [`backend/README.md`](../../../backend/README.md#bootstrap-administrator-credential-install-step). The hash command was run against the built `cp-core` and produced a valid `{argon2id}…m=393216,t=4,p=1` hash. |
| 5 | Node names / org label are raw UUIDs | ⛔ **Not fixable in the frontend.** `ScopeNode.name` is a placeholder on the wire; `sm_control` has no display-name column (`ENDPOINT-INVENTORY.md` §"Served now", `D11-AMENDMENT-PROPOSAL.md` §2). Needs a backend schema change plus a contract ruling. |
| 6 | `mvn verify` fails in `mesh-events` | ⛔ **Owner decision** — `tenant` vs `organization` reconciliation (`D20`/`FE-D51`/`FE-D53`). Unchanged. |

## Verification

- Unit: `AuthProvider` + `features/auth` + `shared/auth` suites (87 tests) pass; `tsc --noEmit` and `eslint src/shared/auth` clean.
- **Not browser-verified** for follow-up 1: the unit test reproduces the race (listener subscribed, 401 arrives async) but the real-browser first-visit path was not re-run in this plan. Do that with the next Playwright journey (plan 002 Task 8).

## What this does not change

The 7 served operations, their routes, the contract, the 38-verb catalog, I5/I6.
