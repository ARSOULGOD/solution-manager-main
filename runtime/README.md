# runtime

**SM's own container packaging** — Dockerfiles, image layering, compose for local development. NOT the MI runtime (see the terminology note in the root README).

**Stack:** Docker · **Status:** no code yet.

## Before writing code here

This component does not own its own architecture. Read, in order:

| What | Where |
|---|---|
| Cross-cutting decisions | [`../docs/design/DECISIONS.md`](../docs/design/DECISIONS.md) |
| Overall architecture | [`../docs/design/architecture.md`](../docs/design/architecture.md) |
| **This component's design** | `../docs/design/runtime/design.md` — **not yet written** |
| This component's decisions | `../docs/design/runtime/decisions.md`, ID family `RT-D*` |
| Approved plan | `../docs/plans/runtime/NNN-<slug>.md` — **none yet** |
| Setup checklist | [`../docs/checklists/new-module.md`](../docs/checklists/new-module.md) |

⚠️ **The baseline is UNBASELINED** — `SM-D*` decisions are PROPOSED and the design has open blocking items. Do not
start implementing against them. See [`../docs/tasks/codex-review-triage.md`](../docs/tasks/codex-review-triage.md).

**If the design is wrong or missing, raise it — do not invent architecture here.** Component-level decisions get an
ID in the `RT-D*` family and are recorded in the design folder, not in code comments.
