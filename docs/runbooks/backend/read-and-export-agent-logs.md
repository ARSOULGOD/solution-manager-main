# Read and export an agent placement's logs

| | |
|---|---|
| **Applies to** | the Data Plane and agent gateway (`backend/dp-core`, `backend/agent-gateway`, `backend/console-gateway`) serving a `host-local` agent that runs with the `logs` role (plan 016). The agent side is in [`go-agent/RUNBOOK.md`](../../../go-agent/RUNBOOK.md#logs). |
| **Severity** | routine; a stuck or refused export is urgent only if the log is the evidence for an incident |
| **Est. duration** | minutes |
| **Reversible?** | Yes; reading changes nothing. Lowering a placement's cap on the agent **deletes** the oldest stored lines (see the agent runbook). |
| **Status** | **`UNTESTED` end to end.** Each piece ran under Docker Maven and `fake-gateway`; no real agent, gateway and container were run together, and the launcher integration tests cannot load (see [Known limits](#known-limits)). |

## When to use

An operator needs the container log of a placement whose target is an agent (`readLogs` for a page, `exportLogs` for a
download), or a download was refused, cut short or has to be resumed. SSH placements keep the existing SSH read; they have no
export.

## Preconditions

- The principal holds `logs.read` at instance scope. **There is no new verb**: `readLogs` and `exportLogs` both use it
  (`contracts/openapi/console.yaml`). SM does **not** redact log text, so grant it narrowly.
- The agent runs with `--roles ...,logs` and is connected with the logs capability; the placement is in its lease.

## Properties

Data Plane (`sm.dp.logs.*`, `LogConfiguration`):

| Property | Default | Meaning |
|---|---|---|
| `agent-request-deadline` | `5s` | How long a page read waits for the agent. |
| `agent-max-concurrent-reads` | `8` | Global bound on page reads in flight; the gateway also bounds 4 per agent (the agent's own `BUSY` is a page, not an error). |
| `export-max-bytes` | `52428800` (50 MiB, min 1024) | Size of one download: every UTF-8 byte written counts, markers included. |
| `export-deadline` | `5m` | The longest one export runs. |
| `export-max-concurrent` | `2` | Exports in flight on this Data Plane; one more is `429`, never a queue. |
| `export-start-timeout` | `5s` | How long `open` waits for the agent's first answer, so a refusal is a status and not a file. |
| `agent-max-cap-bytes` | `1073741824` (1 GiB) | Largest per-placement cap `PlacementLogRetention.setCap` accepts; `0` is always allowed. |

Gateway (`sm.gateway.logs.*`): `export-max-threads` (default `4`), the console gateway's own export threads, separate from the
metrics export's. The async backstop is `sm.dp.logs.export-deadline` plus one minute, so the Data Plane's marker always comes
first. That coupling reads a Data Plane property from the gateway; it works only while the two are co-deployed.

## Export statuses

`GET /dp/instances/{instanceId}/logs/{component}/export` (`exportLogs`). Everything below is decided **before the first byte**:

| Status | Meaning | Operator action |
|---|---|---|
| `429` `RATE_LIMITED`, `reason: log-export-concurrency`, `Retry-After` | `export-max-concurrent` exports (or the gateway's `export-max-threads`) are running. | Wait `Retry-After` seconds and retry. Raise the bound only after reading [Memory](#memory-envelope). |
| `503` `SERVICE_UNAVAILABLE` (fixed `detail`) | The agent cannot serve it now: not connected, no log support, not collecting (cap `0`), busy, silent past `export-start-timeout`, or its connection ended. | Check the agent (`listAgents`), its `logs` role and the placement's cap, then retry. |
| `404` | Unknown or foreign instance, component or placement. | Fix the id. |
| `400` | Ambiguous placement (pass `placementId`) or a bad `fromSeq` (not a non-negative integer). | Fix the request. |
| `501` `AGENT_OPERATION_UNSUPPORTED` | The placement is on an SSH target. The title reads inverted; it means the *agent* operation does not apply. | Use `readLogs`. |

## Marker lines and resuming

After the first byte the status cannot change, so every later event is a line starting `# sm-export:` with fixed text (never a
host, path, container or secret):

- `gap <REASON> from <instant> to <instant>` where lines are missing (`DROPPED_OLDEST`, `COLLECTION_PAUSED`,
  `CONTAINER_UNREADABLE`, `CAP_REDUCED`, `OVERSIZE`, `UNKNOWN`).
- `truncated: the size limit was reached`, `truncated: the time limit was reached`, `incomplete: the agent's connection was
  lost`, `incomplete: the agent could not read the rest of the log`, `incomplete: the export ended unexpectedly`, each followed
  by `; resume from seq <n>`.
- `complete, with gaps` as the last line of a finished download that had gaps.

**Rule:** no marker, or `complete, with gaps`, is complete. Any `truncated` or `incomplete` marker is not. On an abnormal end
the gateway writes the marker and then **aborts the connection without the chunked terminator**, so the client sees a failed
transfer.

**Resume:** call again with `fromSeq=<n>` from the marker. It may repeat up to one chunk of lines already written and never loses
one. A `fromSeq` older than the ring keeps starts at the oldest retained line. A line the agent cut ends with
`[line truncated]`.

## `readLogs` on an agent placement

The page carries two optional additive fields, **agent placements only** and omitted when empty (the SSH wire is unchanged):
`gaps[{from,to,reason}]` (holes inside the window shown) and `truncated` (the agent cut the page). A client that ignores them
sees a page that looks continuous when it is not; the console does not yet render them.

## Memory envelope

Heap of the process hosting the Data Plane and gateway. One export holds about `credits + 2` chunks of up to 4 MiB (about 40 MiB)
and rendering roughly doubles that: about 80 MiB per export, 160 MiB for the default 2. A page is one agent message of up to
16 MiB and up to `agent-max-concurrent-reads` (8) run at once: 128 MiB. Worst case with both busy is a few hundred MiB. Size the
heap for it, or lower `export-max-concurrent` / `agent-max-concurrent-reads`.

## Known limits

- **M-1 residual (agent side).** A stalled `Logs` stream stalls **all** log replies on that session, and refusals spawn
  unbounded goroutines. A gateway that stops reading the stream is the trigger. Symptom: every page and export for that agent
  times out (`503`, `TIMEOUT` pages) while Connect stays healthy. Remedy: drain and restart the agent session. Tracked in
  plan 016 Results.
- **A placement leaving the lease** ends its pending pages (`PLACEMENT_NOT_IN_LEASE`) and exports at once.
- **Agent restart (M-2):** resume is by timestamp, so a line from the other stream that completed later than the stored maximum can
  be skipped (millisecond window).
- **Downloads are not audited** (spec open item 5; owner call, see `DECISIONS.md`).
- **The cap cannot be set over REST.** `PlacementLogRetention.setCap` is internal; the agent applies its own default until a cap
  is pushed. Which existing verb authorizes a REST write is an open owner question.

## Verification

Request `exportLogs` for a live agent placement: expect `200` with `Content-Disposition: attachment`, lines `<instant> <text>`
and no `# sm-export:` line, or only `complete, with gaps`. Start a third concurrent export: expect `429` with `Retry-After`.

## Rollback

Nothing to undo. To stop serving logs, set the placement's cap to `0` on the agent (deletes stored lines) or remove the `logs`
role.

## Escalation

Project owner, for the cap verb and the download-audit questions; the agent-gateway owner for a stalled stream.
