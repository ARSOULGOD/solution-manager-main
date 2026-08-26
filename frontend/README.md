# frontend

The console: schema-driven renderer, plane-split API clients, version/restart UX, declared-inventory management.

**Stack:** React + Vite · **Status:** no code yet.

## Before writing code here

This component does not own its own architecture. Read, in order:

| What | Where |
|---|---|
| Cross-cutting decisions | [`../docs/design/DECISIONS.md`](../docs/design/DECISIONS.md) |
| Overall architecture | [`../docs/design/architecture.md`](../docs/design/architecture.md) |
| **This component's design** | `../docs/design/frontend/design.md` — **not yet written** |
| This component's decisions | `../docs/design/frontend/decisions.md`, ID family `FE-D*` |
| Approved plan | `../docs/plans/frontend/NNN-<slug>.md` — **none yet** |
| Setup checklist | [`../docs/checklists/new-module.md`](../docs/checklists/new-module.md) |

⚠️ **The baseline is UNBASELINED** — `SM-D*` decisions are PROPOSED and the design has open blocking items. Do not
start implementing against them. See [`../docs/tasks/codex-review-triage.md`](../docs/tasks/codex-review-triage.md).

**If the design is wrong or missing, raise it — do not invent architecture here.** Component-level decisions get an
ID in the `FE-D*` family and are recorded in the design folder, not in code comments.
