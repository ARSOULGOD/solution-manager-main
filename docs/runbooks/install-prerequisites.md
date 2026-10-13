# Installation Prerequisites

## Local credential verification capacity

Local credentials use Argon2id with 384 MiB memory, four iterations, and parallelism one. Each hash uses a
16-byte salt and produces a 32-byte hash. Do not configure a setting below the absolute floor of 19 MiB memory,
two iterations, and parallelism one.

The runtime permits four concurrent credential hash operations, including hashing and verification. Saturation is
refused immediately; it is not queued. At the selected settings, four simultaneous verifications measured about
1.5 GiB of transient memory. Configure a
minimum 2 GiB JVM heap before enabling local credential login. A 1 GiB heap does not provide sufficient headroom.

Before go-live, rerun the commands in
[`006-argon2id-benchmark.md`](../plans/backend/evidence/006-argon2id-benchmark.md) section 2 on the target host and
tune based on those measurements. The benchmark host was an Apple Silicon development laptop, so its timings do not
establish production latency. The measured lower-memory alternative is 96 MiB memory, 12 iterations, and parallelism
one, at about 650 ms; selecting it is an owner posture decision and it must remain above the absolute floor.

## Database locale: usernames are folded by PostgreSQL `lower()`

Local usernames are unique per organization and matched at login without regard to case, and that case folding is
PostgreSQL's `lower()` under the database's collation/ctype (`principal_org_username_ci_uq`, `sm_control` V019, and the login
lookup). A database created with the `C` or `POSIX` locale folds ASCII letters only, so `Élodie` and `élodie` would be two
different users there and one user under a UTF-8 locale. Create the SM database with a UTF-8 locale (for example
`en_US.UTF-8` or `C.UTF-8`) and do not change it afterwards: changing the locale of a database that already holds
usernames can make existing rows collide. Check it with `SELECT datcollate, datctype FROM pg_database WHERE datname = current_database()`.

## One Solution Manager replica

Run **exactly one** Solution Manager replica per install. The apply worker holds a database advisory lock
(`WorkerSingletonGuard`) for the life of the process, and a second process that starts against the same database
refuses to start — by design, so that two workers can never execute the same apply (owner ruling `OR-44`: the
worker's per-poll tenant enumeration is an `I5` exception that is safe only because one replica runs it).

On Kubernetes, set the Deployment's `spec.strategy.type` to `Recreate`. The default, `RollingUpdate`, cannot
complete for this process: the new pod can never become Ready while the old pod holds the lock, and the old pod is
not torn down until the new one is Ready (plan 007 Results, round 2, finding 7).

> Verified 2026-10-05 against the Kubernetes Deployment documentation
> (`https://kubernetes.io/docs/concepts/workloads/controllers/deployment/`, source
> `content/en/docs/concepts/workloads/controllers/deployment.md`): *"All existing Pods are killed before new ones
> are created when `.spec.strategy.type==Recreate`."* and *"`.spec.strategy.type` can be "Recreate" or
> "RollingUpdate". "RollingUpdate" is the default value."* The same page warns: *"This will only guarantee Pod termination previous to creation for
> upgrades."* and *"If you manually delete a Pod, the lifecycle is controlled by the ReplicaSet and the
> replacement will be created immediately (even if the old Pod is still in a Terminating state)."* So `Recreate`
> removes the upgrade overlap only; a manual pod deletion can still briefly overlap, and the lock — not the
> strategy — is what refuses the second worker in that case.

Upgrading across the V014/V020 release has pre-checks that must run before the application stops: follow
[`operations.md`, "Upgrading from the V014/V020 release"](operations.md#upgrading-from-the-v014v020-release), one run of each
migration main, not the per-migration notes one at a time.

Expect a short outage on every upgrade: with `Recreate` the old pod is gone before the new one starts. Do not
scale the Deployment above one replica, and do not run a second install against the same `sm_data` database.

## Phase 0 and the migration step, per schema (`sm_data`, `sm_secret`, `sm_metrics`)

A fresh install runs the shipped Phase 0 script (`backend/dp-core/src/main/resources/db/bootstrap/sm_data_phase0.sql`)
once, as the cluster administrator, and then the migration step (`SmDataMigrationMain`, with the `sm_dp_migrator`
credential in `SM_DATA_MIGRATOR_URL`, `SM_DATA_MIGRATOR_USERNAME` and `SM_DATA_MIGRATOR_PASSWORD`). Phase 0 creates
three schemas, all owned by `sm_dp_owner`, and grants the runtime role `USAGE` on each and nothing more: `sm_data`,
`sm_secret` (the secret store, created by plan 010) and `sm_metrics` (the metrics store, plan 011).

The migration step migrates `sm_data` and then `sm_metrics`, each with its own Flyway history, and prints one line per
schema. A failure names the schema that failed, and a failure in `sm_metrics` leaves `sm_data`'s migrations committed;
re-running applies only what is missing.

**An install that already ran Phase 0 before `sm_metrics` existed** runs these two statements once, as the same
installer who ran Phase 0 (the executing role must be a member of `sm_dp_owner`, the comment at the head of the
script), and then the migration step as usual:

```sql
CREATE SCHEMA sm_metrics AUTHORIZATION sm_dp_owner;
GRANT USAGE ON SCHEMA sm_metrics TO sm_dp_runtime;
```

Without them the migration step fails at `sm_metrics` (SQLSTATE `3F000`, the schema does not exist) after `sm_data`
has migrated, and the application refuses to start: its readiness check names the migration step.

### The metrics partition command

`com.solace.sm.dp.apply.persistence.MetricsPartitionMain` is run by an operator with the same three
`SM_DATA_MIGRATOR_*` variables. It is never run by the application, and the application never holds that credential.

| Command | What it does |
|---|---|
| `status` | the partition runway, which closed months are not yet frozen, and the byte figures: `sm_metrics` bytes, database bytes and the budget (`SM_METRICS_DISK_BUDGET_BYTES`, optional; `unconfigured` when unset). The API never carries the byte figures |
| `freeze yyyy_mm` | monthly, after the month closes: `VACUUM (FREEZE)` that month's partition, then record it. A missed run shows as `unfrozen closed partitions` in `status` |
| `rollup yyyy_mm` | recomputes the hourly and daily rollups of a **closed** month for the tenants whose last raw hour is not yet rolled up (the same list `detach` refuses on). Use it when `detach` is refused because a series' last hour has no rollup computed after its newest row arrived, which happens when SM was down across that hour or a tenant's last instance retired before it. Idempotent; it never detaches |
| `detach yyyy_mm` | `DETACH PARTITION ... CONCURRENTLY` of a closed month. The table and its rows are kept. ⚠️ If an older transaction (a long query or export) holds it past the 10 s lock timeout, the command exits 1 with a message saying the partition is **pending**: its rows are hidden from queries through `metrics_raw` until it finishes. **Run the same `detach yyyy_mm` again** (it issues `FINALIZE`); `status` lists pending partitions |
| `archive yyyy_mm <dir>` | per tenant: a gzip CSV and a manifest (sha256 and row count), **unencrypted**. Write to an encrypted destination |
| `drop yyyy_mm <dir>` | drops the detached table, only once every tenant in it has a manifest that verifies against its file and the partition's row count |

The partition months are UTC months. A month that is still open (the current one) can be neither frozen nor detached.

⚠️ Do not `detach` a month in the first days after it closes: its last days are still inside the 7-day raw tier and the rollups' late window, and a detached month disappears from raw queries. `detach` refuses until the raw tier and the late window have both passed month end, and until every series' last raw hour has a rollup computed after that hour's newest row arrived (W2-04); if the second condition is what refuses, run `rollup yyyy_mm` and detach again.

## Restarting strategies: test them in dev first

Test and verify restarting strategies in dev first. A `rolling-health-gated` apply restarts the MI and trusts the
package's declared health probe. With a liveness-only probe (Cache's, as declared), the gate proves only that the
process came up — not that it is serving. Verify in a dev environment that the declared probe behaves as declared before
using a restarting strategy in production.

What the strategy does, so the dev run can be judged against it:

- The gate's only health signal is the probe the **package** declares (`health.probe`: `actuator-health` or
  `http-status`). The image's own `HEALTHCHECK` is never consulted. A package that declares no probe SM executes
  (none, or only `broker-heartbeat`) cannot be gated: the apply is refused before any write with
  `strategy.health.signal.absent`.
- How the probe is read is the **instance's** setting, chosen at declare: `via-ssh` runs a fixed `curl` on the host's
  loopback at the placement's host-side health port; `direct-http` has the data plane call the target's registered
  address at that same port. There is no fallback from one to the other, so the port must be reachable the way the
  chosen mode needs (for `direct-http`, from the data plane's network).
- After each replica's restart (and its gate) SM reads the config file back and compares its keyed hash with what it
  wrote. A difference lands `CONFIG_DIVERGED` (`verify.hash.mismatch`) and is **not** rolled back automatically — rolling
  back a restarted replica would be a second outage. The rest of the roll is not started.
- `observeApply` reports `gateProved` (`readiness` or `liveness`) on a passed gate: the strength the package **declared**
  for the probe that gated. It is never upgraded and never present when no probe gated.

## Restart observation after an apply that runs no health gate (OR-112 (1))

`manual` and every other strategy that does not run the health gate (`rolling-no-validation`, `restart-all`, `scheduled`) leave
the configuration ladder at `persisted`: the journal cannot say the restart happened. The data plane's health poller closes
that gap. Each round, for a placement whose newest generation is `persisted` and has seen no start since its write, it runs
the same fixed `docker inspect` as the collector (the container's id and `State.StartedAt`) over the target's pooled SSH
session, records the start **only when it changed**, and the generation reaches `restart-observed` when that start is later
than the start the host itself reported when the apply's write was read back. The metrics active list and the push subscription plan follow the same record, so a
File Mesh instance applied with `manual` is subscribed once its container has restarted, not before.

- **It reads the runtime's state, whatever the instance's metrics acquisition mode** (`direct-http` too), so every target needs
  `docker` on the SSH user's path, as an apply already does. A placement nothing was applied to, one already observed, and a
  `rolling-health-gated` apply (its gate is its proof) are never asked. It is part of the health poller's round: with
  `sm.dp.health.enabled=false` nothing observes a start and an ungated apply stays at `persisted`.
- **No clock synchronisation is needed for this.** Both values compared are the Docker host's own: the container start the
  poller reads, and the start the engine read back from the same host at the write (kept with the write's journal row,
  `apply_attempt.write_baseline`). No data plane timestamp is compared with a host timestamp, so a host whose clock runs
  minutes ahead of or behind the data plane's neither shows a restart that did not happen nor hides one that did (this
  replaces the first build's comparison with the write's end, which did depend on the two clocks agreeing). The two values
  must differ by more than a millisecond, which is rounding, not skew. A write that left no baseline (it failed or was cut
  off, or its container had never started) is left at `persisted` on purpose and is not asked about again; apply again to
  move it.
- A restart that happens **before** the apply's write never counts: the baseline is the start that was running when the file
  was written, so the previous container run is by definition not later than it.

## Metrics collection: what the data plane reads, and what to restrict

The data plane collects an instance's metrics in the background (plan 011 P11-4.3), with no session and no apply in flight,
and reads them the way the instance **declared** (M-Q2: `via-ssh` or `direct-http`, chosen at declare, never switched by
a failure). Nothing is written to the host by collection.

- **`via-ssh`** runs a fixed `curl` against the host's own loopback at the placement's host-side metrics port, and a fixed
  `docker inspect` for the container's start time, over the target's pooled SSH session (the one the health poller shares).
  `curl` and `docker` must be on the SSH user's path. The collector leaves the health poller its own probe slot on that
  session; the per-target bound is `sm.dp.metrics.collect.per-target-cap` and `health-reserve` (defaults 4 and 1, so 3 with
  the pool's default of 4 probes per session). Set them from the host's measured `sshd` `MaxSessions` and exec latency once
  they are measured on the reference host (plan 011 P11-4.3 Step 0, MF6 and MF7); until then they are defaults, not facts.
- **`direct-http`** has the data plane call the target's **registered address** at the host port the declare mapped
  (`metricsPorts`). The address is never taken from a browser, and a re-target reads the new target's address. The data
  plane must be able to route to it; if it cannot, the instance shows gaps and `scrape_failed`, and the fix is the network,
  not the target record.

⚠️ **Restrict the metrics port in `direct-http` mode (M-Q2's accepted residual).** The Cache's `8081` also serves an
unauthenticated `POST /api/cache`; anyone who can reach the port can mutate the cache. Allow the port only from the data
plane's source address (security group, firewall or a host rule). The same holds for **any** MI whose metrics port has a
mutating route: SM only ever issues `GET`, but it cannot stop another client from using the route. `via-ssh` needs no
such rule when the port is published on the host's loopback only.

⚠️ **Organizations sharing one machine must partition metrics host ports (OQ-23, `OR-92`, following `OR-89`).** `OR-89`
lets two organizations each register the same machine, and every port-collision guard is per organization and target.
Two organizations' placements on one machine can therefore both name host port `28081`, and each collector would then read
the other's Cache and store its numbers in its own series. There is no code guard: agree, per machine, which organization
owns which host-port range (for metrics ports and for health ports) before either declares.

## An apply that was cut off: what the reconciler does, and the latest time it can act

When SM cannot tell what an apply did to a host (the connection dropped after the file was written, the process died
mid-step), the request is `APPLY_INDETERMINATE`. A reconciler then **looks at the host again** and decides from what it
finds, not from the journal alone (owner ruling verify-then-retry, `OR-59`, `OR-63`):

| What the host holds now | What SM does |
|---|---|
| the intended file, and (for a restarting strategy) the replica was restarted by this request | confirms it (`INDETERMINATE_RESOLVED`). Writes nothing, restarts nothing. Under `rolling-health-gated` it also takes one read of the declared probe and confirms only on `HEALTHY` |
| the intended file under `manual` | confirms it. `manual` never restarts, so a matching file is the whole proof |
| the intended file, not restarted | restarts the replica once, then verifies |
| the file as it was before the write (or no file, when the adapter recorded that there was none) | writes the intended file again, **only if the file is still exactly what it just read**, then restarts and verifies |
| anything else, including a file someone else wrote | **does nothing to it.** The request stays `APPLY_INDETERMINATE`, `observeApply` shows `reconcileReason: reconcile.foreign.content`, and an `instance.apply-outcome` notice goes out |
| the host cannot be read | retries with backoff (30 s, doubling to 5 min), at most 5 failed inspections, then gives up with `reconcile.exhausted` |

**The latest a restart or re-apply can happen** is `sm.dp.apply.reconcile.max-redrive-age` (default **1 hour**) after SM
first saw the request as indeterminate, and at most `sm.dp.apply.reconcile.max-redrives` (default **1**) times. A Solution
Manager that was down for hours picks its reconciliations up when it returns, so a restart can come up to an hour after
the operator's request, never later. Past the hour the reconciler may still **confirm** (that is a read) but never restarts
or rewrites; it gives up with `reconcile.redrive.too.late`. If an operator has since stopped the instance it does not
restart it either (`reconcile.superseded.by.later.request`).

A give-up is permanent for that request: SM does not poll it again. The way out is the operator's `resolveIndeterminate`,
which works exactly as before. The other settings are `max-attempts` (5), `initial-backoff` (30 s) and `max-backoff` (5 min).
