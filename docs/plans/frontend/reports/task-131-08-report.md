# Task 131.08 — Descriptor-driven connection drawer

## Built

- Added descriptor-driven field visibility, defaults, validation, and secret write handling.
- Added the **edit/save** flow (`onSave`) and a disabled Test Connection affordance with its
  backend dependency stated.
- Preserved secret plaintext isolation: reads expose status only and writes send only newly entered
  secret values.

**Correction (2026-09-25, [[L21]]-class finding):** this report originally also claimed "create" and
"deactivate/delete flows" for `fdb0420`. Checked against that commit's actual diff: it added `onSave`
only — no deactivate/delete UI, and "create" is `ConnectionsRoute`'s later `creatingType` draft (task
9), not this task. `connectionsRepository.ts`'s `deactivate()`/`remove()` methods and their MSW
handlers existed from early on, but nothing in the drawer called them until
[`task-131-12-report.md`](task-131-12-report.md) closed that gap. Left here rather than silently
edited, per this repo's own rule against reporting unverified success.

## Verification

- Form-logic, drawer interaction, and accessibility tests pass.
- Delivered by `fdb0420`.
