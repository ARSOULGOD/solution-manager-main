# The monitoring storage gate on the reference host (plan 011 P11-8.1: M-Q8, MF17, MF5, REQ-007)

**Status: RUN 2026-10-06/07 AT ONE WEEK OF S-A, NOT ONE MONTH (`OR-112` (3)); STEPS 6b AND 6c NOT MEASURED; REQ-007 NOT MET AT THE
MEASURED GATE TOPOLOGY.** The integrator ran the gate on the dedicated SM test host (`OR-100`: PostgreSQL 14 in a container on the E2E
host; `OR-110`: that host is a dedicated SM test host). The month did not fit the host's disk (Step 0b), the owner ruled *"Do it for 1
week, we will extrapolate accordingly"* (`OR-112` (3), 2026-10-07), and every month and six-month figure below is **EXTRAPOLATED from the
week**, labelled so. The application side of Step 3 ran **on the integrator's laptop with the database on the host, reached through an SSH
port-forward**, which is not the topology an install has (`OR-76`: *"our Db will be lying with our application"*); the latencies carry that.
The raw evidence lines (free of host details) are the integrator's `redacted-p81-step0b-evidence.log`, `redacted-p81-week-evidence.log`
and `redacted-p81-bench-results.properties`, kept outside the repo; this file carries every figure that matters from them.

How the labels are used, because the verdict depends on them:

- **MEASURED**: a figure the run observed on this host, from this database, at the scale the run's first line states.
- **EXTRAPOLATED**: arithmetic from a measured figure (the week times 4.3, the raw tier times six). Never a measurement, and never
  written without the word.
- **SCALED**: a measurement taken at a smaller scale than the plan's, carried to the plan's. Labelled, never silent. **Every week figure
  here is SCALED against the plan's month: 7 days of S-A loaded, not 30.**
- **TUNNEL-DOMINATED**: measured, but the number is mostly the network round trip of the gate's topology and says little about SM.
- **SUPPLIED**: a figure the integrator or an owner ruling stated, not one this run read (a round number is not a `df` line).
- **ESTIMATE**: arithmetic that mixes a supplied or measured figure with an assumption; a figure labelled ESTIMATE is never a measurement.
- **NOT MEASURED**: said so, with the reason. A blank is not an answer.

## How to run it (read this first)

The tooling is `backend/launcher/scripts/run-storage-gate.sh` (header: every variable, what it writes, how the SSH port-forward
is made). It runs `MetricsStorageBenchmarkIT` (dp-core: Steps 0b, 1, 2, 2b, 5, 6a) and `MetricsGateBenchmarkIT` (launcher: Step 3).
Passwords are read from the environment only and are never printed. The application's master key for `gate` is derived from the admin password and the database's own identity, so a re-made SSH port-forward does not change it; keep the admin password for the database's life. The evidence lines go to
`backend/target/storage-gate/storage-gate-evidence.log`. Steps 6b and 6c (the 105 M-row export stream and the default re-check) need
plan 011 P11-7.2's export route and **are not in this tooling**; neither was run (see their sections).

| Order | Command | Stop and read before the next one |
|---|---|---|
| 0 | the owner's yes (Step 0), then the container and the SSH port-forward (the plan's Results for P11-8.1 has the exact lines) | free disk before |
| 1 | `run-storage-gate.sh prepare` | `rawPartitions=4` (5 on the second pass, see the run record) |
| 2 | `run-storage-gate.sh step 0b` | **the VERDICT line and `monthProjectionBytes`: if the month does not fit, STOP** |
| 3 | `run-storage-gate.sh step 1`, then `step 2`, `step 2b`, `step 5` | `FINDING` lines |
| 4 | `run-storage-gate.sh gate-prepare`, then `gate` | `operatorSeesTheLoadedEnvironment=true` |
| 5 | `run-storage-gate.sh step 6a` | the 6a VERDICT decides where Step 6b runs |

⚠️ **Re-run `prepare` after any change that adds a migration.** The benchmark database predated `sm_metrics` `V003` (`max_gap_seconds`,
the Fable MON-01 fix) and the harness does not check the database's migration level against the build: Step 2b failed, and the first
Step 3 pass answered `500` on every 30 d hourly shape, for that one reason. After `prepare` re-ran (the second pass), both worked.

## Run record

| Item | Value |
|---|---|
| Date (UTC) | Step 0b: 2026-10-06, 19:00-19:01. Week load, Steps 1, 2, 5, 6a: 2026-10-07, 05:53-06:14. Step 3, first pass (before `V003`): 06:17-06:57. `prepare` re-run and Step 2b: 06:58-07:01. Step 3, second pass (the figures below): 07:03-07:45. (UTC, from the evidence lines.) |
| Host class and RAM | The dedicated SM test host of `OR-76`/`OR-100` (a t4g.medium class host: 2 burstable vCPU aarch64, about 4 GB, shared with the Cache). **Not re-measured by the lane that wrote this file**: the evidence lines do not carry the host's CPU or RAM, and `OR-100` asked for them to be reported if they differ. NOT SUPPLIED |
| PostgreSQL image and version | `postgres:14` in a container (`OR-100`). The minor version was not supplied to this lane. NOT RECORDED |
| Container limits and server settings | The plan's recommended line was 1536 MB limit and swap, `shared_buffers=256MB`, `work_mem=8MB`, `maintenance_work_mem=96MB`, `max_connections=30`. Whether the integrator changed any figure was not reported to this lane. NOT CONFIRMED |
| Scale (first evidence line of each step) | Step 0b: `instances=200 metrics=5 intervalSeconds=30 days=30 rowsPerMonth=86400000` (S-A, the plan's real month), one day loaded. Steps 1 to 6a and Step 3: `SCALED-DOWN, NOT S-A instances=200 metrics=5 intervalSeconds=30 days=7 rowsPerMonth=20160000` |
| Free disk before the run | 18,000,000,000 B (the figure supplied to Step 0b as `SM_BENCH_FREE_DISK_BYTES`; a round number, not a printed `df` line) |
| Free disk after the run | NOT SUPPLIED. Step 6a's estimate of the free disk *after the benchmark data is dropped* is 21,518,987,264 B (ESTIMATE: free disk then plus the bytes the benchmark holds) |
| Docker state before / after | NOT SUPPLIED / NOT SUPPLIED |
| Scaled option taken (days loaded) | **7 days** (`OR-112` (3)). Step 0b's own suggestion was 12 days; the owner chose one week |

## Step 0: the owner's confirmation

| Item | Value |
|---|---|
| Owner confirmed PostgreSQL 14 on the host (M-Q8: *"a change on the owner's host"*) | Yes: `OR-100` (2026-10-05, *"Yes, container on this host (Recommended)"*); `OR-110` (2026-10-06): the host is a dedicated SM test host |
| Date and wording | `OR-100`, 2026-10-05. The scaled option: `OR-112` (3), 2026-10-07, *"Do it for 1 week, we will extrapolate accordingly"* |

## Step 0b: one day first, projected (MF17)

Run at S-A's real settings (`days=30`), one day loaded, 2026-10-06.

| Figure | Value | Label |
|---|---|---|
| Rows in the day | 2,880,000 | MEASURED |
| Day's bytes with indexes (`pg_total_relation_size` delta) | 490,135,552 | MEASURED |
| Heap bytes / index bytes | 234,201,088 / 255,844,352 | MEASURED |
| Bytes per row | **170.19** | MEASURED |
| WAL for the day (`pg_wal_lsn_diff`) | 703,993,080 | MEASURED |
| WAL per row | 244.44 | MEASURED |
| Load seconds and rows per second (DB only) | 69.65 s, 41,347.64 rows/s | MEASURED |
| Month projection (30 x the day's bytes + 30 x its WAL + the 3 GiB floor) | **39,045,084,432** B (14.70 GB rows + 21.12 GB WAL counted in full + 3.22 GB floor) | EXTRAPOLATED |
| Free disk | 18,000,000,000 B (as supplied) | SUPPLIED (a round figure, not a `df` reading; `OR-76` states 18 GB free) |
| Verdict (fits / does not fit) | **DOES NOT FIT** (39.05 GB > 18.00 GB). The tool's note: 12 whole days would fit | |
| If it does not fit: the owner's answer on the scaled option | One week, extrapolated and labelled (`OR-112` (3)) | |

The month's rows alone, 86.4 M x 170.19 B, are 14.70 GB: they would have fitted the 15 GB usable (18 GB minus the floor) by a hair; it
was the WAL counted in full that made the projection fail, and the tool says that WAL is recycled by a checkpoint. The projection is
therefore conservative; the owner's ruling ended the question and the month was not tried.

## Step 1: the week, bytes per row, WAL, vacuum (fills MF17)

| Figure | Value | Label |
|---|---|---|
| Rows loaded this run / already loaded / total | 20,160,000 / 0 / 20,160,000 (7 days x 2.88 M) | MEASURED (SCALED against the 86.4 M month) |
| Raw tier rows, all tenants | 23,040,000: the week plus exactly one S-A day (2.88 M), which is what the earlier Step 0b load would have left (not separately confirmed); bytes per row below is over all 23.04 M | MEASURED |
| Load seconds and rows per second (DB only) | 850.00 s, **23,717.58 rows/s** | MEASURED |
| Rate by day (the run's cumulative `elapsedSeconds`: 66, 140, 238, 358, 502, 665, 849) | 43.6 k, 38.9 k, 29.4 k, 24.0 k, 20.0 k, 17.7 k, **15.7 k rows/s**: the load slowed 2.8x over the week (a day took 66 s, then 184 s). Not diagnosed; consistent with the indexes outgrowing the container's cache, which the run did not test | MEASURED (arithmetic on the progress lines) |
| WAL bytes and WAL per row | 4,982,281,056 B; **247.14 B/row** | MEASURED |
| `VACUUM` seconds per partition (closed months `FREEZE`, the open month `ANALYZE`) | `metrics_raw_2026_09` 12.93 (FREEZE); `metrics_raw_2026_10` 5.57; `2026_11`, `2026_12`, `2027_01` 1.87 each (ANALYZE, empty); **total 24.12 s** | MEASURED |
| Raw tier bytes with indexes (heap / index) | 3,932,495,872 (1,873,625,088 / 2,058,297,344) | MEASURED |
| Bytes per row (heap and indexes) | **170.68** (heap 81.32 + index 89.34) | MEASURED |
| `series` + `series_segment` bytes | 1,581,056 | MEASURED |
| Database size after the load | 3,945,426,267 B | MEASURED |

The Step 0b day (170.19 B/row) and the week (170.68 B/row) agree within 0.3 %; the index is 52 % of a row.

## Step 2: insert rate

| Figure | Value | Label |
|---|---|---|
| Through `IngestService`, from the integrator's laptop over the SSH tunnel: samples per second, batches per second | 0.86 samples/s, 0.17 batches/s (21 batches, 105 samples committed in 121.54 s; `{Committed=21}`, none refused or held) | MEASURED, **TUNNEL-DOMINATED** |
| Per-batch latency p50 / p95 | 5,821.32 ms / 5,917.65 ms (a batch is one placement's 5 samples) | MEASURED, **TUNNEL-DOMINATED** |
| Server-side `INSERT ... SELECT`: rows per second | 39,322.93 rows/s (2,880,000 rows in 73.24 s) | MEASURED (DB only) |
| `IngestService` throughput with the application beside the database | **NOT MEASURED**: this run's only `IngestService` figure includes the tunnel; a batch took about 5.8 s because it commits through several SQL round trips, so the figure is a measure of the link, not of SM. S-A needs 200 placements x one batch per 30 s (about 6.7 batches/s, 33 samples/s); nothing here shows whether `IngestService` meets that co-located | NOT MEASURED |

## Step 2b: rollup cost

Run after `prepare` re-ran (the first attempt failed on `sm_metrics` `V003` missing).

| Figure | Value | Label |
|---|---|---|
| One-time backfill of the week: seconds, raw rows read, hourly rows written | 141.72 s; 20,160,000 raw rows read; 168,000 hourly and 7,000 daily rows written (hourly rows before: 0) | MEASURED (chunked in 24 h statements; not the steady state) |
| Steady state at the default 5-minute cadence: seconds per run, raw rows read per run, duty cycle | runs of 3.96, 3.99 and 3.16 s (mean 3.70 s); 360,000 raw rows read each; duty cycle 0.01 | MEASURED |
| Steady state at a 10 s cadence: seconds per run, raw rows read per run, duty cycle | runs of 3.20, 2.97 and 3.12 s (mean 3.10 s); 360,000 raw rows read each; duty cycle 0.31 | MEASURED |
| Host CPU during the runs (mean and max busy %, steal %) | **NOT MEASURED**: the CPU sampler returned no samples (`hostCpuSamples=0`, the figures print `NaN`) | NOT MEASURED |
| Hourly tier size | 35,094,528 B for 168,000 rows (208.9 B/row). Six months at S-A is 4.32 M rows, about **0.90 GB** | MEASURED; six months EXTRAPOLATED |
| Estimated sustained CPU against the burst baseline | **cannot be stated** without the CPU samples | NOT MEASURED |
| Finding: a run longer than its interval, or sustained CPU above baseline | No run exceeded its interval (3.7 s of 300 s, and 3.1 s of 10 s). The CPU half of the plan's test could not be answered, so the cadence is **not** put to the owner on this evidence and is **not** declared safe either: the host is a burstable 2-vCPU machine and a 3 to 4 s query every 5 minutes was never watched | |

## Step 3: query timings, as an Operator over HTTP

Principal: `bench-operator`, role Operator, bound at the loaded instances' environment (`operatorSeesTheLoadedEnvironment=true`). 20 timed
runs after one cold request, 150 ms apart. **Topology: the application ran on the integrator's laptop and reached the database on the
host through the SSH tunnel.** Every figure below is **TUNNEL-DOMINATED** at the low end. **The window held 7 days of data, so the "30 d"
shapes read 7 days, not 30** (the 30 d hourly shape read 168 hourly rows per series; a true 30 d raw read would scan about 4.3 times the
rows). The second pass (the one below) ran after `V003` was applied; the first pass is further down.

| Shape | Instances | p50 ms | p95 ms | Cold ms | Series | Points | Tier served | Statuses |
|---|---|---|---|---|---|---|---|---|
| 24 h raw | 1 | 6,202 | 6,791 | 6,526 | 1 | 702 | raw | 200 x 21 |
| 24 h raw | 10 | 6,310 | 6,852 | 7,152 | 1 | 696 | raw | 200 x 21 |
| 24 h raw | 200 | 9,190 | 11,233 | 9,178 | 1 | 689 | raw | 200 x 21 |
| 7 d raw | 1 | 6,184 | 6,782 | 10,881 | 1 | 957 | raw | 200 x 21 |
| 7 d raw | 10 | 6,652 | 7,285 | 13,676 | 1 | 956 | raw | 200 x 21 |
| 7 d raw | 200 | **18,175** | **37,888** | 31,088 | 1 | 954 | raw | 200 x 20, **503 x 1** (19 timed) |
| 30 d hourly | 1 | 6,738 | 7,240 | 7,241 | 1 | 168 | hourly | 200 x 21 |
| 30 d hourly | 10 | 6,331 | 7,251 | 6,074 | 1 | 168 | hourly | 200 x 21 |
| 30 d hourly | 200 | 6,799 | 7,269 | 7,790 | 1 | 168 | hourly | 200 x 21 |
| 30 d forced raw | 1 | 8,119 | 8,909 | 9,101 | 1 | 235 | raw | 200 x 21 |
| 30 d forced raw | 10 | 8,771 | 9,139 | 9,860 | 1 | 235 | raw | 200 x 21 |
| 30 d forced raw | 200 | **15,597** | **28,226** | 38,726 | 1 | 235 | raw | 200 x 19, **503 x 2** (18 timed) |

(`Series` is 1 in every row because the shape asks for one aggregated line over the chosen instances. A `503` is an answer, not a
timing: it is left out of the p50 and p95 and counted in `Statuses`.)

**First pass, superseded (before `V003` was applied).** Every 30 d hourly shape answered `500` x 21 and nothing was timed. The other shapes
agree with the second pass within run-to-run noise: p50 ms for 24 h raw 5,868 / 6,140 / 8,672, 7 d raw 6,293 / 6,749 / 17,243 (7 d raw at 200
instances answered `503` once), 30 d forced raw 8,138 / 8,396 / 20,251 (for 1 / 10 / 200 instances).

**The 503s.** They sit on exactly the two 200-instance raw shapes whose cold request ran past 30 s (30.3 s and 31.1 s on the 7 d raw shape in
the two passes, 38.7 s on the forced raw shape in the second). The query's database `statement_timeout` is 30 s (`M-Q22` (1)), and the
route's `503` is "the metrics query side could not answer now". That the `503`s are the statement timeout is **an inference from those
timings; the run did not read the server's log to confirm it.**

### DB-side timings, measured on the host with `psql` (the integrator, not part of the tooling)

| Statement | Time | Label |
|---|---|---|
| One series, 24 h raw | 15.7 ms | MEASURED |
| One series, 7 d raw | about 1 ms | MEASURED (cached; a repeat, not a cold read) |
| All 1,000 series, 24 h raw (2.88 M rows) | 5.47 s | MEASURED |

### What these numbers say (findings against REQ-007, not a pass)

1. **The roughly 6 s floor is not the database.** One series over 24 h is 15.7 ms of database time, yet the one-instance HTTP request takes
   6.2 s. The application issues many SQL statements per HTTP query and the gate's application sat on the far side of an SSH tunnel, so
   each statement paid a network round trip. The floor belongs to the **gate's topology** and to the **number of statements per query**.
   What this run cannot tell is how much of the 6 s a co-located install would keep: that needs the same gate with the application beside
   the database, which was not run. **Finding for the owner: reduce the statements per HTTP query** (it is the multiplier that turns a
   round trip into seconds), **and measure co-located.**
2. **The 200-instance raw shapes are database-bound.** Reading all 1,000 series over 24 h is 5.47 s of database time alone, and 7 d is
   more, which matches 18 s p50 and a p95 past the 30 s statement timeout. The tier that avoids it is already there: the 30 d hourly shape
   at 200 instances answered in 6.8 s, no slower than at one instance. **Finding for the owner: route raw-heavy, wide queries to the
   hourly tier** (or refuse a wide forced-raw read with a clear `422`, as the series cap does), instead of letting them reach the
   statement timeout as a `503`.
3. **REQ-007's acceptance** (*"graphs across the full window render without degradation as history accumulates"*) names no latency, and
   `M-Q22` (1)'s 30 s is a ceiling, not a target. By any reading a user would call "without degradation", a 6 to 9 s dashboard query is
   degraded, and a 200-instance raw query that reaches `503` is a failure. Both are at a topology that is not the production one.
   **The honest status is: not met at the measured gate topology, and not proven either way for a co-located install.**

## Step 4: six months (arithmetic, labelled)

| Figure | Value | Label |
|---|---|---|
| Raw tier at S-A, six 30-day months | 170.68 B/row x 518.4 M rows = **88,481,157,120 B (88.5 GB)**. The month alone is 14.75 GB (86.4 M rows) | EXTRAPOLATED (the measured bytes per row x S-A's rows x 6). The evidence line printed "432 M rows/month"; the figure is right and the label was wrong (86.4 M x 6 = 518.4 M rows), fixed in the tooling |
| That against the host | **4.9 times the 18 GB the host had free**; the WAL of a long retention is on top. The raw tier of six months of S-A **cannot be held on this host as provisioned** | EXTRAPOLATED, against the free-disk figure supplied |
| Hourly and daily tiers at six months | Hourly about 0.90 GB (35.09 MB for the week x 180/7); the daily tier's size was not measured (7,000 rows for the week; 180,000 rows at six months, arithmetic only) | EXTRAPOLATED (hourly); NOT MEASURED (daily size) |
| Query times at six months | **Not given as numbers.** What can be reasoned, and is not a measurement: a 24 h or 7 d read touches the same rows however much history there is (the key is `(tenant, series, ts)` and the partitions prune), so those times would not grow with six months of history; a 30 d forced raw read touches the rows of its window, about 4.3 times what the week holds, so its database time grows about that much | EXTRAPOLATED (reasoning) |
| S-B (S-A at 15 s, about 1.05 B raw rows in six months) | 2 x 518.4 M = 1.0368 B rows x 170.68 B = **177.0 GB** (the plan's "about 1.05 B" is rounded; 179.2 GB at 1.05 B) | arithmetic from the measured bytes per row, not exercised |
| S-C (100 instances x 100 series x 15 s, about 10.5 x 10^9 rows) | 10,000 series x 5,760 per day x 180 days = 10.368 B rows x 170.68 B = **1,769.6 GB (1.77 TB)** | arithmetic from the measured bytes per row, not exercised |

## Step 5: the scaled 5 s point (for the agent plan, M-Q8)

| Figure | Value | Label |
|---|---|---|
| Series, interval, rows in the day | 200 series, 5 s, 3,456,000 rows | MEASURED |
| Day's bytes with indexes | 590,553,088 | MEASURED |
| Bytes per row and WAL per row | 170.88 / 240.82 | MEASURED |
| Insert rate (DB only) | 42,403.92 rows/s | MEASURED |
| Six months at this shape | 106,299,555,840 B (106.3 GB), raw tier only, 200 series | SCALED (one measured day x 180) |

Bytes per row is the same at 5 s as at 30 s (170.88 against 170.68): the row, not the cadence, sets the size.

## Step 6a: disk arithmetic for the 105 M-row stream

| Figure | Value | Label |
|---|---|---|
| Required: 105 M x 170.68 B + 105 M x 247.14 B + the floor | 17,921,400,000 + 25,949,700,000 + 3,221,225,472 = **47,092,325,472 B (47.1 GB)** (WAL counted in full, as the plan says) | EXTRAPOLATED |
| Free disk after the benchmark month is dropped | 21,518,987,264 B | ESTIMATE (free disk then plus what the benchmark data holds; not a `df` reading) |
| Verdict and where Step 6b runs | **DOES NOT FIT** the host; the plan says Step 6b then runs locally in Testcontainers, and the EC2 duration is reported as not measured | |

## Steps 6b, 6c, 6d

| Item | Value |
|---|---|
| 6b: all 105 M rows streamed through `GET /export/range`: heap high-water mark, duration, rows per second, ingest latency before / during / after | **NOT MEASURED, and not attempted.** (1) The tooling does not exist: `MetricsStorageBenchmarkIT` and `MetricsGateBenchmarkIT` stop at Step 6a, and no test streams 105 M rows through the route into a counting sink with a heap sampler and a concurrent ingest producer. (2) Locally it does not fit safely: the Docker Desktop VM reports 403 GB free, but its disk is a sparse image on the Mac's volume, which had **51 GiB free of 460 GiB (88 % used)**, and the plan's own requirement is 47.1 GB (an upper bound: WAL counted in full). It would leave the owner's machine about 7 GB from full, and the image does not shrink afterwards. No disk was partially filled. The local half of MF5 (flat heap at 5 M and 20 M rows) stays as P11-6.2 recorded it |
| 6c: default re-check (`422 EXPORT_TOO_LARGE` before any byte, one export per organization, 4 permits) | **NOT RUN as Step 6c** (no connection to the host from the lane that wrote this file; no S-M series loaded). `ExportEndpointIT` proves the same two behaviours over the real boot with a **test-set 30-row limit**, not the 20 M default: `exportPastTheRowLimitIs422BeforeAnyByte`, `aSecondExportOfTheOrganizationIsACleanTooManyRequestsFromTheStreamingThread`. That is not Step 6c and is not claimed as it |
| 6d: MF5 filled, row 60 may count as served | **NO.** Step 6b did not record a flat heap for 105 M rows. MF5 stays half measured, and `exportRange` stays **wired, not counted served** |

## Step 7: verdict

| Item | Value |
|---|---|
| REQ-007 satisfied on this host (with the measured numbers) | **NOT SATISFIED at the measured gate topology; not proven for a co-located install.** (a) Capacity: six months of raw at S-A is about 88.5 GB (EXTRAPOLATED from 170.68 B/row measured over 23 M rows) against about 18 GB free on this host; the week measured here fits and six months do not. (b) Latency: 6.2 to 9.2 s p50 for the 24 h raw shape over 1 to 200 instances, 15.6 s and 18.2 s p50 at 200 instances for the 30 d forced raw and the 7 d raw shapes, and `503` on those two shapes (a 30 s statement timeout, inferred), at a tunnelled topology and a 7 d window, with the database time itself small for narrow reads. (c) Not proven at all: the month, the six months, `IngestService` co-located, the rollup's CPU, and the 105 M-row export |
| What IS proven | Bytes per row (170.2 to 170.9 B incl. indexes, WAL about 245 B/row, the same at 30 s and 5 s cadences); the load, rollup and query paths run end to end as an Operator over HTTP against 20 M rows on PostgreSQL 14; the hourly tier answers a 30 d window in about 6.8 s at any instance count in the same topology; rollups at 3 to 4 s per run for a 3 h late window of 1,000 series |
| Open item 5 narrowed or closed | **Narrowed, not closed** (the draft for the integrator is in `DECISIONS.md`, a dated note under open item 5, not a ruling) |
| M-Q19 (c), a separate database, put to the owner (always) | **Put to the owner.** The numbers: raw tier 170.7 B/row, about 88.5 GB for six months of S-A, 177 GB for S-B and 1.77 TB for S-C against a 20 GB host; a 24 h read of all 1,000 series is 5.47 s of database time. This plan's recommendation: the storage numbers say the metrics store needs its own disk sizing whether or not it is a separate database; a separate database would also let the query load and the apply path stop sharing one connection pool and one disk. It would not change bytes per row, and it does not fix statements per query. The owner decides |
| OQ-21 (2b), partition the hourly tier (only if its measured size calls for detach-based retention) | **Not put to the owner.** The hourly tier is about 0.90 GB at six months of S-A (EXTRAPOLATED from 35.09 MB for the week), against 88.5 GB of raw; its size does not call for detach-based retention. The owner may read it otherwise |

## Step 8: teardown

| Item | Value |
|---|---|
| Benchmark container and volume removed | NOT REPORTED to the lane that wrote this file |
| The Cache left untouched | NOT REPORTED |
| Docker state and free disk after | NOT REPORTED |

## Findings and flags

1. **The month did not fit; the week was measured; every month and six-month figure is extrapolated** (`OR-112` (3)).
2. **Six months of raw S-A (about 88.5 GB, EXTRAPOLATED) does not fit a host with about 18 GB free.** REQ-007's capacity prerequisite
   (`SM-D26`) is a real number now: the owner decides the disk sizing, a separate database (M-Q19 (c)), or a smaller S-A (a longer interval).
3. **Query latency at the gate's topology: a 6 s floor and `503` on wide raw reads.** Two actions for the owner, neither done here:
   reduce the statements per HTTP query, and send raw-heavy wide queries to the hourly tier (or refuse them with a `422`).
4. **A co-located Step 3 was never run.** It is the one measurement that would separate the network from SM. The tooling runs against
   any reachable PostgreSQL 14; a local container with the application beside it would do it, labelled as not the reference host.
5. **`IngestService` throughput is NOT MEASURED** (the one figure carries the tunnel), **and neither is the rollup's CPU** (the sampler
   returned no samples).
6. **The load rate fell from 43.6 k to 15.7 k rows/s across the week.** The one-month load the plan first asked for would have been
   slower still; a re-load or restore after an outage is slower than the 41 k rows/s of Step 0b suggests.
7. **The 30 d shapes read 7 days.** Their times are SCALED and the "30 d forced raw" figure is a 7-day raw read in a 30-day window.
8. **Harness fixes made with this evidence:** one master key per benchmark database (a second boot died on the first boot's canary row; the
   key is now derived from the database's own identity (system_identifier and name) and the admin password, not from the tunnel's URL, with tests that boot twice and that re-spell the URL), and the six-month evidence line's
   label ("432 M rows/month" is 86.4 M). **Not fixed, flagged:** the harness does not check the database's migration level against the
   build (the `V003` failures above).
9. **Steps 6b, 6c and 6d are not measured;** `exportRange` stays wired, not counted served; acceptance criterion 6 is not claimed.
10. **Step 8 (teardown) and the host's state afterwards were not reported** to the lane that wrote this file.
