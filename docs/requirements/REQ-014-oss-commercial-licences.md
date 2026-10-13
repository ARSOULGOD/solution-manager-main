# REQ-014 — Every third-party library is open source and free for commercial use

| | |
|---|---|
| **Status** | ACCEPTED |
| **Source** | Project owner, 2026-09-29 ("one of the requirements for this project is to use libraries that are open source and free to use for commercial licences") |
| **Raised** | 2026-09-29 |
| **Priority** | must |
| **Phase** | v1, and every phase after — project-wide (backend, frontend, go-agent, contracts tooling, SM packaging) |

## Requirement

Every third-party dependency Solution Manager ships or builds with — direct **and** transitive, in every component —
is open source under a licence that permits commercial use without a fee.

## Why

SM is a commercial Solace product. A dependency under a copyleft, source-available, or commercial-only licence can
force disclosure, a fee, or a rewrite late in the release. The project has already been burned once by a licence
assumed from memory (TimescaleDB's TSL — see the memory note and `I2`).

## Acceptance

- **Allowed without further review (allow-list confirmed by the owner, 2026-09-29):** Apache-2.0, MIT, BSD-2-Clause,
  BSD-3-Clause, ISC, the PostgreSQL Licence.
- **Needs an explicit, recorded owner decision before use:** weak copyleft (EPL-2.0, MPL-2.0, LGPL-*) and anything
  not on the allow-list.
- **Refused:** GPL/AGPL, SSPL, BSL/BUSL, Elastic/"source-available" and commercial-only licences.
- **First-party Solace libraries** (e.g. JCSMP, the Solace WebApp SDK) are Solace's own and permitted, named
  explicitly as the exception — they are not open source.
- **Verified, not recalled:** every new dependency's licence is checked from its own repository or distribution
  (WebFetch/`I2`) at the version pinned, including its transitive dependencies, and recorded where the dependency is
  introduced.
- **Enforced:** a build gate fails when any resolved dependency's licence is outside the allow-list and not
  individually approved (backend: a Maven licence check; frontend: an npm licence check). Until the gate exists, the
  licence is recorded in the change that adds the dependency.

## Satisfied by

Invariant `I8` (root `CLAUDE.md`). Enforcement gate: to be built (plan 010 task; frontend separately). An audit of the
existing dependency tree is owed.

## Conflicts

None known. Verified 2026-09-29 for plan 010's JSON-Schema library: `com.networknt:json-schema-validator` and its
`com.ethlo.time:itu` dependency are both Apache-2.0 (licence files in their GitHub repositories).
