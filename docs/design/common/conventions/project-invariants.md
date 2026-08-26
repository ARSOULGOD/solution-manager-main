# Project Invariants — rationale and changelog

> **The block text itself lives in the root [`CLAUDE.md`](../../../../CLAUDE.md)** and is authoritative there.
> **One copy, no sync machinery.** This file holds only the reasoning and the change history, which do not belong in
> an instruction file that every session loads.
>
> **Why no sync script:** one repo, one `CLAUDE.md`. A ~170-line guard protecting a copy that need not exist was a
> source of false greens twice over (see `L7` in `../lessons.md`). When the monorepo is split, each new repo copies
> the block from `CLAUDE.md` and a drift check becomes worth building.

## Editing the block

1. Edit the block in the root `CLAUDE.md`.
2. Record **what changed and why** in the changelog below — the block states rules, this file explains them.
3. If the change affects how a component is built, also check `docs/checklists/`.


---

## Notes for maintainers

- **I1** is the constraint most likely to be violated by a well-meaning "just add an endpoint to the MI" proposal.
- **I2** exists because three separate factual errors reached a design document that had already passed
  self-review. The fix is procedural, not intellectual.
- **I5**, **I6** and **I7** are all traceable to specific audit findings; see the hardening backlog in
  `docs/design/DECISIONS.md`.
- When adding an invariant: it must be checkable by a reviewer in under a minute, and it must have a concrete
  failure it prevents. Vague principles belong in the workflow block, not here.
