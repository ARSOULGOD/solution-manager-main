# Implementation plans

One folder per component. **The plan is always authoritative** — the GitHub Project tracks execution against it, and
when the two disagree the plan wins.

| | Location | Nature |
|---|---|---|
| **Approved plan** | `docs/plans/<component>/NNN-<slug>.md` | Reviewed, versioned, **authoritative** |
| **Execution status** | GitHub Project "Solution Manager" | Live progress, assignment, sequencing |

The repo owns *what will be built and why*; the Project owns *who is doing it and how far along*. Anything **decided**
while executing must land in `docs/design/DECISIONS.md` before the issue closes.

Template, execution route and model guidance:
[`../design/common/conventions/plan-template.md`](../design/common/conventions/plan-template.md).

## Do not start a plan when

- The component design does not exist in `../design/<component>/design.md`.
- It depends on a **`PROPOSED`** decision that ratification could still change — see `../design/DECISIONS.md`.
- It depends on an unresolved open item in `../design/DECISIONS.md` (check the "Blocks" column).
