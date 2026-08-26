# REQ-004 — ~~The UI must drive deployment of artifacts into client environments~~

| | |
|---|---|
| **Status** | **LOCKED — proposed retirement by `SM-D2` (unratified)** |
| **Source** | parent objective #4 (2026-06-17) |
| **Raised** | 2026-06-17 (backfilled 2026-08-10) |
| **Priority** | must |
| **Phase** | n/a |

## Requirement

**RETIRED.** Originally: the console would push JARs/images into customer AWS/GCP/Azure/on-prem environments, Kong-control-plane style.

## Why

The original assumption was that SM must own the whole lifecycle to be useful. Enterprise reality is that customers deploy through their own CI/CD and will not surrender that.

## Acceptance

n/a — retired.

## Satisfied by

**Nothing. Retired by `SM-D2`** (customer CI/CD deploys artifacts; SM owns configuration only).

## Conflicts

⚠️ **This is the worked example of a retired requirement.** `SM-D2` is **PROPOSED**, so parent objective #4 remains formally **LOCKED** until ratification. Consequences of retirement: entitlement becomes declaration-only (no artifact-pull gate), `B/D7` pre-flight loses enforcement, and `B/D9`'s BOM becomes unexpressible.
