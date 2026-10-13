# Live background collection on the reference host (plan 011 P11-4.4, S-G and S-F)

**Status: LIVE RUNS RECORDED (2026-10-06, integrator, on the dedicated SM test host, `OR-110`).** Four runs of
`MetricsCollectionLiveIT` are summarised below, copied from the redacted evidence files the run writes
(`backend/launcher/target/walkthrough-evidence/MetricsCollectionLiveIT-<UTC>.md`). Host name, address, user, key, broker and VPN are
not recorded here and never were in the files copied; the placeholders `<e2e-host>`, `<ssh-user>` and `<config-dir>` stand for them.

## How to run it (read this first)

`backend/launcher/scripts/run-e2e-metrics-collection.sh` (the walkthrough's own `SM_E2E_*` target, same
`sm.walkthrough.ssh.*` properties underneath). It runs ONLY `MetricsCollectionLiveIT`.

- **By default it only READS**: the collector issues `GET`s (a `curl` to the host's loopback over SSH, or directly) and
  `docker inspect`. No file is written to the host; no container is touched.
- **`SM_E2E_CONFIRM_DISRUPT=yes` also RESTARTS one Cache container and STOPS one**, then starts it again, because the plan's
  Step 2 asks for exactly one `series_reset` after a restart and `scrape_failed` plus a gap (never zeros) after a stop.
  A container the run stopped is started again in `@AfterAll`; the run FAILS LOUDLY if it could not, and says what to do.
- **`SM_E2E_ATTRIBUTION_TRAFFIC=yes`** (exactly; the script refuses anything else, and needs the second Cache) makes the
  attribution step distinguishable by value: 30 read-only GETs of absent keys to the second Cache's loopback port, nothing
  written (the Cache only counts a miss). Without it the step is skipped with its reason recorded.
- A second Cache on the same host (S-G) needs `SM_E2E_SECOND_CONTAINER`, `SM_E2E_SECOND_CONFIG_DIR` and
  `SM_E2E_SECOND_HOST_PORT` together; `SM_E2E_SECOND_MODE=direct-http` reads it the other way, which tests both modes on one
  host (the data plane must reach the target's address at that port from the machine running the test, or that instance is
  skipped and the skip recorded; the target is never edited).

## What the run asserts (from the database)

1. Each declared Cache has its `placement_metrics_endpoint` row (container port 8081 to the host port given) and a
   generation-0 active list.
2. After the window (default 10 minutes) each instance's `cache.hit_count` series holds samples in strict time order, a
   counter never decreases without a restart, and there is no `series_reset` row.
3. For EVERY declared counter (`cache.hit_count`, `cache.miss_count`, `cache.eviction_count`) each Cache's last stored value
   equals a fresh independent `GET` of its OWN host port, within the counter's movement over the window; the evidence names
   the counters that DIFFER between the Caches, or says "attribution not distinguishable by value" when none do (it does not
   fail). With `SM_E2E_ATTRIBUTION_TRAFFIC=yes` (second Cache required) the run first sends 30 `GET /api/cache?key=sm-attribution-<n>`
   (absent keys: a 404 and a miss, nothing written) to the SECOND Cache's loopback port only, waits two collection intervals,
   and then REQUIRES `cache.miss_count` to differ between the Caches and each stored value to equal its own port's.
4. (disruptive) after a restart: exactly one `series_reset` on that series, `signal = 'docker'` over SSH or `'declared'`
   directly, and the first sample after it counted from the restart.
5. (disruptive) after a stop: `scrape_failed` rises, **no sample is stored** for the down window (a gap, never zeros), and
   collection resumes when the container is started again.

## Evidence (from `backend/launcher/target/walkthrough-evidence/MetricsCollectionLiveIT-<UTC>.md`, redacted)

Four runs, in order. The first is a **failed** run and is kept because it is the reason for a code fix; the other three are the
evidence.

| Item | Run 1: `154107Z` (read-only, FAILED) | Run 2: `160144Z` (read-only) | Run 3: `161811Z` (full, two Caches) | Run 4: `164334Z` (full, two Caches, attribution traffic) |
|---|---|---|---|---|
| Date | 2026-10-06 | 2026-10-06 | 2026-10-06 | 2026-10-06 |
| Code | the lane before `a8895442` | the lane at or after `a8895442` | the lane at or after `a8895442` | the lane at `961bafe7` (attribution counters and the traffic step) |
| Mode and Caches | `via-ssh`; one Cache `cache-solution`, host port 8081 | `via-ssh`; one Cache, host port 8081 | `via-ssh`; `cache-solution` (8081) and `cache-solution-2` (8082) on the one target | as run 3 |
| Command (variables by name, no values) | `run-e2e-metrics-collection.sh` with the walkthrough's `SM_E2E_*` target | same | same, plus `SM_E2E_CONFIRM_DISRUPT=yes` and the three `SM_E2E_SECOND_*` | as run 3, plus `SM_E2E_ATTRIBUTION_TRAFFIC=yes` |
| Window, interval | PT10M, PT30S | PT10M, PT30S | PT10M, PT30S | PT10M, PT30S |
| `hit_count` samples in the window | **0** (`scrape_failed` 10) | 19 (`scrape_failed` 0, `parse_failed` 0, `deadline_missed` 0) | 20 and 20 (`scrape_failed` 0 on both) | 20 and 20 (`scrape_failed` 0 on both) |
| Attribution | not reached | `hit_count` stored 0.0, a fresh read of its own port 0.0 (counters equal, so by value it could not tell a mix-up from none) | both Caches' `hit_count` stored 0.0 = own port 0.0; the evidence notes the counters are EQUAL and that this run generated no traffic | see "Attribution" below: **`cache.miss_count` 30.0 on `cache-solution-2` against 0.0 on `cache-solution`, each equal to a fresh read of its own port** |
| `series_reset` after the restart (count, signal) | SKIPPED: read-only | SKIPPED: read-only | **1, signal `docker`**; samples 20 to 23; the first sample after it value 0.0, increment 0.0 | **1, signal `docker`**; samples 22 to 24; first sample after it value 0.0, increment 0.0 |
| `scrape_failed` and the stop | SKIPPED: read-only | SKIPPED: read-only | `cache-solution-2` stopped for about 2 min 45 s: `scrape_failed` 0 to 8 over PT2M30S, **0 samples stored in the down window**; started again; collection resumed (a further `series_reset` 0 to 1, expected: the container's start time changed) | the same: `scrape_failed` 0 to 8 over PT2M30S, **0 samples stored**; resumed; `series_reset` 0 to 1 |
| Summary (final) | `hit_count` samples 0, `scrape_failed` 10 | samples 19, reset 0, `scrape_failed` 0 | `cache-solution`: samples 29, reset 1, `scrape_failed` 0. `cache-solution-2`: samples 24, reset 1, `scrape_failed` 10 | `cache-solution`: samples 30, reset 1, `scrape_failed` 2. `cache-solution-2`: samples 27, reset 1, `scrape_failed` 10 |
| Direct-http instance | not run | not run | not run (both Caches `via-ssh`) | not run (both Caches `via-ssh`) |
| MF6 (exec latency, p50/p95/p99) and MF7 (`MaxSessions`) | MF6: not recorded by any run. MF7: `MaxSessions 10`, `MaxStartups 10:30:100`, read with `sshd -T` by the integrator, separately from the runs (plan 011 measured-facts table) | | | |

## What each run showed

**Run 1 (`154107Z`): zero samples, ten `scrape_failed`, and a code defect.** The test boot ran with `sm.dp.health.enabled=false`,
so the SSH fetcher bean did not exist and every `via-ssh` read failed. The collector counted the failure but did not say why.
Fixed in `a8895442`: the live collection boot carries the SSH fetcher, and the collector now logs why a document failed
(`MetricsCollectorLogTest`). No sample was stored, no zero was invented, which is the gap-not-zero property seen live on the way
to the fix.

**Run 2 (`160144Z`): read-only, 19 samples, no read failure.** With the fix, one Cache over SSH, nothing written to the host: 19
samples in strict time order across the window, none failed, none unparseable, no deadline missed, no `series_reset`. Both Caches
read 0 for every counter, so this run could not distinguish attribution by value, and the evidence says so.

**Run 3 (`161811Z`): two Caches, one restart, one stop.** Two Caches on one registered target, each with its own endpoint row
(container port 8081 to host ports 8081 and 8082), each collected on its own port: 20 samples each, no failure. Restarting
`cache-solution` produced exactly one `series_reset` with signal `docker` and the first sample after it counted from the restart
(0.0, increment 0.0, never negative). Stopping `cache-solution-2` raised `scrape_failed` by 8 over 2 min 30 s and stored no sample
in the down window; after the start, collection resumed.

**Run 4 (`164334Z`): the same, with attribution traffic.** Before the restart and the stop, the run sent 30 `GET
/api/cache?key=sm-attribution-<n>` (absent keys: a 404 and a miss, nothing written) to `cache-solution-2` only, waited two
collection intervals, and compared every declared counter of each Cache with a fresh read of its own port:

| Cache | `cache.hit_count` | `cache.miss_count` | `cache.eviction_count` |
|---|---|---|---|
| `cache-solution` (8081) | stored 0.0, own port 0.0 | stored 0.0, own port 0.0 | stored 0.0, own port 0.0 |
| `cache-solution-2` (8082) | stored 0.0, own port 0.0 | **stored 30.0, own port 30.0** | stored 0.0, own port 0.0 |

The counter that differs between the Caches is `cache.miss_count`, so a series holding the other port's numbers would have shown
(0.0 on the second Cache, or 30.0 on the first). S-G is proven by value.

## What is proven live, and what is not

Proven on the dedicated host: background collection over SSH with no session and nobody logged in; per-instance attribution of
two instances on one target by value (S-G); one `series_reset` after a real `docker restart` with the signal read from `docker
inspect`'s start time, and the sample after it counted from the restart; and a real `docker stop` is a gap with `scrape_failed`
and never a zero (S-F), with collection resuming after the start.

**Not proven here:** the `direct-http` acquisition mode against a live Cache (no run used it); MF6's exec-latency percentiles
(the runs record none); the collector under many concurrent instances on one host (two were declared); and a long window (every
run was ten minutes).

**Two harmless observations.** Run 4's `cache-solution` shows `scrape_failed` 2 in its summary: the two reads that landed while
its container was restarting. Run 3's `cache-solution-2` and run 4's both show `series_reset` 1 after the stop, which the gate
records as expected (a stop then start changes the container's start time).

**What the run did to the host (`OR-110`).** The disruptive runs (3 and 4) restarted one Cache container and stopped one, then
started it again; and a **second Cache container (`cache-solution-2`, host port 8082) was created on the dedicated test host**,
under `OR-110` (the host is a dedicated SM test host).

No credentials, no host names, no addresses and no values from a body belong in this file.
