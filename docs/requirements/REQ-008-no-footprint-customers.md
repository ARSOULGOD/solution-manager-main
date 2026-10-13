# REQ-008 — Some customers will not host any footprint in their runtime

| | |
|---|---|
| **Status** | ACCEPTED |
| **Source** | architect group, 2026-07 |
| **Raised** | 2026-06-17 (backfilled 2026-08-10) |
| **Priority** | must |
| **Phase** | v1 |

## Requirement

The platform must be usable by customers who refuse to run any Solace-provided process inside their runtime.

## Why

Named customers refuse a footprint. Without an agentless path they cannot be served at all.

## Acceptance

Such a customer can configure and monitor a solution with no SM process running in their runtime.

## Satisfied by

`SM-D12`/`SM-D23` (`control_locus = dp-remote`) · `SM-D11` (credential handling) · `architecture.md` §4.1

## Conflicts

⚠️ **Costs the platform-wide security posture.** `dp-remote` needs inbound reach and holds host credentials, reversing locked `D2` and `§17` — outbound-only survives only for `host-local`. Also: **no per-pod Kubernetes metrics** for such a customer without `federated`.
