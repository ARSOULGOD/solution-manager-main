# REQ-002 — Support File Mesh, Cache and DB MI

| | |
|---|---|
| **Status** | ACCEPTED |
| **Source** | parent objectives (2026-06-17) |
| **Raised** | 2026-06-17 (backfilled 2026-08-10) |
| **Priority** | must |
| **Phase** | v1 (Cache) / phase 2 (File Mesh) / phase 3 (DB MI) |

## Requirement

The platform must onboard all three existing Solace solutions.

## Why

These are the solutions that exist today. A platform that cannot hold them is not a platform.

## Acceptance

Each of the three is onboarded and operable through the console, with its own config, health and metrics.

## Satisfied by

`B/D5` (v1 = Cache across all runtimes) · phasing in `DECISIONS.md` §9

## Conflicts

None. Note the three differ in exactly the dimensions the platform abstracts (see `architecture.md` §2) — Cache polls and has an actuator, File Mesh pushes and has none.
