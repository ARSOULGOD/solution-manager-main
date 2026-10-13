# REQ-007 — Metrics retained and queryable for six months

| | |
|---|---|
| **Status** | ACCEPTED. **Not met at the measured storage gate (2026-10-07): capacity and wide-query latency findings open; see Conflicts** |
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

**2026-10-07 — the storage gate (plan 011 P11-8.1) ran, at ONE WEEK of S-A, not the month (`OR-112` (3)); REQ-007 is NOT MET at the measured
gate topology.** Evidence, with every label: [`../design/backend/evidence/011-storage-gate.md`](../design/backend/evidence/011-storage-gate.md).

- **Measured (one week of S-A, 20.16 M rows, PostgreSQL 14 in a container on the dedicated SM test host):** 170.68 bytes per raw row with
  indexes (170.19 for the Step 0b day, 170.88 at a 5 s cadence), 247 bytes of WAL per row, 23.7 k rows/s DB-only load, rollups at 3 to 4 s per
  run over a 3 h late window, and an Operator's HTTP queries at 6.2 to 9.2 s p50 for 24 h raw over 1 to 200 instances, up to 18.2 s p50 (p95
  37.9 s, with `503`s) for 7 d raw over 200 instances (TUNNEL-DOMINATED: the application reached the database through an SSH tunnel, so these
  HTTP latencies are mostly the tunnel's round trip times the statements per query, not a measurement of SM co-located).
- **Extrapolated (labelled, never measured):** six months of raw at S-A is about **88.5 GB** (170.68 B x 518.4 M rows). The host had about 18 GB
  free: **six months of raw at S-A does not fit it as provisioned.** The month did not fit either by the plan's own projection (39.05 GB with
  WAL counted in full; the rows alone, 14.7 GB, would have).
- **Finding against the acceptance's "without degradation":** the HTTP query had a floor of about 6 s although the database answered one
  series over 24 h in 15.7 ms, so the floor is the gate's topology (the application on a laptop, the database through an SSH tunnel) times
  the number of SQL statements per query; and the 200-instance raw shapes are database-bound (5.47 s of database time to read all 1,000 series
  over 24 h) and reach the 30 s statement timeout as a `503` (inferred from the timings). The hourly tier answered a 30 d window in about 6.8 s at
  any instance count in the same topology. The window held 7 days, so no 30-day figure is a 30-day measurement.
- **Not proven:** the month and six months themselves; `IngestService` throughput beside the database (the one figure is tunnel-dominated);
  the rollup's host CPU (not sampled); latency with the application beside the database; and the **105 M-row export** (Step 6b not run: no
  tooling, and it does not fit the host or safely the local machine), so **"exportable" is wired and tested but not measured at scale**
  and `exportRange` stays "wired, not counted served".

**Before 2026-10-07 this was designed-but-unproven.** `SM-D26` makes six months an **enforced capacity prerequisite**, which is now a number
(88.5 GB for S-A, extrapolated) the owner must size a disk for or reduce; plain PostgreSQL has no columnar compression. Open item 5 in
`DECISIONS.md` is narrowed, not closed; the separate-database question (`M-Q19` (c)) is with the owner.
