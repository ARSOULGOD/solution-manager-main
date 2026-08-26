# Implementation plans — convention (CANONICAL)

Implementation plans live at **`plans/<component>/NNN-<slug>.md`**, next to the design they implement. Monorepo, so
design, plan and code move in one reviewed PR.

## Two tiers — which is authoritative

| Artifact | Location | Nature |
|---|---|---|
| **Approved plan** | `plans/<component>/NNN-<slug>.md` | Reviewed, versioned, **authoritative** — the contract for what gets built |
| **Working checklist** | `docs/tasks/<slug>/todo.md` | Live progress, ephemeral, **derived from** the approved plan |

When they disagree the approved plan wins — update it deliberately rather than letting the checklist drift. The
checklist is scoped per task rather than one file per repo, because a single `todo.md` is a merge-conflict magnet.

## Execution

Use `superpowers:executing-plans`, **on a lower-cost model** — writing the plan needs the strongest model
available; executing an already-written plan does not.

**The agent must ask which session mode to use before it starts executing**, and state which it recommends. Same
session suits a single focused task (<5 steps); a new parallel session suits multi-task work, keeping context fresh
and cost down. The choice is the developer's — but the ask is mandatory.

## Template

```markdown
# NNN — <plan title>

| | |
|---|---|
| **Component** | |
| **Design** | link to `design/<component>/design.md` §… |
| **Requirements** | REQ-NNN, … |
| **Decisions** | decision IDs this must honour (`design/DECISIONS.md`) |
| **Status** | DRAFT / APPROVED / IN PROGRESS / DONE |

## Scope
<What is in. And explicitly, what is out.>

## Prerequisites
<Decisions that must be ratified, contracts that must be published, open items that must close first.>

## Tasks
- [ ] 1. <task> — verification: <how we prove it works>
- [ ] 2. …

## Verification
<How the whole plan is proven complete. Tests, not assertions.>

## Review
<Filled in after execution: what changed vs the plan, and why.>
```

## Do not start a plan when

- The component design does not exist at `design/<component>/design.md`.
- It depends on a **`PROPOSED`** decision that ratification could still change.
- It depends on an unresolved open item in `docs/design/DECISIONS.md` — check the "Blocks" column.
- It requires a **contract change** that has not been reviewed and merged — contracts are single-writer even in a
  monorepo (`design/common/contracts/README.md`).

Review a plan against [`../../../checklists/plan-review.md`](../../../checklists/plan-review.md) before execution.
