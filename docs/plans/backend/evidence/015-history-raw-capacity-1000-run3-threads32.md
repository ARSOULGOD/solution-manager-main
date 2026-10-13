# Plan 011 — gateway capacity measurement

Generated 2026-10-07T06:39:39.944218Z by `AgentCapacityIT`.

## Hardware and configuration

- host: Mac OS X 26.6 aarch64, 10 logical CPUs, JVM 26.0.1
- docker: cpus=10 mem=8321515520 server=29.5.3
- gateway replica: the shipped image, cgroup limits --cpus=2 --memory=1g; max-sessions 5000, admission 100/s burst 200, poll interval 1 s (default), notice-retry 15 s (default)
- database: postgres:14, --cpus=2 --memory=2g, max_connections=200, otherwise default configuration; the gateway's pool is the default (10 connections)
- workload: 1000 synthetic agents; heartbeat 30 s; report 30 s; telemetry batch every 2 s with 5 samples; simulated operation time 50 ms; 50 commands/s in phase 3 (RESTART, one per agent in turn); phase length 30 s; burst 5000

## 1 enrol and connect 1000 agents

- enrolment: 1000 agents in 43.1 s (23.2/s); enrol p50/p95/p99 177.7 / 2832.9 / 13672.4 / 13889.1 ms
- connect ramp to all ACTIVE: 14.6 s; session-open p50/p95/p99/max 10.7 / 48.5 / 75.3 / 185.8 ms; admission refusals 0; other errors {}

## 2 steady state (heartbeats, reports, telemetry only)

- window 30 s: heartbeats 33.3/s, reports 33.3/s, telemetry batches 500.2/s (acked 500.2/s), samples committed 2499.6/s, commands acked 0.0/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 1.3 / 2485.4 / 2680.7 / 2723.9; report 3.0 / 1149.6 / 1250.1 / 1337.2; telemetry ack 4.4 / 2224.3 / 2625.0 / 2875.5; notice-to-offer NaN / NaN / NaN / NaN
  - gateway container (cgroup 2 CPU, 1g): CPU avg 84% / max 201% of one core, memory avg 880 / max 893 MB; database container: CPU avg 72% / max 119%, memory max 139 MB
  - gateway JVM (management endpoint): heap used avg 149 / max 233 MB, live threads max 69
  - queue depth: max queued 0, max offered 0, max delivered 0; final acked total 0
  - fleet: sessions opened 1000, closed 0, admission refusals 0, other protocol errors {}

## 3 command load at 50/s

- window 30 s: heartbeats 33.1/s, reports 33.2/s, telemetry batches 498.6/s (acked 498.6/s), samples committed 2493.1/s, commands acked 49.6/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 2.4 / 127.2 / 202.5 / 300.3; report 3.1 / 36.2 / 69.1 / 120.4; telemetry ack 7.5 / 81.6 / 147.0 / 292.5; notice-to-offer 2.5 / 49.5 / 137.5 / 214.8
  - command end-to-end, database timestamps (enqueue to terminal ack), n=1500: p50 966 / p95 1218 / p99 1295 / max 1350 ms
  - gateway container (cgroup 2 CPU, 1g): CPU avg 100% / max 141% of one core, memory avg 880 / max 881 MB; database container: CPU avg 97% / max 147%, memory max 188 MB
  - gateway JVM (management endpoint): heap used avg 141 / max 217 MB, live threads max 73
  - queue depth: max queued 50, max offered 1, max delivered 0; final acked total 1450
  - fleet: sessions opened 1000, closed 0, admission refusals 0, other protocol errors {}

## 4 overload — 5000 commands enqueued at once

- peak queue depth 5000; drained in 13.9 s
- window 14 s: heartbeats 64.9/s, reports 2.4/s, telemetry batches 503.9/s (acked 501.7/s), samples committed 2502.4/s, commands acked 359.9/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 572.7 / 4322.0 / 5068.8 / 6056.4; report 4.3 / 36.2 / 46.6 / 46.6; telemetry ack 419.2 / 755.5 / 822.7 / 895.5; notice-to-offer 1249.4 / 2947.6 / 3334.3 / 3586.3
  - command end-to-end, database timestamps (enqueue to terminal ack), n=5000: p50 10712 / p95 12741 / p99 12919 / max 13043 ms
  - gateway container (cgroup 2 CPU, 1g): CPU avg 182% / max 190% of one core, memory avg 882 / max 882 MB; database container: CPU avg 204% / max 218%, memory max 213 MB
  - gateway JVM (management endpoint): heap used avg 142 / max 216 MB, live threads max 73
  - queue depth: max queued 3290, max offered 3079, max delivered 192; final acked total 6277
  - fleet: sessions opened 1000, closed 0, admission refusals 0, other protocol errors {}

## 5 planned restart — SIGTERM with 1000 agents attached (the drain)

- container healthy after 26.4 s; all 1000 agents ACTIVE again after 49.6 s; agents told to drain 1000; sessions closed 1000; admission refusals during recovery 0
- window 50 s: heartbeats 11.0/s, reports 21.2/s, telemetry batches 164.4/s (acked 91.0/s), samples committed 453.4/s, commands acked 0.0/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 3471.9 / 4734.9 / 4827.9 / 4860.2; report 1382.3 / 4311.8 / 4615.4 / 4830.1; telemetry ack 1664.2 / 6940.8 / 7499.5 / 8309.4; notice-to-offer NaN / NaN / NaN / NaN
  - gateway container (cgroup 2 CPU, 1g): CPU avg 188% / max 222% of one core, memory avg 650 / max 883 MB; database container: CPU avg 48% / max 133%, memory max 219 MB
  - gateway JVM (management endpoint): heap used avg NaN / max NaN MB, live threads max NaN
  - queue depth: max queued 0, max offered 0, max delivered 0; final acked total 6500
  - fleet: sessions opened 2000, closed 1000, admission refusals 0, other protocol errors {DRAINING=100}

## 6 crash — SIGKILL with 1000 agents attached (the reconnect storm)

- killed with SIGKILL; container healthy after 30.3 s; all 1000 agents ACTIVE again after 48.4 s; sessions closed 1000; admission refusals during recovery 185 (the limits at work: 100/s sustained, burst 200)
- window 48 s: heartbeats 3.8/s, reports 20.6/s, telemetry batches 127.5/s (acked 119.9/s), samples committed 597.4/s, commands acked 0.0/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 341.5 / 891.0 / 5048.0 / 5066.1; report 817.0 / 1790.1 / 1937.0 / 5054.3; telemetry ack 927.4 / 1868.1 / 1981.1 / 8762.9; notice-to-offer NaN / NaN / NaN / NaN
  - gateway container (cgroup 2 CPU, 1g): CPU avg 177% / max 233% of one core, memory avg 566 / max 855 MB; database container: CPU avg 41% / max 126%, memory max 219 MB
  - gateway JVM (management endpoint): heap used avg NaN / max NaN MB, live threads max NaN
  - queue depth: max queued 0, max offered 0, max delivered 0; final acked total 6500
  - fleet: sessions opened 3000, closed 2000, admission refusals 185, other protocol errors {DRAINING=100}

## 7 recovery (same load as phase 2)

- window 30 s: heartbeats 33.6/s, reports 33.4/s, telemetry batches 500.2/s (acked 511.7/s), samples committed 2557.7/s, commands acked 0.0/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 65.3 / 482.7 / 682.7 / 795.4; report 5.1 / 97.3 / 174.1 / 818.0; telemetry ack 7.3 / 423.2 / 692.3 / 954.4; notice-to-offer NaN / NaN / NaN / NaN
  - gateway container (cgroup 2 CPU, 1g): CPU avg 141% / max 242% of one core, memory avg 858 / max 862 MB; database container: CPU avg 108% / max 141%, memory max 226 MB
  - gateway JVM (management endpoint): heap used avg 147 / max 223 MB, live threads max 69
  - queue depth: max queued 0, max offered 0, max delivered 0; final acked total 6500
  - fleet: sessions opened 3000, closed 2000, admission refusals 185, other protocol errors {DRAINING=100}


## Interpretation limits

One machine, one gateway replica, a synthetic fleet on the same host as the containers (so the load generator competes with them for CPU), PostgreSQL with default tuning apart from max_connections. The numbers bound what this build does here; they are not a promised capacity, and they say nothing about a multi-replica deployment (not supported in this build) or a real WAN.

Total run time 263 s.
