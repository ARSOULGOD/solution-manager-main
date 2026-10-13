# Common — contracts, conventions, lessons

Anything shared across more than one component.

| Path | Purpose |
|---|---|
| [`contracts/`](contracts/) | Design of the cross-repo contracts: agent↔CP protocol, REST/OpenAPI surface, Solution Package schema, telemetry envelope |
| [`conventions/`](conventions/) | **Canonical** `CLAUDE.md` blocks every code repo must carry, the repo template, and the implementation-plan template |
| [`lessons.md`](lessons.md) | Cross-cutting lessons — patterns that affect more than one repo |

## Contracts are central and one-directional

Contracts have a **single writer**: this repo. Code repos consume them as **versioned, read-only dependencies**, and
a contract change **originates as a PR here** — never as a local edit, even when the need is discovered mid-build.
A pre-release artifact is published from the contract PR branch so the developer is never blocked on multi-party
review. Full governance in [`contracts/README.md`](contracts/README.md).

## Conventions live in the root `CLAUDE.md`

The two mandated blocks are **single-homed in the root [`CLAUDE.md`](../../../CLAUDE.md)** — one copy, no sync
machinery. [`conventions/`](conventions/) holds their **rationale and changelog**, which do not belong in a file every
session loads, plus the plan and component templates.

**Why no sync script:** one repo, one `CLAUDE.md`. A ~170-line guard protecting a copy that need not exist produced
false greens twice (see `L7` in [`lessons.md`](lessons.md)). When the monorepo is split, each repo copies the block
from `CLAUDE.md` and a drift check becomes worth building — not before.

## Lessons: which file

- **Repo-specific** pattern → `docs/tasks/lessons.md` in that repo.
- **Cross-cutting** — affects more than one repo, or is architectural/contract-level → [`lessons.md`](lessons.md)
  here, and raise it at the next design review.
