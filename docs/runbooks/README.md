# Runbooks

Operational procedures — for **operators running the system**, not developers building it. Development-time
procedures belong in `../checklists/`.

One folder per component plus `common/` for anything cross-cutting. One file per procedure, named `<verb>-<noun>.md`.

## Template

```markdown
# <Verb the noun>

| | |
|---|---|
| **Applies to** | component / version |
| **Severity** | routine / urgent / incident |
| **Est. duration** | |
| **Reversible?** | yes / no — if no, say what is destroyed |

## When to use this
<The trigger. Symptoms, alert name, or scheduled cadence.>

## Preconditions
<Access required, maintenance window needed, backups verified.>

## Steps
1. …

## Verification
<How to confirm it worked. Not "it looks fine".>

## Rollback
<How to undo, or an explicit statement that you cannot.>

## Escalation
<Who to call and what to tell them.>
```

## Rules

- **Every destructive step names what it destroys** before the command, not after.
- **A runbook nobody has executed is a draft.** Mark it `UNTESTED` until someone has run it end to end.
- If a runbook exists because the system is awkward, **file the fix** — link the issue from the runbook.

## Index

| Runbook | Contains |
|---|---|
| [`install-prerequisites.md`](install-prerequisites.md) | Credential-hash capacity; one Solution Manager replica |
| [`backend/enable-agent-gateway.md`](backend/enable-agent-gateway.md) | The agent path: CA keystore and lease-nonce key, TLS proxy, database role passwords, issuing a token, onboarding an agent, the scheduled jobs, a first-issue agent that never gets a lease — `UNTESTED` until G-A1 |
| [`backend/restart-the-gateway-with-a-drain.md`](backend/restart-the-gateway-with-a-drain.md) | Planned restart with a drain; `drainAgent`; the single-replica limits — `UNTESTED` |
| [`backend/revoke-or-change-an-agent.md`](backend/revoke-or-change-an-agent.md) | `listAgents`, `getAgent`, `revokeAgent`, `retireTarget`; discovery tombstones and the stuck-`PENDING` row — `UNTESTED` |
| [`backend/read-and-export-agent-logs.md`](backend/read-and-export-agent-logs.md) | Agent placement logs: `sm.dp.logs.*` / `sm.gateway.logs.*`, export statuses and marker lines, resuming with `fromSeq`, memory envelope, the stalled-`Logs`-stream residual — `UNTESTED` end to end |
| [`backend/release-the-images.md`](backend/release-the-images.md) | Releasing the launcher and agent images: licence gates, NOTICE obligations, the corresponding-source offer — `UNTESTED` draft |
| [`operations.md`](operations.md) | Orphaned-node cleanup, **the one combined upgrade from the V014/V020 release** (V019/V021/V024 are its reference notes), placements, undeclare, registering and managing targets and users through the API, the TLS-proxy rule, the organization-rename command and the live-gate procedures — all `UNTESTED` |
