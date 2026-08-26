# REQ-007 — Metrics retained and queryable for six months

| | |
|---|---|
| **Status** | ACCEPTED |
| **Source** | `SM-D8` / stakeholder requirement |
| **Raised** | 2026-06-17 (backfilled 2026-08-10) |
| **Priority** | must |
| **Phase** | v1 |

## Requirement

Six months of solution metrics must be retained and queryable, with raw samples available for the full window.

## Why

Customers need to see trends and investigate incidents across quarters, and to produce raw records on request.

## Acceptance

Six months of raw samples are queryable and exportable; graphs across the full window render without degradation as history accumulates.

## Satisfied by

`SM-D8` (storage, rollups, archive) · `SM-D26` (corrections) · `architecture.md` §8

## Conflicts

⚠️ **Conditionally satisfied.** `SM-D26` makes six months an **enforced capacity prerequisite** — but the sizing model has **not been benchmarked** (open item 3), so this requirement is designed-but-unproven. Plain PostgreSQL has no columnar compression.
