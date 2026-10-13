# REQ-003 — Deployable on-prem at the client site and as multi-organization SaaS

| | |
|---|---|
| **Status** | ACCEPTED |
| **Source** | parent objectives (2026-06-17) |
| **Raised** | 2026-06-17 (backfilled 2026-08-10) |
| **Priority** | must |
| **Phase** | on-prem v1; SaaS deferred |

## Requirement

One codebase must run as a customer-hosted single-organization deployment (including air-gapped) and as
Solace-operated multi-organization SaaS.

## Why

Compliance-driven customers will not accept a hosted control plane; the commercial model needs SaaS. Retrofitting
an Organization isolation boundary is the expensive rewrite the File Mesh backend demonstrates.

## Acceptance

The same build runs in both shapes; the Organization boundary is present in the schema from the first commit even
when a deployment contains one Organization (`D20` 🔒).

## Satisfied by

`D9` · `B/D17` (editions) · `SM-D1` (plane split + seams) · `architecture.md` §3.1, §3.2

## Conflicts

None, but the CP/DP identity protocol across the seam is unspecified — open item 5.
