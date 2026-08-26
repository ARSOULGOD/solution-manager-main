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
