# Plan 011 P7.3 — packaging and operations (verified 2026-10-07)

> **History, taken verbatim from `origin/dp-agent-integration` @ `87e338e1` by plan 015 T0.1 (2026-10-08) and renamed from `011-…`.** It records a run of the *branch's* tree, not ours; it is not authority (plan [`015`](../015-agent-gateway.md) is). Apart from this note, only file names and links between history files were edited.

Via `ContainerPackagingIT` (real image built by `runtime/scripts/build-image.sh`, real `docker compose`-style Phase 0 /
migrate / launcher containers, a real `sm-agent`). Raw harness output: `015-history-raw-p73-packaging.md`. 11/11 PASS on the
final run.

| # | Scenario | Result |
|---|---|---|
| P01 | Reproducible image | two independent builds from the same source produce the **identical image digest** |
| P02 | Bootstrap and migrate as separate one-shot containers | Phase 0 completes once; first migrate reports `sm_control` migrated 14 (…010, **011, 012, 014, 015** — `V013` absent by design) and `sm_data` migrated 15 (…018–026); a second migrate on the same database is a clean no-op for both schemas |
| P03 | Startup from documented inputs; enrolment; real host command | healthy 10 s after start; readiness socket accepts on the published port |
| P04 | Observability without an unauthenticated route | the authenticated management endpoint answers; **every** unauthenticated probe (`/actuator`, `/actuator/health`, `/actuator/metrics`, `/management/health`, `/health`, `/`) returns **404** through the console's HTTP listener — the protection against HTTP mappings bypassing console auth holds |
| P05 | Graceful shutdown (`docker stop`) | returns in 2 s, exit **143** (SIGTERM convention, not a failure); log shows `agent-gateway drained: sessions=1 answered=1 forced=0` |
| P05b | **Drain with a frozen agent; readiness closes first** | readiness refused 91 ms after shutdown began, *while the container was still running* — the L4 readiness socket genuinely stops first, giving a balancer time to stop routing before the drain finishes; the frozen agent is forced at the drain window (`forced=1`) and the container still exits inside the grace period; once thawed the agent reconnects normally |
| P06 | Crash (`SIGKILL`) mid-command | exit 137; the command held at `DELIVERED:-` at the moment of the kill is not lost and not duplicated after restart; one `docker start`, epoch advances |
| P07 | Persistent state across container recreation | commands and audit rows survive recreation; epoch continues to advance (no identity reset) |
| P08 | Missing TLS material | fails closed at startup (`TLS listener could not start`) rather than starting unprotected |
| P09 | **Scheduled maintenance as a deployed job** | run as a separate one-shot process (`SmDataMaintenanceMain`) using the **migrator** credential: partitions created (15→21), nothing to delete on a fresh run; **running it with the runtime credential fails** with Postgres `SQLSTATE 42501` (insufficient privilege) — proving the runtime role genuinely cannot do DDL/maintenance and the maintenance job genuinely needs its own credential, not a shortcut |
| P10 | `compose.yml` validates | `docker compose config` accepts it |

## What this settles

- **Partition-maintenance ownership (item 3/8)**: resolved as a **separate scheduled one-shot process**
  (`SmDataMaintenanceMain`), not a thread inside the launcher and not the runtime role. P09 proves both halves: the
  job works with its own credential and **cannot** work with the application's own (least-privilege) credential.
  Deployment artifacts for running it on a schedule are in `runtime/` (cron/compose profile); a Kubernetes CronJob
  equivalent is not built or tested here (see Remaining blockers).
- **Health/readiness (item 8)**: the console serves **no** unauthenticated actuator/management route (P04); the
  optional L4 readiness socket closes at the *start* of shutdown rather than at the end (P05b), which is the
  property a load balancer needs. No authenticated-observability replacement beyond the existing management
  endpoint was found necessary by this drill; see Remaining blockers for what it does *not* prove.
- **Reproducibility**: P01's two independent builds producing one digest is a real check (both builds run from
  scratch), not a cache-hit artifact.

## Runbook execution by hand

`docs/runbooks/backend/enable-agent-gateway.md` was rewritten against this evidence: certificate generation, Phase 0
→ migrate → launcher sequencing, enrolment SQL, and the verification queries all reflect what P01–P10/P05b actually
exercised. `UNTESTED` markers were replaced only where a scenario above (or `AgentCapacityIT`) verified the claim;
the runbook's "Limits to read before relying on this" section states what remains unverified (multi-replica, real
WAN, non-macOS host, certificate rotation).

## Not covered here

A real reverse proxy / load balancer in front of the readiness socket (P05b proves the socket's own timing, not a
balancer's reaction to it); Kubernetes manifests for the launcher or the maintenance CronJob (only `compose.yml` is
validated, P10); certificate rotation (manual per the runbook, not exercised by any IT); a second gateway replica.
