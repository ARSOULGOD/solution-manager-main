# Plan 011 P7.3 — container packaging and operations (real image, real PostgreSQL, real Go agent)

Generated 2026-10-07T10:27:37.330950Z by the integration test; do not edit by hand.

| Scenario | Verdict |
|---|---|
| P01 Reproducible image | PASS — identical image ids across two --no-cache builds |
| P02 Bootstrap and migrate as separate one-shot containers | PASS — phase 0, then phase 1 twice (second a no-op); migrator secrets not echoed |
| P03 Startup from documented inputs; enrolment; real host command | PASS — healthy as non-root with runtime secrets only; enrolled; one real restart |
| P04 Observability without an unauthenticated route | PASS — 401 without the token, 200 with it; the console answers 200 to none of the probe paths |
| P05 Graceful shutdown (docker stop) | PASS — drained in 2 s with exit 143; command ACKED before shutdown; agent reconnected |
| P06 Crash (docker kill -s KILL) mid-command | PASS — completed once after a SIGKILL; exit 137 |
| P07 Persistent state across container recreation | PASS — history intact; agent resumed under a recreated container |
| P08 Missing TLS material | PASS — exit 1 with a named cause and no secret in the log |
| P09 Scheduled maintenance as a deployed job | PASS — two concurrent runs clean; horizon advanced to 18 months; third run a no-op; the runtime role refused |
| P10 compose.yml validates | PASS — valid with the documented variables; refuses to render without them |
| P05b Drain with an agent that never answers; readiness closes first | PASS — readiness closed first; the frozen agent was forced at the window; the agent reconnected once thawed |

## P01 — Reproducible image

**Expected:** Two builds of the same inputs produce the same repackaged jar and the same image id

**Observed:**
- [jar:   cc23153c1951b13eadf7895f2121bf8abb939eab197d09b17933cb0aa0b62e6c  launcher-0.1.0-SNAPSHOT-exec.jar, image: sha256:54eaa1e4984cb3c4ca80d8d2b1504f6e297ab412373c2068e30960838b4c9e0c  (sm-launcher:it-7eeebca4), image: sha256:54eaa1e4984cb3c4ca80d8d2b1504f6e297ab412373c2068e30960838b4c9e0c  (sm-launcher:it-7eeebca4-recheck), REPRODUCIBLE: identical image ids]

**Verdict:** PASS — identical image ids across two --no-cache builds

## P02 — Bootstrap and migrate as separate one-shot containers

**Expected:** Phase 0 (superuser) and Phase 1 (migrator roles, separate JVM) run from the shipped files; a re-run of Phase 1 is a no-op; the application is given neither credential

**Observed:**
- phase 0: phase 0 complete
- first migrate: [migrated 14 (versions 001, 002, 003, 004, 005, 006, 007, 008, 009, 010, 011, 012, 014, 015), migrated 15 (versions 001, 002, 003, 004, 005, 006, 018, 019, 020, 021, 022, 023, 024, 025, 026)]
- second migrate: both schemas 'migrated 0' (no-op)

**Verdict:** PASS — phase 0, then phase 1 twice (second a no-op); migrator secrets not echoed

## P03 — Startup from documented inputs; enrolment; real host command

**Expected:** The container becomes healthy (readiness socket), runs as uid 10001, holds no migrator/superuser secret; the agent enrols through the published port and a command runs on the real target

**Observed:**
- healthy 10 s after start
- readiness socket accepted on the published port

**Verdict:** PASS — healthy as non-root with runtime secrets only; enrolled; one real restart

## P04 — Observability without an unauthenticated route

**Expected:** The management endpoint needs its bearer token; the console serves no actuator or management route; the gateway's meters and the JVM's are scrapeable

**Observed:**
- management health: {"status":"UP","gateway":{"enabled":true,"listening":true,"draining":false,"sessions":1,"maxSessions":2000}}
- console /actuator -> 404
- console /actuator/health -> 404
- console /actuator/metrics -> 404
- console /management/health -> 404
- console /health -> 404
- console / -> 404

**Verdict:** PASS — 401 without the token, 200 with it; the console answers 200 to none of the probe paths

## P05 — Graceful shutdown (docker stop)

**Expected:** SIGTERM drains: the in-flight command's outcome is recorded, the agent is told and answers, the container exits inside the grace period; after docker start the agent returns

**Observed:**
- docker stop returned after 2 s, exit code 143
- [2026-10-07T10:25:58.651Z  INFO 1 --- [ionShutdownHook] com.solace.sm.agw.AgentGatewayServer     : agent-gateway drained: sessions=1 answered=1 forced=0]

**Verdict:** PASS — drained in 2 s with exit 143; command ACKED before shutdown; agent reconnected

## P06 — Crash (docker kill -s KILL) mid-command

**Expected:** No drain, no goodbye: after the container is started again the command completes exactly once and the agent resumes

**Observed:**
- exit code after SIGKILL: 137; command state at the moment of the kill: DELIVERED:-
- docker starts=1; epoch 2 -> 3

**Verdict:** PASS — completed once after a SIGKILL; exit 137

## P07 — Persistent state across container recreation

**Expected:** Removing and recreating the application container keeps the database's history (commands, audit, identity, lease); the agent resumes without re-enrolling

**Observed:**
- commands 3 -> kept; audits 2 -> kept; epoch 3 -> 4

**Verdict:** PASS — history intact; agent resumed under a recreated container

## P08 — Missing TLS material

**Expected:** A deployment whose certificate path is wrong does not start serving: the process exits non-zero quickly, the log names the cause class, and no secret is printed

**Observed:**
- exit code 1; Caused by: java.lang.IllegalStateException: agent-gateway TLS listener could not start

**Verdict:** PASS — exit 1 with a named cause and no secret in the log

## P09 — Scheduled maintenance as a deployed job

**Expected:** The maintenance container uses the migrator credential, advances the partition horizon, prunes by retention, and two concurrent runs are both clean

**Observed:**
- run A: Standard Commons Logging discovery in action with spring-jcl: please remove commons-logging.jar from classpath in order to avoid potential conflicts maintained sm_data: partitionsCreated=6 reportsDeleted=0 auditsDeleted=0 telemetryDeleted=0 gapsDeleted=0
- run B: Standard Commons Logging discovery in action with spring-jcl: please remove commons-logging.jar from classpath in order to avoid potential conflicts maintained sm_data: partitionsCreated=0 reportsDeleted=0 auditsDeleted=0 telemetryDeleted=0 gapsDeleted=0
- metrics_raw partitions 15 -> 21
- with the runtime credential: exit non-zero — Standard Commons Logging discovery in action with spring-jcl: please remove commons-logging.jar from classpath in order to avoid potential conflicts the sm_data maintenance failed: BadSqlGrammarException (SQLSTATE 42501) — no message text is printed because Flyway's includes the connection URL; diagnose with psql using the same credential

**Verdict:** PASS — two concurrent runs clean; horizon advanced to 18 months; third run a no-op; the runtime role refused

## P10 — compose.yml validates

**Expected:** docker compose config accepts the file with the documented variables

**Observed:**
- docker compose config: valid

**Verdict:** PASS — valid with the documented variables; refuses to render without them

## P05b — Drain with an agent that never answers; readiness closes first

**Expected:** The L4 readiness socket stops accepting as soon as shutdown begins (a balancer stops sending new connections) while the drain is still waiting; an agent that never answers is cut off at the drain window, counted forced, and the container still exits inside the grace period; the agent then reconnects normally

**Observed:**
- before stop: the readiness socket accepts
- readiness refused while the container was still running: true (91 ms after the stop began)
- docker stop returned after 25 s; 2026-10-07T10:27:24.659Z  INFO 1 --- [ionShutdownHook] com.solace.sm.agw.AgentGatewayServer     : agent-gateway drained: sessions=1 answered=0 forced=1

**Verdict:** PASS — readiness closed first; the frozen agent was forced at the window; the agent reconnected once thawed
