# Workflow Orchestration — rationale and changelog

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

## Changelog

**2026-08-10 — v2.** Folded the outstanding clarifications into the block above, so there is one authoritative text
rather than a block plus a list of caveats.

| Change | Rationale |
|---|---|
| **New §2: the Superpowers skill pipeline**, with `executing-plans` on a lower-cost model | Design and planning need the strongest model; executing an already-written plan does not. Naming the skills stops the workflow being improvised. |
| **§3: "Option 2 is MANDATORY" → "ASK the developer, never assume"** | A blanket mandate invites violation on a 6-task job, and once ignored it loses authority everywhere. Making the *ask* mandatory is enforceable; the choice is the developer's, which is where the context actually lives. |
| **§5: lessons split repo-local vs cross-cutting** | A single per-repo `lessons.md` fragments the learning that matters most — the Spring Boot precedence trap affects backend *and* go-agent; the no-MI-development constraint affects three repos. |
| **Task Management: plan is authoritative; plan in `docs/plans/`, execution status in the GitHub Project** | Two artifacts, two purposes. The original wording put the plan in `todo.md`, conflating the reviewed contract with ephemeral progress. `todo.md` was removed in round 6 when tracking moved to the Project — a single tracked file is a merge-conflict magnet across a team. |
| **§§3–8 renumbered** (was §§2–7) | Consequence of inserting the skills section. Nothing was removed. |

**One unverified figure retained:** the *"~70% cheaper"* estimate in §3 is labelled as an estimate. It has not been
measured on this codebase and depends heavily on how much context a session accumulates. If it gets measured,
replace it with the real number.
