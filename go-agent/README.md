# go-agent

Agent for the `host-local` locus: enrollment, mTLS, protocol, collectors, runtime version introspection, K8s leader election.

**Stack:** Go · **Status:** no code yet.

## Before writing code here

This component does not own cross-cutting architecture. Read, in order:

| What | Where |
|---|---|
| Cross-cutting decisions | [`../docs/design/DECISIONS.md`](../docs/design/DECISIONS.md) |
| Overall architecture | [`../docs/design/architecture.md`](../docs/design/architecture.md) |
| **This component's design** | [`docs/design.md`](docs/design.md) |
| This component's decisions | [`docs/decisions.md`](docs/decisions.md), ID family `AG-D*` |
| **Sequenced workplan** | [`docs/workplan.md`](docs/workplan.md) — contract-spine path to first vertical slice |
| **Agent context** | [`agent.md`](agent.md) — whole-monorepo durable context; update via skill `agent-md-context` on wrap-up |
| Approved plan | `../docs/plans/go-agent/NNN-<slug>.md` — **none yet** |
| Setup checklist | [`../docs/checklists/new-module.md`](../docs/checklists/new-module.md) |

⚠️ **The baseline is UNBASELINED** — `SM-D*` decisions are PROPOSED and the agent↔DP wire protocol does not exist yet.
Do not start implementing against them. See [`../docs/tasks/codex-review-triage.md`](../docs/tasks/codex-review-triage.md).

**If the design is wrong or missing, raise it — do not invent architecture here.** Component-level decisions get an
ID in the `AG-D*` family and are recorded in [`docs/decisions.md`](docs/decisions.md), not in code comments.
