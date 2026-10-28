# `host-local` agent — managed-integration logs fetched on demand from `/actuator/logfile`

| | |
|---|---|
| **Component** | `go-agent` + `contracts/agent` + `agent-gateway` + `dp-core` |
| **Date** | 2026-10-28 |
| **Status** | **DRAFT — brainstormed with the project developer; not reviewed, not ratified.** `PROPOSED` like every `AG-D*`/`AGW-D*` (`I4`). Decision ids are not allocated here: `DECISIONS.md` is the one central allocator. |
| **Supersedes, in part** | [`2026-10-20-agent-mi-logs-design.md`](2026-10-20-agent-mi-logs-design.md): its §2 rows 1 and 2, §3 (collector, ring store, cap), the `LogRetention` half of §4, the cap, gap and "export follows the live ring" notes of §4.1 and §4.2, §5 "Cap", §6 "Status of the cap write", the store-related rows of §7, and §9 items 1, 2 (the cap default) and 4. Everything else in it stands. |

## 1. Why

The managed integration (MI, a Spring Boot application) now exposes its own log file through Spring Boot's built-in
`/actuator/logfile` endpoint, switched on by configuration. The agent therefore no longer needs to follow container output,
keep a copy on the host, or have the data plane (DP) size that copy. What the console offers stays the same: **the last
screenful, polled live, and a full download.**

## 2. Decided in brainstorming

| # | Decision | Whose |
|---|---|---|
| 1 | Source is the MI's built-in `/actuator/logfile`, enabled through configuration. No MI code change (`I1`). | developer |
| 2 | The agent fetches **only when the DP asks**: no background collector, no schedule. | developer |
| 3 | The agent **stores no logs**: no files, no on-disk buffer, nothing in memory beyond the one reply being forwarded. | developer |
| 4 | Both modes stay: a page (the console's last screenful and its live poll) and a full download. | developer |
| 5 | The console and the DP's read and export paths keep working unchanged; the agent answers the same `LogRequest`s. | developer |
| 6 | The DP-set size cap is **removed** end to end: agent, wire, gateway, DP and its table. | developer |
| 7 | The MI log uses Spring Boot's **default log pattern**. | developer |
| 8 | The MI's port is set by an **environment variable on the container**. | developer |

## 3. Facts this design rests on (verified, `I2`)

Checked on 2026-10-28 against Spring Boot's documentation and, for the cache engine's version, its source at `v3.3.5`.

- `logfile` "returns the contents of the logfile (if the `logging.file.name` or the `logging.file.path` property has been set).
  Supports the use of the HTTP `Range` header to retrieve part of the log file's content." Range is not supported under
  Jersey. The response is `text/plain;charset=UTF-8`; a ranged reply is `206 Partial Content` with
  `Content-Range: bytes 0-1023/4707` (the total size after the slash).
  ([actuator API: logfile](https://docs.spring.io/spring-boot/api/rest/actuator/logfile.html),
  [endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html))
- Only `health` is exposed over HTTP by default; `logfile` must be added to `management.endpoints.web.exposure.include`.
- `LogFileWebEndpoint` reads **one file**, `logging.file.name`/`path` or `management.endpoint.logfile.external-file`. Rotated
  archives are never served. Files rotate at 10 MB by default and 7 archives are kept
  ([logging](https://docs.spring.io/spring-boot/reference/features/logging.html)), so a download is **at most the current
  file**: by default up to about 10 MB.
- The default file pattern (`defaults.xml` at `v3.3.5`) begins every entry with `%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}`, e.g.
  `2026-10-20T04:46:06.745Z  INFO 11765 --- …`: millisecond time **with its zone offset**. Continuation lines (a stack trace)
  carry no time.
- The actuator path is `/actuator/{id}` by default. It moves with `management.endpoints.web.base-path`,
  `management.endpoints.web.path-mapping.<id>`, `server.servlet.context-path` (when there is no separate management port) and
  `management.server.base-path` (when there is). `management.server.port` moves it to its own port
  ([monitoring](https://docs.spring.io/spring-boot/reference/actuator/monitoring.html)).
- The main HTTP port defaults to `8080` and may be set as `SERVER_PORT` through relaxed binding
  ([web server how-to](https://docs.spring.io/spring-boot/how-to/webserver.html)).

**Edge cases, verified in source on 2026-10-28** (Spring Framework `v6.1.14`, `HttpRange` and
`AbstractMessageConverterMethodProcessor`; Spring Boot `v3.3.5`, `LogFileWebEndpoint` and the actuator's servlet adapter):

- A suffix range (`bytes=-N`) is honoured. With `N` at or above the file size it returns the whole file as `206`.
- A range starting at or past the end is `416` with `Content-Range: bytes */<size>`. That includes any range on an empty
  file, so an empty file is `416` with `bytes */0`.
- No log file configured, or the file unreadable: the read operation returns `null`, which the actuator turns into `404`
  for a `GET`.

## 4. Agent

### 4.1 Finding the endpoint *(builder's design, settled here)*

- The container is the one resolved for the placement exactly as `apply` and the config view resolve it (`AG-D20`).
- **Port.** `--management.server.port=` launch argument, else env `MANAGEMENT_SERVER_PORT`; if neither, `--server.port=`, else
  env `SERVER_PORT`; else `8080`. That is Spring's own precedence (argument over environment) applied to the same container
  facts `AG-D20` already reads. A value that is not a port number in 1–65535 (a placeholder, `0`, `-1`) is unusable.
- **Address.** The port goes through the adapter's existing `ScrapeAddr` (published host port, else container network IP), as
  telemetry does.
- **Path.** Fixed: `/actuator/logfile`. A moved base path, path mapping or context path is **not supported**; the MI shows as
  unavailable rather than being guessed at.
- **HTTP client.** Telemetry's rules: no proxy, no redirects, a timeout on every call. The timeout is short enough that a page
  still answers inside the DP's page deadline. No credentials are sent: an actuator behind authentication is unavailable.
- Port resolution lives behind the `runtime` adapter seam, beside `ScrapeAddr`, so a future adapter supplies its own.

### 4.2 Turning bytes into lines

- Split on `\n`, drop a trailing `\r`. Decode as UTF-8 as the current serving code does.
- A line whose start parses as the §3 timestamp is an **entry start**; its `ts` is that time in UTC.
- A continuation line takes the `ts` of the entry it belongs to.
- **At the start of a fetched window**, continuation lines whose entry began before the window have no known time: a page
  drops them; a download starting at byte 0 has none.
- **No parsable time at all** (a non-default pattern): lines still flow, stamped with the nearest earlier entry's time or, with
  none, the fetch time. Known consequence: the console's live poll may repeat such lines. A non-default pattern is not
  supported (§9).
- An over-long line is cut and flagged `truncated`, as today.
- `LogLine.stream` is `"file"`. The gateway and DP do not read the field.

### 4.3 A page (`LogRequest` mode `PAGE`)

- Read the end of the file in ranged steps, going back until the request is satisfied: `limit` lines (0 = no line limit) that
  fall strictly inside (`after`, `before`), or the page budget (`Limits.max_message_bytes`) is reached, or the start of the file.
- Reply with exactly one `LogPage`, the newest matching lines oldest first, as today. Nothing is kept once it is sent.
- `after` alone (the console's live poll) usually needs one small read. `before` alone (paging back) may walk further. How far
  each step reads, and whether to search by offset, is the builder's, **as long as memory stays within one page budget plus one
  read step.**

### 4.4 A download (`LogRequest` mode `EXPORT`)

- **Snapshot.** The download covers the file up to the total size reported by its first ranged reply. It does not follow the
  file's growth.
- **One chunk per credit.** Each chunk is one ranged read of up to `Limits.max_batch_bytes`, cut back to the last whole line,
  sent, then dropped. Credits, cancel and the initial-credit rule are unchanged (`2026-10-20` §4.1).
- **`seq` is a byte offset.** `LogExportQuery.from_seq` is the offset to start at (0 = start of file), and each
  `LogChunk.seq` is the offset just after the chunk's last whole line, so it is still the resume point the DP already uses.
- **Rotation.**
  - `from_seq` beyond the current size means the file was replaced: start at 0 and put a `DROPPED_OLDEST` gap first.
  - The size shrinking during a download ends it with a final chunk carrying a `COLLECTION_PAUSED` gap. The DP already renders
    that as "incomplete … resume from seq N".
  - Known limit: a `from_seq` that is inside a replaced file's size cannot be told apart from a valid one. The agent skips to the
    next line start, so the resumed download may silently mix two files (§9).
  - Gap times *(builder's call, settled here)*: a `DROPPED_OLDEST` gap's `from` and `to` are both the first line's `ts`, because
    the lost range's times are unknown. A `COLLECTION_PAUSED` gap's are the last sent line's `ts`.

### 4.5 Refusals and errors

| Situation | Reply |
|---|---|
| Placement not in the lease | `PLACEMENT_NOT_IN_LEASE` (unchanged) |
| More than `DefaultMaxConcurrent` (4) in flight | `BUSY` (unchanged) |
| Container not found | `CONTAINER_MISSING` (reserved until now; now emitted) |
| Port unusable, endpoint unreachable, timed out, `401`/`403`/`404`, or a ranged request answered `200` with the whole file | `NOT_COLLECTING` |
| Read fails mid-download | final chunk with a `COLLECTION_PAUSED` gap (unchanged) |

- A `200` to a ranged request is refused, never read into memory: an MI on Jersey cannot serve ranges.
- As now, a refusal carries an enum reason only, and no MI log text reaches `slog` or an error string.

### 4.6 What goes, what stays

- **Goes:**
  - `internal/logs/collect` and `internal/logs/ring`;
  - the Docker adapter's `FollowLogs` (`internal/runtime/docker/logs.go`);
  - the per-placement collectors, rings, retention handling and backoff in `internal/agent/logs_role.go`;
  - the `--logs-default-max-bytes` flag;
  - `msg.LogRetention` and its codec;
  - the fake gateway's `cap` command and retention sending.
- **Stays:**
  - `--roles …,logs`, `CAPABILITY_LOGS`, the `Logs` RPC;
  - credits, cancel, `DefaultMaxConcurrent`;
  - `ResolveByName` (discovery uses it).
- `internal/logs/serve` keeps its request handling and reads from the new fetcher instead of a ring.
- Because nothing in the logs feature is on `main`, there is no upgrade path: no migration of `<data-dir>/logs/`, no
  deprecated flag kept.

## 5. Wire (`contracts/agent/v1/agent_gateway.proto`)

- **Remove** `message LogRetention` and `GatewayToAgent.log_retention = 15`. In `GatewayToAgent`, add `reserved 15;` and
  `reserved "log_retention";` so the number and name are never reused.
- **Remove** the matching entries from `baseline.json` by hand (`--write-baseline` never drops entries). Step 18's
  `--superset` check passes because its reference, the merge base with `origin/UAT`, has a baseline without them. Verified:
  that merge base (`b0950cd`) has no `LogRetention` entry.
- Update `CAPABILITY_LOGS`'s comment: it now means "understands `LogRequest` and opens the `Logs` RPC; serves the MI's
  `/actuator/logfile`".
- Comment-only updates, no number changes:
  - `LogGap`'s reason list: `CONTAINER_UNREADABLE` and `CAP_REDUCED` are no longer emitted;
  - `LogExportQuery`/`LogChunk`: `seq` is a byte offset of the current file.
- Regenerate the Go (`make proto`) and Java stubs.

## 6. Gateway and data plane — removing the cap

- **`dp-api`:**
  - remove `AgentLogPort.sendRetention`;
  - remove `AgentLogRetentionSource` and its contract kit `AbstractAgentLogRetentionSourceContractTest`.
- **`agent-gateway`:**
  - remove the resend of stored caps on session open and lease renewal;
  - remove the retention wiring in `AgentGatewayService`, `AgentGatewayPortSet` and `AgentGatewayConfiguration`, and the tests
    that cover it.
- **`dp-core`:**
  - remove `PlacementLogRetention`, `PlacementLogRetentionRows` and `JdbcPlacementLogRetentionRepository`;
  - remove their beans in `LogConfiguration` and `LogPersistenceConfiguration`;
  - remove the property `sm.dp.logs.agent-max-cap-bytes` and the tests that cover them.
- **`launcher`:** remove `AgentLogRetentionSourceWiringTest`.
- **Schema:**
  - **Keep** `V053__placement_log_retention.sql` and add `V054` that drops `sm_data.placement_log_retention`, with its
    `CHECKSUMS.sha256` line.
  - V053 cannot simply be deleted. It is on the pushed branch `origin/feature/agent-mi-config-view`, and step 12's base mode
    compares against the merge base with the upstream. It is in no anchor commit, so it is not frozen by `OR-22` itself.
  - Re-pin the table and grant counts in `SmDataBootstrapIT` and `InventorySchemaIT`.
- **Not touched:**
  - `AgentLogService`, `AgentLogExportService` and the routing to SSH for `dp-remote`;
  - `console.yaml`: the cap had no REST write and no verb.
- `2026-10-20` §9 item 1 (which verb authorizes the cap) is **moot** and closes with this change.

## 7. Documentation

- Mark `2026-10-20-agent-mi-logs-design.md` superseded in part, pointing here, without rewriting its body.
- Rewrite the "Logs" demo in `go-agent/RUNBOOK.md`. The demo container must serve a growing file at `/actuator/logfile` with
  `Range` support; the builder picks the image.
- Update `go-agent/CLAUDE.md` (status, the `internal/logs/` row, the `--roles` line).
- Add the MI preconditions to `docs/runbooks/backend/read-and-export-agent-logs.md`:
  - `logging.file.name` or `logging.file.path` set;
  - `logfile` in the exposure list;
  - no moved actuator path;
  - the port in env or arguments.
- No `DECISIONS.md` id is allocated by this document.

## 8. Testing

- **Agent unit:**
  - timestamp parsing (`Z` and `+hh:mm`), continuation lines, the window-start rule, truncation;
  - port precedence, including unusable values;
  - range requests built correctly;
  - handling of `206`, `200`-to-a-range, `404`, `416`, timeouts;
  - rotation, both before resume and mid-download.
- **No local storage.** After pages and a download, nothing new exists under `<data-dir>`. A test proves this and fails if a
  write is added.
- **Bounded memory.** A test proves no read requests more than the page budget or `max_batch_bytes`, and that a `200` body is
  not read.
- **Agent ↔ `fake-gateway`:** against an `httptest` server that serves a growing file with `http.ServeContent`, which honours
  `Range`:
  - page and live poll with `after`;
  - paging back with `before`;
  - download with credits, cancel and resume;
  - rotation mid-download.
- **Java:**
  - the reactor builds;
  - the removed classes' tests are gone;
  - `V054` runs in `SmDataBootstrapIT`;
  - `arch-tests` pass;
  - the existing `AgentLogService`/`AgentLogExportService` tests pass unchanged, proving the DP read path did not move.
- **Contract:** `./contracts/run-checks.sh`, all steps, including 12 and 18.
- **End to end:** still not run against a real gateway, DP and agent together (`L26`); the plan should say so if it remains true.

## 9. Not in scope, and known limits

- **Not in scope:**
  - rotated archives;
  - Kubernetes (no adapter yet);
  - an actuator behind authentication;
  - a non-default log pattern;
  - a moved actuator path;
  - redaction;
  - console changes;
  - any MI change (`I1`);
  - SM writing the MI's logging or exposure settings.
- **Never:**
  - a local copy of MI logs;
  - a background fetch;
  - more than one reply's worth of log text held in memory;
  - MI log text in the agent's own log.
- **Known limits:**
  - a download is the current file only (by default about 10 MB);
  - resuming across a rotation can mix files (§4.4);
  - a log with a non-default pattern can repeat in the console's live poll (§4.2).
