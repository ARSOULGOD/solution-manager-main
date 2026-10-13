# Plan 011 — gateway capacity measurement

Generated 2026-10-07T06:23:15.859170Z by `AgentCapacityIT`.

## Hardware and configuration

- host: Mac OS X 26.6 aarch64, 10 logical CPUs, JVM 26.0.1
- docker: cpus=10 mem=8321515520 server=29.5.3
- gateway replica: the shipped image, cgroup limits --cpus=2 --memory=1g; max-sessions 5000, admission 100/s burst 200, poll interval 1 s (default), notice-retry 15 s (default)
- database: postgres:14, --cpus=2 --memory=2g, max_connections=200, otherwise default configuration; the gateway's pool is the default (10 connections)
- workload: 1000 synthetic agents; heartbeat 30 s; report 30 s; telemetry batch every 2 s with 5 samples; simulated operation time 50 ms; 50 commands/s in phase 3 (RESTART, one per agent in turn); phase length 30 s; burst 5000

## 1 enrol and connect 1000 agents

- enrolment: 1000 agents in 13.6 s (73.6/s); enrol p50/p95/p99 117.2 / 217.6 / 3506.8 / 3525.2 ms
- connect ramp to all ACTIVE: 15.4 s; session-open p50/p95/p99/max 19.4 / 104.7 / 148.0 / 231.0 ms; admission refusals 0; other errors {}

## 2 steady state (heartbeats, reports, telemetry only)

- window 30 s: heartbeats 33.3/s, reports 33.3/s, telemetry batches 499.6/s (acked 499.8/s), samples committed 2499.3/s, commands acked 0.0/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 1.2 / 3.2 / 17.6 / 29.6; report 2.3 / 6.8 / 48.0 / 84.2; telemetry ack 4.5 / 11.3 / 41.1 / 266.6; notice-to-offer NaN / NaN / NaN / NaN
  - gateway container (cgroup 2 CPU, 1g): CPU avg 114% / max 161% of one core, memory avg 877 / max 888 MB; database container: CPU avg 132% / max 161%, memory max 134 MB
  - gateway JVM (management endpoint): heap used avg 156 / max 207 MB, live threads max 132
  - queue depth: max queued 0, max offered 0, max delivered 0; final acked total 0
  - fleet: sessions opened 1000, closed 0, admission refusals 0, other protocol errors {}

## 3 command load at 50/s

- window 30 s: heartbeats 33.8/s, reports 33.3/s, telemetry batches 499.1/s (acked 499.1/s), samples committed 2495.0/s, commands acked 49.7/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 1.2 / 4.0 / 9.2 / 20.3; report 2.4 / 6.9 / 12.4 / 18.6; telemetry ack 5.7 / 16.5 / 26.1 / 65.6; notice-to-offer 1.5 / 4.2 / 10.4 / 40.4
  - command end-to-end, database timestamps (enqueue to terminal ack), n=1500: p50 622 / p95 1030 / p99 1058 / max 1097 ms
  - gateway container (cgroup 2 CPU, 1g): CPU avg 105% / max 123% of one core, memory avg 875 / max 875 MB; database container: CPU avg 128% / max 135%, memory max 176 MB
  - gateway JVM (management endpoint): heap used avg 193 / max 234 MB, live threads max 132
  - queue depth: max queued 47, max offered 4, max delivered 19; final acked total 1340
  - fleet: sessions opened 1000, closed 0, admission refusals 0, other protocol errors {}

## 4 overload — 5000 commands enqueued at once

- peak queue depth 4754; drained in 7.2 s
- window 7 s: heartbeats 64.8/s, reports 4.9/s, telemetry batches 507.7/s (acked 507.3/s), samples committed 2528.9/s, commands acked 698.0/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 6.7 / 42.0 / 59.6 / 87.3; report 27.0 / 59.6 / 64.7 / 64.7; telemetry ack 22.0 / 63.9 / 96.5 / 313.6; notice-to-offer 21.3 / 97.9 / 159.5 / 397.0
  - command end-to-end, database timestamps (enqueue to terminal ack), n=5000: p50 3254 / p95 6458 / p99 6661 / max 6812 ms
  - gateway container (cgroup 2 CPU, 1g): CPU avg 157% / max 218% of one core, memory avg 876 / max 876 MB; database container: CPU avg 207% / max 210%, memory max 194 MB
  - gateway JVM (management endpoint): heap used avg 92 / max 100 MB, live threads max 132
  - queue depth: max queued 3794, max offered 49, max delivered 28; final acked total 5490
  - fleet: sessions opened 1000, closed 0, admission refusals 0, other protocol errors {}

## 5 reconnect storm — gateway container restarted with 1000 agents attached

- container healthy after 21.4 s; all 1000 agents ACTIVE again after 33.5 s; sessions closed 1000; admission refusals during recovery 0
- window 34 s: heartbeats 4.5/s, reports 29.7/s, telemetry batches 142.7/s (acked 133.6/s), samples committed 667.8/s, commands acked 0.0/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 10.9 / 745.2 / 881.4 / 909.6; report 1377.5 / 4100.7 / 5156.2 / 8482.9; telemetry ack 1192.0 / 3916.1 / 5199.8 / 7638.8; notice-to-offer NaN / NaN / NaN / NaN
  - gateway container (cgroup 2 CPU, 1g): CPU avg 161% / max 214% of one core, memory avg 586 / max 921 MB; database container: CPU avg 45% / max 92%, memory max 199 MB
  - gateway JVM (management endpoint): heap used avg NaN / max NaN MB, live threads max NaN
  - queue depth: max queued 0, max offered 0, max delivered 0; final acked total 6500
  - fleet: sessions opened 2000, closed 1000, admission refusals 0, other protocol errors {DRAINING=200}

## 6 recovery (same load as phase 2)

- window 30 s: heartbeats 33.8/s, reports 33.4/s, telemetry batches 501.0/s (acked 509.7/s), samples committed 2545.9/s, commands acked 0.0/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 12.7 / 956.2 / 1610.5 / 3010.4; report 1146.4 / 4868.9 / 6505.9 / 9322.3; telemetry ack 483.1 / 4781.0 / 7114.0 / 10959.9; notice-to-offer NaN / NaN / NaN / NaN
  - gateway container (cgroup 2 CPU, 1g): CPU avg 204% / max 289% of one core, memory avg 1008 / max 1015 MB; database container: CPU avg 168% / max 212%, memory max 217 MB
  - gateway JVM (management endpoint): heap used avg 174 / max 246 MB, live threads max 1276
  - queue depth: max queued 0, max offered 0, max delivered 0; final acked total 6500
  - fleet: sessions opened 2000, closed 1000, admission refusals 0, other protocol errors {DRAINING=200}


## Interpretation limits

One machine, one gateway replica, a synthetic fleet on the same host as the containers (so the load generator competes with them for CPU), PostgreSQL with default tuning apart from max_connections. The numbers bound what this build does here; they are not a promised capacity, and they say nothing about a multi-replica deployment (not supported in this build) or a real WAN.

Total run time 164 s.
