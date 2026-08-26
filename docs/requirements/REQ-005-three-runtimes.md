# REQ-005 — Support Docker, Kubernetes and bare-Linux deployments

| | |
|---|---|
| **Status** | ACCEPTED |
| **Source** | parent objectives (2026-06-17) |
| **Raised** | 2026-06-17 (backfilled 2026-08-10) |
| **Priority** | must |
| **Phase** | v1 |

## Requirement

Solutions must be manageable on Docker, Kubernetes and bare VM/systemd.

## Why

Customers span all three; a platform that covers one is a point solution.

## Acceptance

Cache is configured, restarted and monitored on all three runtimes.

## Satisfied by

`B/D5` · `architecture.md` §12 (topology matrix), §15 (v1 build order)

## Conflicts

⚠️ Kubernetes needs a **single declared config writer** (`SM-D25`) — unresolved with customers, open item 6. And `direct-poll` cannot reach pods, so K8s telemetry requires `host-local` or `federated`.
