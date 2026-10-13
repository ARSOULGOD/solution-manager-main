# Plan 011 — gateway capacity (measured 2026-10-07)

> **History, taken verbatim from `origin/dp-agent-integration` @ `87e338e1` by plan 015 T0.1 (2026-10-08) and renamed from `011-…`.** It records a run of the *branch's* tree, not ours; it is not authority (plan [`015`](../015-agent-gateway.md) is). Apart from this note, only file names and links between history files were edited.

One machine (Mac aarch64, 10 CPUs, Docker Desktop), **one** gateway replica (shipped image, `--cpus=2 --memory=1g`), PostgreSQL 14 (`--cpus=2`), **1000 synthetic protocol-faithful agents on the same host** (they compete for CPU). Harness: `AgentCapacityIT`
(`mvn -o verify -pl launcher -Dit.test=AgentCapacityIT -Dcapacity.agents=1000 -Dcapacity.seconds=30 -Dcapacity.commandsPerSecond=50 -Dcapacity.burst=5000`). Raw reports sit beside this file.

| Run | Code | Raw |
|---|---|---|
| 1 | unbounded gRPC executor, per-session pollers | `015-history-raw-capacity-1000-run1-unbounded-executor.md` |
| 2 | bounded executor, **threads = max(8, 2×CPU) = 8** in a 2-CPU container, shared poll, batched telemetry | `015-history-raw-capacity-1000-run2-threads8.md` |
| 3 | same, **threads = 32** (current default) | `015-history-raw-capacity-1000-run3-threads32.md` |

## Findings

- Run 1 (no limits): fine at steady state (heartbeat p95 3 ms) but **1276 live threads** after a restart and multi-second acks for minutes. This is why the executor was bounded.
- Run 2 was a **regression I introduced**: 8 threads blocked on the database queued everything (steady heartbeat p95 836 ms; 5000-command drain 43 s vs 7 s; session-open p50 3.7 s). A CPU-derived pool size is wrong for blocking I/O. Fixed by run 3's default (32).
- Run 3: live threads 69–73 (vs 132 / 1276). Phase 3 (50 cmd/s, 1500 commands): heartbeat p95 127 ms, command end-to-end p50 966 / p99 1295 ms. Phase 4 (5000 queued at once): drained in 13.9 s, no admission refusals. Phase 7 (recovery) p95 ≤ 483 ms.
- **Unexplained, not resolved:** run 3 phase 2 (steady state, immediately after the connect ramp) shows heartbeat/telemetry p95 of ~2.5 s though phases 3 and 7 under the same or higher load are fast. Hypothesis: warm-up/backlog after the ramp, plus an independent reviewer process using the host CPU during that window. **Not proven**; needs a rerun on a quiet host before any latency figure is quoted.
- Run 3 was measured while other work ran on the same machine; run-to-run noise is not characterised (single runs only).

## Defaults

`max-sessions 2000`, admission 100/s burst 200 were left as set. They are *not* derived from this data; 1000 agents is the largest fleet measured. No claim is made for 2000.
Overload behaviour: queue drains without session loss; SIGKILL storm at 1000 agents recovered in 34 s with 66 admission refusals (limiter working).

## Not measured

Multiple replicas (unsupported), real WAN/TLS latency, a real (non-synthetic) fleet beyond the single-agent E2E, 2000 agents, long soak.
