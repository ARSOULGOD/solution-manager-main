# Plan 011 P7.2 — planned drain of a live fleet (three real agents)

Generated 2026-10-07T10:19:58.014278Z by the integration test; do not edit by hand.

| Scenario | Verdict |
|---|---|
| F1 Drain three live agents, one mid-command | PASS — 3/3 answered, none forced; in-flight outcome durable; stagger spread 12000 ms; all back ACTIVE; one start; telemetry resumed |

## F1 — Drain three live agents, one mid-command

**Expected:** Every agent answers DrainComplete (none forced); the in-flight command's outcome is durable before the DP is down; reconnect_after is spread over >= 80% of the configured spread; all return under new epochs; nothing runs twice; telemetry resumes

**Observed:**
- DP stopped 2007 ms after the drain began
- assigned reconnect_after (ms): [1000, 7000, 13000] from [reconnect-after-ms=1000;deadline-ms=30000, reconnect-after-ms=13000;deadline-ms=30000, reconnect-after-ms=7000;deadline-ms=30000]
- all three reconnected 8862 ms after the DP came back
- busy agent docker starts=1; telemetry watermarks advanced for all three

**Verdict:** PASS — 3/3 answered, none forced; in-flight outcome durable; stagger spread 12000 ms; all back ACTIVE; one start; telemetry resumed
