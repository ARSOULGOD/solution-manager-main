# REQ-001 — Onboarding a solution must not require new console code

| | |
|---|---|
| **Status** | DESIGNED |
| **Source** | parent objectives (2026-06-17) |
| **Raised** | 2026-06-17 (backfilled 2026-08-10) |
| **Priority** | must |
| **Phase** | v1 |

## Requirement

A new Solace solution must be onboardable by publishing a versioned package — schema, telemetry contract, UI enrichment — without writing bespoke React screens or agent collectors.

## Why

Bespoke console code per solution is what makes adding a solution a project rather than a configuration task. It is the single biggest cost driver the platform exists to remove.

## Acceptance

Publishing a package for a solution not previously known to the platform renders its Designer, Governance and Monitoring screens with no code change and no redeploy of the console.

## Satisfied by

`D1` (hybrid platform) · `SM-D19` (Solution Package) · `SM-D20` (schema source adapters) · `architecture.md` §5.1, §14

## Conflicts

⚠️ **`D3`'s premise is false** — Cache ships no build-generated config metadata, so the schema cannot be generated. `SM-D20` replaces generation with declared source adapters. Requirement still satisfiable; mechanism changed.
