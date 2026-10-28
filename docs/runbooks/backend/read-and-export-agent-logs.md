# Read and export an agent placement's logs

| | |
|---|---|
| **Applies to** | the Data Plane and agent gateway (`backend/dp-core`, `backend/agent-gateway`, `backend/console-gateway`) serving a `host-local` agent that runs with the `logs` role (plans 016 and 018: the agent reads the MI's `/actuator/logfile` when asked and stores nothing). The agent side is in [`go-agent/RUNBOOK.md`](../../../go-agent/RUNBOOK.md#logs). |
| **Severity** | routine; a stuck or refused export is urgent only if the log is the evidence for an incident |
| **Est. duration** | minutes |
| **Reversible?** | Yes; reading changes nothing, and the agent stores no log text. |
| **Status** | **`UNTESTED` end to end.** Each piece ran under Docker Maven and `fake-gateway`; no real agent, gateway and container were run together (the agent half ran against a stand-in MI and `fake-gateway` on 2026-10-29, see the agent runbook), and the launcher integration tests cannot load (see [Known limits](#known-limits)). |

## When to use

An operator needs the container log of a placement whose target is an agent (`readLogs` for a page, `exportLogs` for a
download), or a download was refused, cut short or has to be resumed. SSH placements keep the existing SSH read; they have no
export.

## Preconditions

- The principal holds `logs.read` at instance scope. **There is no new verb**: `readLogs` and `exportLogs` both use it
  (`contracts/openapi/console.yaml`). SM does **not** redact log text, so grant it narrowly.
- The agent runs with `--roles ...,logs` and is connected with the logs capability; the placement is in its lease.
- The MI is configured to serve its log file: see [MI preconditions](#mi-preconditions). Without that every read and export is refused (`NOT_COLLECTING`).

## MI preconditions

The agent fetches `/actuator/logfile` from the MI itself, so the MI must serve it. These are the MI's deployment configuration: SM never writes them (`I1`). The Spring Boot facts behind them are verified in the [design's §3](../../design/backend/agent-gateway/specs/2026-10-28-agent-mi-logs-from-actuator-design.md) (`I2`).

| Requirement | Detail |
|---|---|
| The MI writes a log file | `logging.file.name` or `logging.file.path` is set. Otherwise `/actuator/logfile` answers `404`. |
| `logfile` is exposed over HTTP | `logfile` is in `management.endpoints.web.exposure.include`; only `health` is exposed by default. |
| The actuator is at `/actuator` | No moved base path, path mapping or context path (`management.endpoints.web.base-path`, `management.endpoints.web.path-mapping.logfile`, `server.servlet.context-path`, `management.server.base-path`). The path `/actuator/logfile` is fixed. |
| The port is declared | In the container's exec-form args `--server.port=` / `--management.server.port=`, or its env `SERVER_PORT` / `MANAGEMENT_SERVER_PORT`; the management port wins, an argument wins over the env, and with none declared the agent uses `8080`. **Not read:** a JVM `-Dserver.port=`, `JAVA_TOOL_OPTIONS`/`JAVA_OPTS`, a shell-form `CMD`; the agent then uses the env value or `8080`. **Refused:** a declared value that is empty or not 1-65535 (a `${PORT}` placeholder, `0`, `-1`) is `NOT_COLLECTING`. |
| The port is published | The actuator's container port is published to the host, as telemetry scraping needs (`ScrapeAddr`). |
| The actuator has no authentication | The agent sends no credentials; a `401` or `403` is `NOT_COLLECTING`. |
| The log pattern is Spring Boot's default | Each entry starts with the default timestamp. Another pattern still flows, but see [Known limits](#known-limits). |
| Downloads cover the current file only | Rotated archives are never served; files rotate at 10 MB by default, so a download is at most about that. |

The placement's container is also resolved as `apply` resolves it (`AG-D20`): the binding label, a declared config location and its bind mount. The agent runbook's [Logs](../../../go-agent/RUNBOOK.md#logs) section shows each refusal reproduced.

## Deploy order

The gateway and DP must not be older than an agent from this change: such an agent drops its control session if an older gateway sends the removed cap message. That can happen only for a placement with a stored cap row; no API can write one, so in practice only a hand-inserted row (`V054` drops the table).

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

Gateway (`sm.gateway.logs.*`): `export-max-threads` (default `4`), the console gateway's own export threads, separate from the
metrics export's. The async backstop is `sm.dp.logs.export-deadline` plus one minute, so the Data Plane's marker always comes
first. That coupling reads a Data Plane property from the gateway; it works only while the two are co-deployed.

## Export statuses

`GET /dp/instances/{instanceId}/logs/{component}/export` (`exportLogs`). Everything below is decided **before the first byte**:

| Status | Meaning | Operator action |
|---|---|---|
| `429` `RATE_LIMITED`, `reason: log-export-concurrency`, `Retry-After` | `export-max-concurrent` exports (or the gateway's `export-max-threads`) are running. | Wait `Retry-After` seconds and retry. Raise the bound only after reading [Memory](#memory-envelope). |
| `503` `SERVICE_UNAVAILABLE` (fixed `detail`) | The agent cannot serve it now: not connected, no log support, not collecting (the MI does not serve its log file, see [MI preconditions](#mi-preconditions)), busy, silent past `export-start-timeout`, or its connection ended. | Check the agent (`listAgents`), its `logs` role and the MI preconditions, then retry. |
| `404` | Unknown or foreign instance, component or placement. | Fix the id. |
| `400` | Ambiguous placement (pass `placementId`) or a bad `fromSeq` (not a non-negative integer). | Fix the request. |
| `501` `AGENT_OPERATION_UNSUPPORTED` | The placement is on an SSH target. The title reads inverted; it means the *agent* operation does not apply. | Use `readLogs`. |

## Marker lines and resuming

After the first byte the status cannot change, so every later event is a line starting `# sm-export:` with fixed text (never a
host, path, container or secret):

- `gap <REASON> from <instant> to <instant>` where lines are missing (`DROPPED_OLDEST`, `COLLECTION_PAUSED`,
  `CONTAINER_UNREADABLE`, `CAP_REDUCED`, `OVERSIZE`, `UNKNOWN`). An agent from plan 018 emits only the first two: a `from-seq` beyond the file's size (the file was replaced) is `DROPPED_OLDEST`, a read that fails or a file that shrinks mid-download is `COLLECTION_PAUSED`.
- `truncated: the size limit was reached`, `truncated: the time limit was reached`, `incomplete: the agent's connection was
  lost`, `incomplete: the agent could not read the rest of the log`, `incomplete: the export ended unexpectedly`, each followed
  by `; resume from seq <n>`.
- `complete, with gaps` as the last line of a finished download that had gaps.

**Rule:** no marker, or `complete, with gaps`, is complete. Any `truncated` or `incomplete` marker is not. On an abnormal end
the gateway writes the marker and then **aborts the connection without the chunked terminator**, so the client sees a failed
transfer.

**Resume:** call again with `fromSeq=<n>` from the marker. It may repeat up to one chunk of lines already written and never loses
one. `fromSeq` is a byte offset in the MI's current log file; one beyond its size means the file was replaced, so the download starts at byte 0 with a `DROPPED_OLDEST` gap first. A line the agent cut ends with
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
- **Resuming across a rotation.** A `fromSeq` that is inside the replaced file's size cannot be told from a valid one: the agent skips to the next line start, so the resumed download may mix two files (design §4.4).
- **Downloads are not audited** (spec open item 5; owner call, see `DECISIONS.md`).
- **Downloads:** the current file only, because rotated archives are not served (design §9).
- **Live poll and same-millisecond lines.** The poll's `after` is strict, and Spring stamps to the millisecond. A line stamped in the same millisecond as the newest line already shown, but written after the poll, is not shown. Nor is a line written a few ms out of time order.
- **Live poll and multi-line entries.** Continuation lines carry their entry's time, so the rest of an entry written after a poll is not shown.
- **Short pages.** A page can return fewer than `limit` lines, because it ends on an entry and time boundary so that paging back with `before` loses nothing.
- **Oversize entries.** One entry larger than a page shows only its newest part.
- **Paging back can stop.** A page ending inside an entry whose start lies beyond the page's look-back reach takes the fetch time, and paging back stops there.
- **Non-default log pattern.** Every line takes the fetch time, and the live poll repeats lines (design §9).

## Verification

Request `exportLogs` for a live agent placement: expect `200` with `Content-Disposition: attachment`, lines `<instant> <text>`
and no `# sm-export:` line, or only `complete, with gaps`. Start a third concurrent export: expect `429` with `Retry-After`.

## Rollback

Nothing to undo: the agent stores no log text, so there is nothing to delete. To stop serving logs, remove `logs` from the agent's
`--roles` and restart it (the DP then answers `503`, no log support), or have the MI stop exposing `logfile` (the MI's own
configuration; reads then answer `NOT_COLLECTING`). The MI's log file is untouched either way.

## Escalation

Project owner, for the download-audit question; the agent-gateway owner for a stalled stream.
