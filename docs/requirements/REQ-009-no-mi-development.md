# REQ-009 — No development work may be required from the MI/MDK team

| | |
|---|---|
| **Status** | ACCEPTED |
| **Source** | architect group — restated repeatedly |
| **Raised** | 2026-06-17 (backfilled 2026-08-10) |
| **Priority** | must |
| **Phase** | all phases |

## Requirement

The platform may never require code changes, build changes, new endpoints or new telemetry emission from the managed integrations.

## Why

MI development depends on the MDK, owned by a different Solace team whose roadmap and approach SM does not control. A design that needs their cooperation is a design that does not ship.

## Acceptance

Every capability is satisfiable by something SM controls (config file contents, launch configuration, runtime APIs) or degrades gracefully with the loss stated.

## Satisfied by

**`HC1`** · `SM-D16` (audit table) · `project-invariants.md` `I1`

## Conflicts

None — this is a hard constraint, not a tradeable requirement. It is what **invalidated `D3`** (`SM-D20`) and what bounds drift detection (`SM-D22`).
