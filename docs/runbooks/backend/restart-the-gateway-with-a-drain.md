# Restart the gateway (planned drain), or drain one agent

| | |
|---|---|
| **Applies to** | the `sm-launcher` container with the agent gateway enabled; `AGW-D18`; the operator verb `drainAgent` (plan 015 T4.3) |
| **Severity** | routine |
| **Est. duration** | the drain (seconds, up to `drain-window`, 30 s by default) plus start-up; for one agent, the time that agent needs to finish its work |
| **Reversible?** | Yes. A drain deletes nothing and revokes nothing; any work it cut short is re-notified on the next session. |
| **Status** | **`UNTESTED` as a procedure.** The mechanisms are tested: `AgentGatewayServiceTest` (the drain message, only that agent, forced close at the window), `AgentDrainCompositionIT`, `AgentEndpointsIT`. The run against the real Go agent is plan 015 T4.4 (`agent-live`) and G-A1 (T5.1); their evidence is recorded separately. The earlier revision of this runbook cited `ContainerPackagingIT` and `AgentFleetDrainIT` runs on a different branch; those are not evidence for this tree. |

## When to use

- **Whole gateway:** any planned stop of the launcher process: an upgrade, a configuration change, a host reboot, the maintenance restart after rotating a runtime database role password.
- **One agent:** you want one agent to finish what it holds and come back (a host maintenance window, before an agent upgrade) without stopping the gateway.

## Preconditions

- **The grace periods nest.** The container's stop grace period (compose `stop_grace_period`, **90 s**) must exceed `SM_SHUTDOWN_TIMEOUT` (**60 s**), which must exceed `SM_AGENT_GATEWAY_DRAIN_WINDOW` (**30 s**) plus the transport grace (**5 s**). `SM_AGENT_GATEWAY_DRAIN_WINDOW` and `SM_SHUTDOWN_TIMEOUT` reach the container from `.env` (compose passes both); **`stop_grace_period` is hard-coded in `runtime/compose.yml`**, so if you raise either value, edit it there too, or the runtime `SIGKILL`s a drain in progress (safe, see Rollback, but the drain is lost).
- No migration is pending that the new image needs ([`enable-agent-gateway.md`](enable-agent-gateway.md), step 2).
- For `drainAgent`: a principal with `agent.drain` at the agent's environment (Administrator and Operator hold it).

## Steps: restart the whole gateway

```bash
docker compose stop launcher          # or: docker stop -t 90 <container>
# ... upgrade / change ...
docker compose up -d launcher
```

On `SIGTERM` the gateway closes the readiness socket first (a balancer stops sending new connections), refuses new sessions as draining, tells every live agent to finish and reconnect on a **staggered** schedule (the property `sm.agent.gateway.drain-reconnect-min`, 2 s, plus a share of `drain-reconnect-spread`, 30 s; neither is mapped to an environment variable, so changing them needs an overlay property), closes each session as the agent answers or forces it at the window, stops the transport, and the JVM exits with **143** (the convention for a signal-initiated exit; not a failure when the drain line is in the log).

## Steps: drain one agent

`drainAgent`: `POST /api/dp/agents/{agentId}/drain`, with the CSRF token.

- `202 {agentId, requestedAt}`: the `Drain` was **sent** to the agent's live session. The request is accepted, not complete. The agent stops taking new work, finishes what it holds, answers `DrainComplete` and reconnects after the gateway-assigned delay (`drain-reconnect-min`, 2 s by default). A session that has not answered when the drain window ends is closed.
- `409 AGENT_NOT_CONNECTED`: **nothing was sent and nothing is queued**: the agent has no live session on this gateway, or its session is already draining (a repeated request sends nothing).
- `404`: the agent is not visible at your scope.
- **Audit.** A drain that was sent writes an `agent_event` row `code = 'gateway.drain'`, `detail_code = 'DRAINING'`, `request_id = 'gateway.drain-<uuid>'`. The reason `operator-drain` reaches the `Drain` notice the agent receives but is **not stored**, so the row cannot be told from a planned-restart drain (the contract text and a comment in `AgentOrchestrator` say otherwise; the contract text is corrected in this fix round, the comment is owed to the Final).

Draining does **not** revoke and loses no command.

## Verification

- **Whole gateway:** the container log has `agent-gateway drained: sessions=N answered=N forced=0`. `forced>0` means that many agents did not answer inside the window (frozen, or the window is too short for their work): their unfinished commands stay unfinished and are re-notified when they reconnect. `docker inspect -f '{{.State.ExitCode}}'` is `143` after a clean drain and `137` if the runtime had to kill it.
- After `up`: the container becomes healthy, and each agent returns on a **higher** fencing epoch at its assigned time (`listAgents`: `session.state` back to `connected`, `lastSeenAt` advancing). Nothing ran twice: compare the host-side effect (the file, the container's start time), not only a row.
- **One agent:** `listAgents`/`getAgent` shows the session `disconnected` and then `connected` again, after about the reconnect delay.

## Limits that apply to a drain (read before relying on it)

- **Single replica.** `drainAgent` reaches **only the sessions the gateway process holds**. With a second gateway replica an agent connected there is `409 AGENT_NOT_CONNECTED` from here. The same single-replica reach applies to the **declarations-changed push**: a change to an instance's declared metrics is signalled to the agents' sessions held by this process; **a second replica is not told**, and the agent picks the change up at its next lease renewal (the backstop). No multi-replica deployment is supported.
- **A draining session can still be prompted to renew and be re-leased** (W4 seam review, F3, a NIT): only the declarations push checks the draining flag. It does not defeat the drain (the session closes at the window) but a lease may be issued during it. To be settled against the real agent in T4.4; if it is not settled before release, it is a known limit.
- Reconnect after a drain takes seconds, but after an **outage** (a crash or a long stop) an agent that has backed off can take up to **5 minutes** (full jitter; see `enable-agent-gateway.md`, Limits).
- The planned stop drains; a crash does not. After `docker kill -9` or an OOM kill, agents' unacknowledged commands are re-notified on reconnect and the agent answers a repeat from its journal (a finished command replays its stored outcome; it is never run twice), and the epoch fence refuses the dead process's session.

## Rollback

There is nothing to roll back: a drain deletes nothing. If the process was killed mid-drain (`137`), start the container again; the fence and the journal are what keep a command from running twice. For one agent, an agent that did not come back is in [`revoke-or-change-an-agent.md`](revoke-or-change-an-agent.md) (is it revoked, is its certificate expired) or [`enable-agent-gateway.md`](enable-agent-gateway.md) (a first-issue agent that never gets a lease).

## Escalation

Backend owners. Include the drain log line, the `agent_event` rows for `gateway.drain*`, and any command not in a terminal state. Never include a token, a key or a rendered configuration.
