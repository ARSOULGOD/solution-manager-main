# Plan 011 — gateway capacity measurement

Generated 2026-10-07T06:32:49.278485Z by `AgentCapacityIT`.

## Hardware and configuration

- host: Mac OS X 26.6 aarch64, 10 logical CPUs, JVM 26.0.1
- docker: cpus=10 mem=8321515520 server=29.5.3
- gateway replica: the shipped image, cgroup limits --cpus=2 --memory=1g; max-sessions 5000, admission 100/s burst 200, poll interval 1 s (default), notice-retry 15 s (default)
- database: postgres:14, --cpus=2 --memory=2g, max_connections=200, otherwise default configuration; the gateway's pool is the default (10 connections)
- workload: 1000 synthetic agents; heartbeat 30 s; report 30 s; telemetry batch every 2 s with 5 samples; simulated operation time 50 ms; 50 commands/s in phase 3 (RESTART, one per agent in turn); phase length 30 s; burst 5000

## 1 enrol and connect 1000 agents

- enrolment: 1000 agents in 28.1 s (35.6/s); enrol p50/p95/p99 302.2 / 628.0 / 3216.9 / 3579.2 ms
- connect ramp to all ACTIVE: 25.7 s; session-open p50/p95/p99/max 3665.1 / 5232.1 / 5434.3 / 5570.6 ms; admission refusals 0; other errors {}

## 2 steady state (heartbeats, reports, telemetry only)

- window 30 s: heartbeats 33.3/s, reports 33.3/s, telemetry batches 500.3/s (acked 512.7/s), samples committed 2559.3/s, commands acked 0.0/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 3.5 / 835.9 / 1115.8 / 1475.4; report 3.8 / 1317.0 / 1410.4 / 1455.1; telemetry ack 6.6 / 1098.7 / 1439.6 / 1541.5; notice-to-offer NaN / NaN / NaN / NaN
  - gateway container (cgroup 2 CPU, 1g): CPU avg 127% / max 273% of one core, memory avg 881 / max 882 MB; database container: CPU avg 100% / max 181%, memory max 137 MB
  - gateway JVM (management endpoint): heap used avg 158 / max 258 MB, live threads max 45
  - queue depth: max queued 0, max offered 0, max delivered 0; final acked total 0
  - fleet: sessions opened 1000, closed 0, admission refusals 0, other protocol errors {}

## 3 command load at 50/s

- window 30 s: heartbeats 33.4/s, reports 34.8/s, telemetry batches 510.9/s (acked 502.4/s), samples committed 2504.4/s, commands acked 49.7/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 3.0 / 1418.5 / 1596.4 / 1654.4; report 5.2 / 982.7 / 1553.0 / 1668.1; telemetry ack 9.3 / 1261.7 / 1610.6 / 1754.6; notice-to-offer 6.3 / 1154.0 / 1337.7 / 1383.6
  - command end-to-end, database timestamps (enqueue to terminal ack), n=1500: p50 572 / p95 3163 / p99 3963 / max 3996 ms
  - gateway container (cgroup 2 CPU, 1g): CPU avg 95% / max 170% of one core, memory avg 868 / max 870 MB; database container: CPU avg 113% / max 248%, memory max 187 MB
  - gateway JVM (management endpoint): heap used avg 131 / max 205 MB, live threads max 49
  - queue depth: max queued 100, max offered 0, max delivered 0; final acked total 1350
  - fleet: sessions opened 1000, closed 0, admission refusals 0, other protocol errors {}

## 4 overload — 5000 commands enqueued at once

- peak queue depth 4956; drained in 43.2 s
- window 43 s: heartbeats 38.9/s, reports 28.4/s, telemetry batches 503.1/s (acked 421.7/s), samples committed 2105.7/s, commands acked 151.7/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 12340.2 / 24318.2 / 26923.0 / 27826.5; report 15829.5 / 25163.3 / 26697.3 / 27587.0; telemetry ack 3269.5 / 8070.1 / 8247.2 / 8422.3; notice-to-offer 6788.9 / 23818.0 / 25407.6 / 26020.7
  - command end-to-end, database timestamps (enqueue to terminal ack), n=5000: p50 23779 / p95 39631 / p99 40919 / max 41555 ms
  - gateway container (cgroup 2 CPU, 1g): CPU avg 157% / max 237% of one core, memory avg 871 / max 876 MB; database container: CPU avg 212% / max 247%, memory max 232 MB
  - gateway JVM (management endpoint): heap used avg 144 / max 225 MB, live threads max 49
  - queue depth: max queued 3767, max offered 4459, max delivered 456; final acked total 6498
  - fleet: sessions opened 1000, closed 0, admission refusals 0, other protocol errors {}

## 5 planned restart — SIGTERM with 1000 agents attached (the drain)

- container healthy after 47.3 s; all 1000 agents ACTIVE again after 62.5 s; agents told to drain 1000; sessions closed 1000; admission refusals during recovery 0
- window 62 s: heartbeats 6.9/s, reports 26.2/s, telemetry batches 190.0/s (acked 242.4/s), samples committed 1205.8/s, commands acked 42.6/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 14370.4 / 25836.3 / 28175.7 / 29949.0; report 1897.8 / 27076.8 / 29227.6 / 29981.0; telemetry ack 3814.0 / 7351.8 / 7466.2 / 7526.7; notice-to-offer 24743.9 / 28899.4 / 30569.5 / 30743.9
  - gateway container (cgroup 2 CPU, 1g): CPU avg 173% / max 234% of one core, memory avg 562 / max 879 MB; database container: CPU avg 95% / max 225%, memory max 233 MB
  - gateway JVM (management endpoint): heap used avg NaN / max NaN MB, live threads max NaN
  - queue depth: max queued 0, max offered 0, max delivered 0; final acked total 6500
  - fleet: sessions opened 2000, closed 1000, admission refusals 0, other protocol errors {DRAINING=572}

## 6 crash — SIGKILL with 1000 agents attached (the reconnect storm)

- killed with SIGKILL; container healthy after 18.8 s; all 1000 agents ACTIVE again after 34.0 s; sessions closed 1000; admission refusals during recovery 66 (the limits at work: 100/s sustained, burst 200)
- window 34 s: heartbeats 0.5/s, reports 29.4/s, telemetry batches 118.4/s (acked 103.4/s), samples committed 510.3/s, commands acked 0.0/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 1153.1 / 1168.5 / 1168.5 / 1168.5; report 1028.1 / 1370.8 / 1960.1 / 2359.5; telemetry ack 880.6 / 1202.7 / 1450.8 / 2258.6; notice-to-offer NaN / NaN / NaN / NaN
  - gateway container (cgroup 2 CPU, 1g): CPU avg 160% / max 213% of one core, memory avg 534 / max 847 MB; database container: CPU avg 48% / max 176%, memory max 223 MB
  - gateway JVM (management endpoint): heap used avg NaN / max NaN MB, live threads max NaN
  - queue depth: max queued 0, max offered 0, max delivered 0; final acked total 6500
  - fleet: sessions opened 3000, closed 2000, admission refusals 66, other protocol errors {DRAINING=572}

## 7 recovery (same load as phase 2)

- window 30 s: heartbeats 33.3/s, reports 33.3/s, telemetry batches 499.9/s (acked 513.4/s), samples committed 2568.3/s, commands acked 0.0/s
  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat 95.4 / 581.2 / 906.6 / 946.0; report 6.2 / 167.2 / 260.5 / 1055.3; telemetry ack 34.3 / 909.6 / 1072.9 / 1124.9; notice-to-offer NaN / NaN / NaN / NaN
  - gateway container (cgroup 2 CPU, 1g): CPU avg 160% / max 213% of one core, memory avg 855 / max 858 MB; database container: CPU avg 143% / max 189%, memory max 228 MB
  - gateway JVM (management endpoint): heap used avg 170 / max 259 MB, live threads max 45
  - queue depth: max queued 0, max offered 0, max delivered 0; final acked total 6500
  - fleet: sessions opened 3000, closed 2000, admission refusals 66, other protocol errors {DRAINING=572}


## Interpretation limits

One machine, one gateway replica, a synthetic fleet on the same host as the containers (so the load generator competes with them for CPU), PostgreSQL with default tuning apart from max_connections. The numbers bound what this build does here; they are not a promised capacity, and they say nothing about a multi-replica deployment (not supported in this build) or a real WAN.

Total run time 289 s.
