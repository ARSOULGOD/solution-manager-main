# Plan 011 P7.2 — failure and recovery matrix (real Go agent, real DP, PostgreSQL 14, Docker)

Generated 2026-10-07T10:21:38.152208Z by the integration test; do not edit by hand.

| Scenario | Verdict |
|---|---|
| M01 First enrollment | PASS — ACTIVE at fencing epoch 1 |
| M02 Agent restart | PASS — resumed at epoch 2 without re-enrolling |
| M03 Duplicate command | PASS — one command row, one container start, across an enqueue replay and an agent reconnect |
| M04 Hash mismatch | PASS — both refusals coded, the second audited, no host write |
| M05 Lost CommandReceived | PASS — terminal ack recorded without a delivery ack; executed once |
| M06 Lost terminal ack | PASS — re-notified, replayed from the agent journal, ACKED:APPLIED, one start |
| M07 Report lands after the apply acknowledgement (the unexplained extra command) | PASS — two commands, no retry, with the report delayed 3 s |
| M08 Gateway restart (planned drain with work in flight) | PASS — drained with the command in flight; outcome durable before shutdown; reconnected; one start; telemetry resumed |
| M09 DP restart (full process state rebuilt from the database) | PASS — command survived a DP restart and ran once |
| M10 Split brain (a second gateway claims the agent) | PASS — epoch 5 -> 7; the stale epoch was refused |
| M11 Split brain, hard mode: two live agents with one identity | PASS — bounded reclaim rate under a duplicated identity |
| M12 Permission change under a live session | PASS — lease re-issued both ways without a reconnect |
| M13 Telemetry reject (unknown series) | PASS — rejected with a code, later batches continued |
| M14 Telemetry pause (zero credits) | PASS — paused without false acks, resumed, nothing lost |
| M15 Revocation under load | PASS — stopped in 113 ms; no telemetry, no work, no reconnect |

## M01 — First enrollment

**Expected:** Empty agent data dir -> Enroll -> Connect; certificate and endpoint persist; session reaches ACTIVE; token consumed once

**Observed:**
- session epoch 1, one identity, token consumed; agent health: {"session_phase":"ACTIVE","authority_phase":"ACTIVE","expires_at":"2026-10-07T10:35:11.48Z","clock_offset_ms":-1,"at":"2026-10-07T10:20:11.615879Z"} 

**Verdict:** PASS — ACTIVE at fencing epoch 1

## M02 — Agent restart

**Expected:** Kill/restart the agent with the same data dir: no re-enrollment, a new fencing epoch, work resumes

**Observed:**
- epoch 1 -> 2 after a SIGKILL of the agent; still one identity and one enrollment

**Verdict:** PASS — resumed at epoch 2 without re-enrolling

## M03 — Duplicate command

**Expected:** Redeliver the same key and content: one durable command, one host operation, even across a reconnect

**Observed:**
- rows for the key: 1; docker start events since enqueue: 1

**Verdict:** PASS — one command row, one container start, across an enqueue replay and an agent reconnect

## M04 — Hash mismatch

**Expected:** Same key with different content is refused at the DP; a payload that does not match its hash is refused by the agent and audited; nothing is written

**Observed:**
- DP refused the conflicting enqueue: KEY_HASH_MISMATCH
- agent refused the tampered payload (KEY_HASH_MISMATCH); host file unchanged; audit rows gateway.command.refused=1

**Verdict:** PASS — both refusals coded, the second audited, no host write

## M05 — Lost CommandReceived

**Expected:** The delivery acknowledgement never reaches the DP: the agent still executes exactly once and the terminal outcome closes the command

**Observed:**
- received_at is null (the drop took effect), outcome ACKED:APPLIED, docker starts=1, injected calls: [claim, renew:REFUSED:LEASE_RENEW_REFUSED, renew:REFUSED:LEASE_RENEW_REFUSED, renew:REFUSED:LEASE_RENEW_REFUSED, renew:REFUSED:LEASE_RENEW_REFUSED, renew:REFUSED:LEASE_RENEW_REFUSED, claim, claim, claim, claim, claim, claim, claim, claim, claim, claim, claim, claim, received:DROPPED]

**Verdict:** PASS — terminal ack recorded without a delivery ack; executed once

## M06 — Lost terminal ack

**Expected:** The agent's terminal CommandAck never reaches the durable record: the DP re-notifies within notice-retry, the agent replays its stored outcome, the command closes, nothing executes twice

**Observed:**
- host executed; command state with the ack dropped: DELIVERED:-
- closed 1162 ms after the host operation was seen
- docker starts=1

**Verdict:** PASS — re-notified, replayed from the agent journal, ACKED:APPLIED, one start

## M07 — Report lands after the apply acknowledgement (the unexplained extra command)

**Expected:** preflight then apply, each exactly once: the engine's read-back waits for the report that proves the apply instead of failing the attempt and enqueueing a second APPLY

**Observed:**
- request STAGED; commands this request produced: [PREFLIGHT:ACKED:PREFLIGHT_OK:gen=6:attempt=01a115e1-0233-7860-b917-7fdd2ed63bc7, APPLY:ACKED:APPLIED:gen=7:attempt=01a115e1-02b2-7154-b031-18fbe7a5410e]
- apply attempts: [PREFLIGHT SUCCEEDED , MATERIALIZE SUCCEEDED ]

**Verdict:** PASS — two commands, no retry, with the report delayed 3 s

## M08 — Gateway restart (planned drain with work in flight)

**Expected:** Stopping the DP drains: the in-flight command's terminal outcome is recorded before the session closes, the agent answers DrainComplete, reconnects on the assigned stagger after the DP is back, nothing executes twice, telemetry credits recover

**Observed:**
- DP stopped 2559 ms after the drain began
- command state once the DP is down: ACKED:APPLIED
- reconnected 482 ms after the DP came back; epoch 3 -> 4
- docker starts=1; telemetry watermark 14 -> 18

**Verdict:** PASS — drained with the command in flight; outcome durable before shutdown; reconnected; one start; telemetry resumed

## M09 — DP restart (full process state rebuilt from the database)

**Expected:** Work queued, DP restarted, agent returns: the commands are reconstructed from the database and run once

**Observed:**
- queued across a DP restart; completed after reconnect; docker starts=1

**Verdict:** PASS — command survived a DP restart and ran once

## M10 — Split brain (a second gateway claims the agent)

**Expected:** One fencing epoch owns writes: the rival's claim fences the incumbent, the incumbent's agent is told it was superseded and reclaims, and the rival's own writes are then refused STALE_EPOCH

**Observed:**
- rival claimed epoch 6 (incumbent was 5)
- agent reclaimed at epoch 7
- the rival's heartbeat is refused: STALE_EPOCH

**Verdict:** PASS — epoch 5 -> 7; the stale epoch was refused

## M11 — Split brain, hard mode: two live agents with one identity

**Expected:** Two processes presenting the same certificate must not thrash the DP: claims are bounded, and one of them ends up serving

**Observed:**
- claims in 15 s with two agents sharing one identity: 5

**Verdict:** PASS — bounded reclaim rate under a duplicated identity

## M12 — Permission change under a live session

**Expected:** Narrowing the agent in the control plane re-issues its lease within the revocation-check interval; work outside the new lease is never offered; restoring it releases the held work

**Observed:**
- lease narrowed 126 ms after the CP change
- after restoring the capability the held command completed: ACKED:PREFLIGHT_OK

**Verdict:** PASS — lease re-issued both ways without a reconnect

## M13 — Telemetry reject (unknown series)

**Expected:** A batch the DP cannot map is rejected with a code and recorded as a gap; later batches continue to commit

**Observed:**
- rejected with UNDECLARED_LABEL (audited); watermark held at 31
- after the mapping returned, metrics_raw 31 -> 32; gaps recorded=1

**Verdict:** PASS — rejected with a code, later batches continued

## M14 — Telemetry pause (zero credits)

**Expected:** While the gateway grants no credits nothing is falsely acknowledged and the agent buffers; once credits return the buffer drains with no samples lost

**Observed:**
- watermark 33 -> 33 while paused for 8 s (no ack without a commit)
- resumed: watermark 33 -> 35, samples 32 -> 33

**Verdict:** PASS — paused without false acks, resumed, nothing lost

## M15 — Revocation under load

**Expected:** Revoking in the control plane stops an active session within the check interval: Revoke is sent, telemetry stops committing, new work is never offered, a restarted agent cannot claim, every stale identity write is refused

**Observed:**
- agent halted 113 ms after the CP revocation; health: {"session_phase":"HALTED","authority_phase":"REVOKED","expires_at":"0001-01-01T00:00:00Z","clock_offset_ms":-2,"halted_reason":"IDENTITY_REVOKED","at":"2026-10-07T10:21:27.486556Z"} 
- telemetry samples: before=38 at revoke=38 6 s later=38
- the in-flight command ended as: DELIVERED:- (the host operation may have finished; the DP does not record an outcome after revocation)
- restarted agent refused at claim: {"session_phase":"HALTED","authority_phase":"NONE","expires_at":"0001-01-01T00:00:00Z","clock_offset_ms":0,"halted_reason":"IDENTITY_REVOKED","at":"2026-10-07T10:21:38.146603Z"} 

**Verdict:** PASS — stopped in 113 ms; no telemetry, no work, no reconnect
