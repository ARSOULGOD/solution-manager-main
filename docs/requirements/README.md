# Requirements

Every requirement that comes in gets a file here, an ID, and a trace to whatever satisfies it.

**Why the traceability matters on this project specifically:** a stated objective has already been *retired* —
the original *"the UI must drive deployment of artifacts into client environments"* was killed by `SM-D2`. Without
a trace, `requirements/` will quietly contradict `design/DECISIONS.md` and nobody will notice which one is current.

## Adding one

1. Next free ID. `REQ-NNN-<short-slug>.md`. **IDs are never reused or renumbered.**
2. Fill the template below. Leave `Satisfied by` empty until a design decision actually covers it.
3. Add a row to [`traceability.md`](traceability.md).
4. If it conflicts with an existing decision, **say so in `Conflicts` and raise it** — do not quietly assume the
   requirement wins.

## Status values

| Status | Meaning |
|---|---|
| `PROPOSED` | Captured, not yet accepted into scope |
| `ACCEPTED` | In scope, awaiting design |
| `DESIGNED` | A design decision covers it — `Satisfied by` is populated |
| `IMPLEMENTED` | Built and verified |
| `DEFERRED` | Accepted but explicitly out of the current phase; say which phase |
| `RETIRED` | No longer required. **Record why and what replaced it** — see `REQ-004` for the worked example |

## Template

```markdown
# REQ-NNN — <one-line requirement>

| | |
|---|---|
| **Status** | PROPOSED |
| **Source** | <person / meeting / customer / deck slide — be specific enough to go back and ask> |
| **Raised** | YYYY-MM-DD |
| **Priority** | must / should / could |
| **Phase** | v1 / phase 2 / phase 3 / unscheduled |

## Requirement

<What is needed, in the requester's terms. Avoid solutioning here.>

## Why

<The underlying need. This is what survives when the stated solution turns out to be wrong.>

## Acceptance

<How we would know it is satisfied. Concrete and checkable.>

## Satisfied by

<Decision IDs and/or design sections. Empty until designed.>

## Conflicts

<Any decision or requirement this contradicts, with the ID. "None known" is a valid answer, but check first.>

## Notes

<Constraints, open questions, links.>
```

## Seed requirements to backfill

The design predates this folder, so several requirements are currently implicit in `design/DECISIONS.md` and should
be written up so the trace runs both ways:

| Suggested ID | Requirement | Source |
|---|---|---|
| `REQ-001` | Unified configuration layer — onboarding a solution must not require new console code | parent objectives |
| `REQ-002` | Support File Mesh, Cache and DB MI | parent objectives |
| `REQ-003` | Deployable on-prem at the client site **and** as multi-organization SaaS | parent objectives |
| `REQ-004` | ~~UI must drive artifact deployment into client environments~~ | parent objective #4 — **RETIRED** by `SM-D2`; the worked example for a retired requirement |
| `REQ-005` | Support Docker, Kubernetes and bare-Linux | parent objectives |
| `REQ-006` | RBAC for access and control | parent objectives |
| `REQ-007` | Metrics retained and queryable for 6 months | `SM-D8` — note this is a **minimum**, not a maximum, and is gated on the sizing model |
| `REQ-008` | Some customers will not host any footprint in their runtime | drives the `dp-remote` locus and the whole two-axis Target model |
| `REQ-009` | No development work may be required from the MI/MDK team | hard constraint `HC1` / `SM-D16` |
| `REQ-010` | Per-instance shared dashboards, line/gauge/single-stat widgets, one-metric multi-instance comparison | owner ruling `OR-78` (M-Q23), 2026-09-30 — raised; see [`REQ-010`](REQ-010-per-instance-dashboards-and-comparison.md) |
| `REQ-012` | Live console state with bounded, honest staleness; degradation stated, never silent | backend architecture direction, 2026-08-27 — `REQ-010`/`REQ-011` stay reserved for the requests recorded in `traceability.md` |
| `REQ-013` | Fleet apply: one change to many instances of a solution, authorized and audited per instance | owner ruling `OR-47`, 2026-09-28 — **no decision yet; needs design** |
| `REQ-014` | Every third-party library is open source and free for commercial use (project-wide) | project owner, 2026-09-29 — invariant `I8`; enforcement gate to be built |
