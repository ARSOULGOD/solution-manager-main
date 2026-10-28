# Agent MI logs from `/actuator/logfile` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The go-agent answers the DP's log page and export requests by reading the MI's `/actuator/logfile` over HTTP when
asked, keeping nothing. The DP-set log cap is removed from the agent, the wire, the gateway, the DP and its table.

**Architecture:**
- A new `internal/logs/logfile` package does ranged HTTP reads of one MI's log file and turns bytes into timed lines.
- `internal/logs/serve` keeps its request, credit and cancel handling, but reads from a per-request `Source` instead of a ring.
- The ring, the collector and the cap go.
- The Java side only loses code, plus one migration that drops the cap table.
- The wire loses `LogRetention` last, once nothing on either side uses it.

**Tech Stack:** Go 1.26 (go-agent, `net/http`), Java 17 / Spring Boot (dp-api, agent-gateway, dp-core, launcher), Flyway,
protobuf (buf 1.73.0).

**Spec:** [`docs/design/backend/agent-gateway/specs/2026-10-28-agent-mi-logs-from-actuator-design.md`](../../design/backend/agent-gateway/specs/2026-10-28-agent-mi-logs-from-actuator-design.md).
Read it, and §4.1–§4.2 of the [2026-10-20 design](../../design/backend/agent-gateway/specs/2026-10-20-agent-mi-logs-design.md)
it amends: the parts not superseded still bind (credits, cancel, refusal shapes, `request_id` rules).

## Global Constraints

- **`I1`:** no MI change. The MI's `logging.file.name`/`path` and `logfile` exposure are its deployment configuration; SM does not write them.
- **`I6`:** no MI log text in `slog`, in an error string or in a refusal. Refusals carry an enum reason only.
- **Never:**
  - a local copy of MI logs;
  - a background fetch;
  - more than one reply's worth of log text in memory: one page budget plus one read step for a page, one `max_batch_bytes` chunk for an export.
- **`I7` / `AGW-D2`:** unchanged. The gateway still holds no datasource.
- **No new permission verb** (`logs.read` stays). **No decision id** is allocated.
- **Migrations:** `V053` stays as written; the cap table is dropped by a new `V054`.
- **Fixed values:**

  | Item | Value |
  |---|---|
  | Endpoint path | `/actuator/logfile` |
  | Default port | `8080` |
  | Port precedence | `--management.server.port=` arg, else env `MANAGEMENT_SERVER_PORT`; then `--server.port=` arg, else env `SERVER_PORT`; usable range 1–65535 |
  | `LogLine.stream` | `"file"` |
  | Page read step | 64 KiB (`logfile.DefaultStep`) |
  | Read timeout | 4 s per open, page and export read (`serve.DefaultReadTimeout`); the DP waits 5 s (`sm.dp.logs.agent-request-deadline`, `sm.dp.logs.export-start-timeout`) |
  | Max requests in flight | `serve.DefaultMaxConcurrent` = 4 (unchanged) |
  | Budget when a `Limits` value is 0 or over 1 GiB | 1 MiB (unchanged) |

- **Timestamps:** an entry starts with Spring Boot's default `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`. Go's `time.Parse(time.RFC3339, token)` parses
  both `…745Z` and `…745+05:30` (run while writing this plan).
- **Commits:**
  - Conventional subjects, as in the repo (`feat(logs): …`, `fix(dp): …`).
  - The message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  - Do not push or merge.

## Decisions made in this plan (the spec left them to the builder)

1. **Empty file.** Two answers both mean "size 0, no lines": Spring's `416` with `Content-Range: bytes */0` (verified in source),
   and Go's `http.ServeContent`, which answers an empty file `200` with `Content-Length: 0` (read in `$GOROOT/src/net/http/fs.go`).
   Any other `200` to a ranged request is `ErrUnavailable` and its body is not read.
2. **Only `\n`-terminated lines are ever sent.** An unterminated tail is the MI mid-write; it is sent by the next request.
3. **A continuation line with no entry start before it in the read** takes `prev` (an export's previous chunk), else the first
   entry time later in the same read, else the fetch time.
4. **Opening a placement happens after admission**, on the request's goroutine, so `Handle` never blocks on Docker. `BUSY` is
   therefore checked before `NOT_COLLECTING`/`CONTAINER_MISSING`.
5. **Export errors.**
   - An error on the **first** read of an export is a refusal page (`NOT_COLLECTING`), so the DP returns a real status
     rather than an incomplete download.
   - A later error ends the export with a `COLLECTION_PAUSED` final chunk (unchanged).
   - A read that returns no lines but moves forward (skipped bytes) is followed by the next read without spending a credit.
6. **Gap times.** A `DROPPED_OLDEST` gap's `From` and `To` are both the first line's `TS`; a `COLLECTION_PAUSED` gap is built
   as today (from the last sent line's `TS` to now).

## Review Focus

1. **A line half-written while the agent reads** is never sent cut. It arrives whole in the next poll. (Task 3
   `TestReadNeverReturnsAnUnterminatedTail`; Task 4 `TestPageLeavesAnUnterminatedTailForTheNextPoll`.)
2. **A stack trace that straddles a chunk boundary** loses no line, and every line of it carries its entry's time. (Task 5
   `TestExportPassesThePreviousTimestamp`; Task 3 `TestReadGivesContinuationLinesTheirEntryTime`.)
3. **The file rotating before a resume or between chunks** gives a marker, never a spin or a crash. (Task 5
   `TestExportFromBeyondTheSizeRestartsWithDroppedOldest`, `TestExportEndsCollectionPausedWhenTheFileShrinks`.)
4. **An MI on Jersey, or one with no log file,** is refused at once, and a whole-file `200` body is never read. (Task 3
   `TestReadRefusesAWholeFileAnswerWithoutReadingIt`; Task 5 `TestLogfileNotExposedIsNotCollecting`.)
5. **The console's live poll when nothing new was written** gets an empty page from exactly one small read. (Task 4
   `TestPageAfterTheNewestLineIsEmptyAfterOneRead`.)

---

## File Structure

| File | Responsibility |
|---|---|
| `go-agent/internal/runtime/runtime.go` | `ActuatorLocator` optional interface, `ErrNoActuatorPort` |
| `go-agent/internal/runtime/docker/actuator.go` (new) | Port from launch args/env, then `ScrapeAddr` |
| `go-agent/internal/discovery/discovery.go` | Forwards `ActuatorAddr` like the other optional capabilities |
| `go-agent/internal/logs/logfile/` (new) | `File`: ranged reads, `Content-Range`, status rules, lines and times, `Read` (forward), `Page` (backward) |
| `go-agent/internal/logs/serve/serve.go` | Requests, credits, cancel; answers from a `Source`; export snapshot, rotation, byte-offset `seq` |
| `go-agent/internal/logs/nostore_test.go` (new) | Import guard proving the logs code cannot write to disk |
| `go-agent/internal/agent/logs_role.go` | Session and stream only; opens a `Source` per request through the adapter |
| `go-agent/internal/logs/ring/`, `logs/collect/`, `runtime/docker/logs.go` | **Deleted** |
| `contracts/agent/v1/agent_gateway.proto`, `baseline.json` | `LogRetention` removed, field 15 reserved |
| `backend/dp-core/.../sm_data/V054__drop_placement_log_retention.sql` (new) | Drops the cap table |
| `backend/` cap classes and tests (Task 1 lists them) | **Deleted** or trimmed |

---

### Task 1: The DP and gateway stop using the cap; the table is dropped

**Files:**
- Delete:
  - `backend/dp-api/src/main/java/com/solace/sm/dp/api/agw/AgentLogRetentionSource.java`
  - `backend/dp-api/src/test/java/com/solace/sm/dp/api/agw/kit/AbstractAgentLogRetentionSourceContractTest.java`
  - `backend/dp-core/src/main/java/com/solace/sm/dp/logs/PlacementLogRetention.java`
  - `backend/dp-core/src/main/java/com/solace/sm/dp/logs/PlacementLogRetentionRows.java`
  - `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/JdbcPlacementLogRetentionRepository.java`
  - `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/LogPersistenceConfiguration.java` (its only bean is the repository)
  - `backend/dp-core/src/test/java/com/solace/sm/dp/logs/PlacementLogRetentionTest.java`
  - `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/JdbcPlacementLogRetentionRepositoryIT.java`
  - `backend/dp-core/src/test/java/com/solace/sm/dp/DataPlaneLogRetentionWiringTest.java`
  - `backend/launcher/src/test/java/com/solace/sm/launcher/AgentLogRetentionSourceWiringTest.java`
- Modify, `dp-api`: `AgentLogPort.java` (drop `sendRetention`, fix the class comment), `kit/AbstractAgentLogPortContractTest.java`.
- Modify, `agent-gateway` main: `AgentGatewayService.java`, `AgentLogBroker.java`, `AgentGatewayPortSet.java`, `AgentGatewayConfiguration.java`.
  - Drop `retentionSent`, `pushRetention`, `sendRetention`, `sendLogRetentions` and its call at the lease event.
  - Drop the `Link.pushRetention` method, the `logRetention` port and the `ObjectProvider<AgentLogRetentionSource>`.
- Modify, `agent-gateway` tests: `AgentGatewayConfigurationTest`, `AgentGatewayLogsTest`, `AgentGatewayServiceTest`,
  `AgentLogBrokerOrderingTest`, `AgentLogPortGatewayContractTest`, `LogsHarness`.
- Modify, `dp-core`:
  - main: `logs/LogConfiguration.java` (drop the `placementLogRetention` bean, the `sm.dp.logs.agent-max-cap-bytes` property and
    the `LogPersistenceConfiguration` import), `logs/AgentLogService.java` (`absentPort`);
  - tests: `logs/AgentLogServiceTest.java`, `logs/AgentLogExportServiceTest.java`,
    `apply/persistence/SmDataBootstrapIT.java`, `apply/persistence/InventorySchemaIT.java`.
- Create:
  - `backend/dp-core/src/main/resources/db/migration/sm_data/V054__drop_placement_log_retention.sql`;
  - a line in that directory's `CHECKSUMS.sha256`.
- Docs:
  - `docs/design/DECISIONS.md` open item 49;
  - `docs/runbooks/backend/read-and-export-agent-logs.md`.

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: no Java source refers to `LogRetention`, so Task 8 can remove the message. `AgentLogPort` keeps `requestPage` and
  the export methods unchanged.

- [ ] **Step 1: Establish the Java test command and a baseline.**
  - Start Docker Desktop. Run Maven in `maven:3.9-eclipse-temurin-17`, with the repo and the Docker socket mounted, as plan 016's
    Results describe. Testcontainers needs the socket override in repo lesson R10.
  - ⚠️ That exact invocation is recorded nowhere in the repo and **was not run while writing this plan**. Docker was down, and
    the host `~/.m2` lacks the test dependencies offline.
  - Do not resolve online into the shared `~/.m2` (repo lesson on the offline licence gate).
  - Run `dp-api`, `agent-gateway` and `dp-core`'s `SmDataBootstrapIT`, `InventorySchemaIT`, `AgentLogServiceTest` and
    `AgentLogExportServiceTest`.
  - Record the command and the result in this plan's Results. Expected: green except plan 017's listed pre-existing reds.
- [ ] **Step 2: Write the failing test.** In `SmDataBootstrapIT`:
  - add `"054"` to the pinned migration list;
  - change the table pin from 37 to 36, and its message to say `V054` drops `placement_log_retention`;
  - add an assertion that `SELECT to_regclass('sm_data.placement_log_retention')` is `null`.
- [ ] **Step 3: Run it.** `SmDataBootstrapIT` → FAIL on the migration list (no `054`).
- [ ] **Step 4: Create `V054`.**
  - House-style header comment: plan 018, the 2026-10-28 design §6, and why `V053` stays (it is on the pushed branch; step 12's
    base mode compares against the upstream merge base).
  - The statement: `DROP TABLE sm_data.placement_log_retention;`.
  - A `-- ROLLBACK:` note: re-running `V053`'s `CREATE` and grants restores an empty table.
  - Append `sha256sum V054__drop_placement_log_retention.sql` to `CHECKSUMS.sha256` in the existing two-space format.
- [ ] **Step 5: Run it.** `SmDataBootstrapIT` → PASS.
- [ ] **Step 6: Remove the cap code** listed under Files.
  - In `InventorySchemaIT`, remove the `placement_log_retention` assertions; the table no longer exists after migration.
  - In the gateway tests, remove the retention-source fixtures and the cases that assert a `LogRetention` was sent.
- [ ] **Step 7: Prove nothing is left.**
  - Run `grep -rnE 'LogRetention|logRetention|placement_log_retention|sendRetention|pushRetention|agent-max-cap|retentionSent' backend --include=*.java --include=*.properties --include=*.yml | grep -v '/target/'`.
  - Expected: no output.
- [ ] **Step 8: Run the suites from Step 1**, plus `launcher` tests by reading where the launcher context is a known red, and
  `arch-tests`. Expected: green except the known reds.
- [ ] **Step 9: Docs.**
  - `DECISIONS.md` open item 49: mark it **CLOSED 2026-10-28, moot**. The cap it asked a verb for is removed by the 2026-10-28
    design §6. Keep the row's text and add the closing note, as item 38 was closed.
  - The DP runbook: remove the `agent-max-cap-bytes` row and the "cap cannot be set over REST" bullet.
- [ ] **Step 10: Commit** (`fix(dp): remove the agent log cap; V054 drops placement_log_retention`).

### Task 2: The agent finds the MI's actuator

**Files:**
- Modify:
  - `go-agent/internal/runtime/runtime.go`;
  - `go-agent/internal/discovery/discovery.go` and `discovery_test.go`.
- Create:
  - `go-agent/internal/runtime/docker/actuator.go`;
  - `go-agent/internal/runtime/docker/actuator_test.go`.

**Interfaces:**
- Produces:
  ```go
  // runtime
  var ErrNoActuatorPort = errors.New("docker: the container's actuator port is not a usable port number")
  type ActuatorLocator interface {
      // ActuatorAddr is the host:port the placement's Spring Boot actuator answers on.
      ActuatorAddr(ctx context.Context, p Placement) (string, error)
  }
  // docker
  func actuatorPort(args, env []string) (int, error)   // pure; args = Entrypoint+Cmd, env = Config.Env
  func (a *Adapter) ActuatorAddr(ctx context.Context, p runtime.Placement) (string, error)
  // discovery
  func (a *Adapter) ActuatorAddr(ctx context.Context, p runtime.Placement) (string, error) // inner's, else ErrUnsupported
  ```

- [ ] **Step 1: Write the failing tests.**
  - `TestActuatorPortPrecedence`, a table:

    | Input | Result |
    |---|---|
    | nothing | 8080 |
    | `SERVER_PORT=9000` | 9000 |
    | `MANAGEMENT_SERVER_PORT=9100` with `SERVER_PORT=9000` | 9100 |
    | `--server.port=9200` with `SERVER_PORT=9000` | 9200 |
    | `--management.server.port=9300` with `MANAGEMENT_SERVER_PORT=9100` | 9300 |
    | `MANAGEMENT_SERVER_PORT=9100` with `--server.port=9200` | 9100 (a management port wins whatever its source) |

  - `TestActuatorPortRefusesUnusableValues`:
    - `SERVER_PORT` of `0`, `-1`, `${PORT}` or `70000` → `errors.Is(err, runtime.ErrNoActuatorPort)`;
    - `MANAGEMENT_SERVER_PORT=-1` with `SERVER_PORT=9000` → the same error, with **no** fallback to 9000.
  - `TestActuatorAddrMapsThePortLikeScrapeAddr`: a `fakeEngine` whose inspect has env `SERVER_PORT=9000` and a `9000/tcp`
    binding to host `18080` → `"127.0.0.1:18080"`.
  - In `discovery_test.go`:
    - `TestWrapForwardsActuatorAddr`: the inner adapter's value is returned;
    - an inner adapter without the method → `errors.Is(err, discovery.ErrUnsupported)`.
- [ ] **Step 2: Run them.** `go test ./internal/runtime/... ./internal/discovery/ -run 'Actuator' -v` → FAIL (undefined).
- [ ] **Step 3: Implement.** `ActuatorAddr` inspects `p.ContainerID`, takes the port from `actuatorPort`, and returns
  `a.ScrapeAddr(ctx, p, port)`. The constants sit beside `argAdditionalLocation`.
- [ ] **Step 4: Run them.** The same command → PASS. Then `make test` → PASS.
- [ ] **Step 5: Commit** (`feat(runtime): resolve the MI actuator address from its launch args and env`).

### Task 3: `logfile`: forward reads

**Files:**
- Create in `go-agent/internal/logs/logfile/`:
  - `logfile.go`: `File`, options, HTTP client, ranged GET, `Content-Range`, status rules;
  - `lines.go`: splitting, `entryTime`;
  - `read.go`;
  - `read_test.go` and `lines_test.go`, using `httptest` servers.

**Interfaces:**
- Produces:
  ```go
  package logfile
  const Path = "/actuator/logfile"
  const Stream = "file"
  const DefaultStep = 64 << 10
  var ErrUnavailable = errors.New("logfile: unavailable")
  type Chunk struct {
      Lines []msg.LogLine
      Ends  []uint64 // Ends[i]: the offset just past Lines[i]'s '\n'
      Next  uint64   // where the next read starts
      Size  uint64   // the file size this read reported
  }
  type Option func(*File)
  func WithClient(c *http.Client) Option          // default: no proxy, no redirects (telemetry/collect's two rules)
  func WithStep(n int) Option                     // Page's read step; default DefaultStep
  func WithNow(f func() time.Time) Option
  func New(addr string, opts ...Option) *File      // addr is host:port; URL http://addr/actuator/logfile
  // Read returns the whole lines from byte offset from, never reading at or past until (0 = no bound), with at most
  // textBudget bytes of text. prev is the time for continuation lines before the read's first entry start.
  func (f *File) Read(ctx context.Context, from, until uint64, textBudget int, prev time.Time) (Chunk, error)
  func entryTime(line []byte) (time.Time, bool)   // the first space-delimited token, parsed as RFC 3339, in UTC
  ```

- [ ] **Step 1: Write the failing tests.**
  - `TestEntryTimeParsesTheDefaultPattern`:
    - `2026-10-20T04:46:06.745Z  INFO 1 --- [main] a.B : x` → `2026-10-20T04:46:06.745Z` UTC;
    - the `+05:30` form of the same instant → equal;
    - `\tat com.x.Y(Y.java:1)` and `2026-10-20 04:46:06.745 INFO` → not an entry start.
  - `TestReadReturnsWholeLinesWithEnds`: three entries, one with `\r\n`.
    - 3 lines, `\r` stripped, `Stream == "file"`;
    - `Ends` are the offsets after each `\n`, `Next == Ends[2]`, `Size == len(file)`.
  - `TestReadNeverReturnsAnUnterminatedTail`: `A\nB\npartial` → lines `A`, `B`; `Next` is the offset after `B\n`.
  - `TestReadGivesContinuationLinesTheirEntryTime`:
    - an entry and two `\tat …` lines → all three share the entry's `TS`;
    - a read starting on continuation lines: with `prev` set → `prev`; with `prev` zero → the next entry's `TS`; with no entry
      at all → the injected `WithNow` time.
  - `TestReadFromMidLineSkipsToTheNextLineStart`:
    - `from` inside line 1 → the first line returned is line 2;
    - `from` exactly at a line start → that line.
  - `TestReadStopsAtUntil`: `until = Ends[1]` → 2 lines, and the server saw no requested byte at or past `until`.
  - `TestReadCutsALineLongerThanTheBudget`: a 3000-byte line of multi-byte runes, budget 1000.
    - One line, ≤ 1000 bytes, valid UTF-8, `Truncated`;
    - its `Ends[0]` and `Next` are the offset after its `\n`.
  - `TestReadPastTheEndReportsTheSize`:
    - a handler answering `416` with `Content-Range: bytes */300` → `Size == 300`, no lines, `Next == from`;
    - an empty file through `http.ServeContent` (`200`, `Content-Length: 0`) → `Size == 0`, no lines, no error.
  - `TestReadRefusesAWholeFileAnswerWithoutReadingIt`: the handler ignores `Range`, sets `Content-Length: 1073741824`, writes
    1 KiB and blocks until the request ends → `ErrUnavailable` within 1 s.
  - `TestReadUnavailableStatuses`: `404`, `401`, `403`, `500`, a closed listener, a context deadline →
    `errors.Is(err, ErrUnavailable)`.
  - `TestReadRangesStayWithinTheBudget`: a recording handler; every `Range` that `Read` sends spans at most `textBudget + 1`
    bytes (the `+1` is the line-start probe at `from-1`).
  - `TestReadUsesNoProxyAndFollowsNoRedirect`:
    - `t.Setenv("HTTP_PROXY", "http://127.0.0.1:1")` → still reaches the server;
    - a `302` → `ErrUnavailable`.
- [ ] **Step 2: Run them.** `go test ./internal/logs/logfile/ -v` → FAIL (package has no code).
- [ ] **Step 3: Implement.**
  - Send `Range: bytes=<start>-<end>`, where `start = from-1` when `from > 0` (to see whether `from` is a line start).
  - Parse `Content-Range: bytes a-b/total`.
  - A line longer than the budget: keep its first `textBudget` bytes (cut on a rune boundary), then keep reading forward in
    budget-sized ranges, discarding bytes, until its `\n` or `until`/`Size`.
- [ ] **Step 4: Run them.** The same command → PASS.
- [ ] **Step 5: Commit** (`feat(logs): read the MI log file forward over ranged HTTP`).

### Task 4: `logfile`: a page, walking back from the end

**Files:**
- Create: `go-agent/internal/logs/logfile/page.go`, `page_test.go`.

**Interfaces:**
- Consumes: Task 3's `File`, `entryTime` and the fetch helpers.
- Produces:
  ```go
  // Page returns the newest lines strictly inside (q.After, q.Before), oldest first, at most q.Limit of them
  // (0 = no line limit), with at most textBudget bytes of text in total.
  func (f *File) Page(ctx context.Context, q msg.LogPageQuery, textBudget int) ([]msg.LogLine, error)
  ```

- [ ] **Step 1: Write the failing tests.** The fixture is 100 entries, one per second.
  - `TestPageNewestLinesOldestFirst`: `Limit: 10` → entries 91–100 in order.
  - `TestPageAfterReturnsOnlyNewerLines`: `After` = entry 95's `TS` → 96–100.
  - `TestPageAfterTheNewestLineIsEmptyAfterOneRead`: `After` = entry 100's `TS` → no lines, and exactly one request.
  - `TestPageBeforeWalksBack`: `WithStep(256)`, `Before` = entry 20's `TS`, `Limit: 5` → 15–19; every request spans at most
    256 bytes.
  - `TestPageJoinsALineSplitAcrossReads`: a step smaller than one line → every line whole.
  - `TestPageDropsTheOrphanedHeadOfAnEntry`: the step is chosen so the walk stops inside a stack trace.
    - No returned line has an unknown time;
    - every returned continuation line has its entry's `TS`.
  - `TestPageAtTheFileStartWithoutAnEntryUsesTheFetchTime`: a file starting with two non-pattern lines, `Limit: 0` → they are
    returned with the `WithNow` time.
  - `TestPageKeepsWithinTheTextBudget`: `Limit: 0`, budget 1000 → total text ≤ 1000, the newest kept.
  - `TestPageOnAnEmptyFile`: both empty-file answers (Decision 1) → no lines, no error.
  - `TestPageLeavesAnUnterminatedTailForTheNextPoll`: a tail `partial` is not returned; after the server appends `\n`, the next
    `Page` returns it.
  - `TestPageEndsWhenTheFileShrinks`: the second read reports a smaller total → the lines found so far, no error.
- [ ] **Step 2: Run them.** `go test ./internal/logs/logfile/ -run Page -v` → FAIL.
- [ ] **Step 3: Implement.**
  - The first read is a suffix range `bytes=-<step>`; each later one is the step before it.
  - Carry the fragment before the first `\n` of each read into the next, holding at most `textBudget` bytes of one line.
  - Hold continuation lines until their entry start is read.
  - Stop at:
    - `limit` kept lines;
    - the text budget;
    - offset 0;
    - an entry with `TS <= After`;
    - a shrink;
    - `ctx`.
  - Memory stays within one page budget plus one step.
- [ ] **Step 4: Run them.** `go test ./internal/logs/logfile/ -v` → PASS.
- [ ] **Step 5: Commit** (`feat(logs): serve a page from the end of the MI log file`).

### Task 5: The agent serves logs from the actuator

`serve` reads from a per-request `Source`, and the logs role opens one through the adapter. Collectors and rings stop being
used. They and the cap are deleted in Task 6, so this commit still builds with them present.

**Files:**
- Modify: `go-agent/internal/logs/serve/serve.go`.
  - Drop the `ring` import and `fitRead`.
  - `page` and `export` read from the `Source`.
- Rewrite: `go-agent/internal/logs/serve/serve_test.go`, with a scripted `fakeSource`.
  - Keep the credit, cancel, revoke, ignored-id and BUSY cases, adapted.
- Rewrite: `go-agent/internal/agent/logs_role.go`.
  - Keep the session, stream, pump and queue.
  - Drop rings, collectors, the store, `logsDPCapDir` and `collectorBackoff`.
  - `Retention` stays as a method that does nothing; Task 6 deletes it with the message.
- Modify: `go-agent/internal/agent/wiring.go`, the `newLogsRole` call. No `logFollower`; `LogsConfig.DefaultMaxBytes` is now
  unused and goes in Task 6.
- Rewrite: `go-agent/internal/agent/logs_integration_test.go`.

**Interfaces:**
- Consumes:
  - Task 2's `runtime.ActuatorLocator` (through the discovery-wrapped adapter);
  - Task 3's `logfile.New` and `logfile.Chunk`;
  - Task 4's `Page`. `*logfile.File` satisfies `Source`.
- Produces:
  ```go
  // serve
  type Source interface {
      Page(ctx context.Context, q msg.LogPageQuery, textBudget int) ([]msg.LogLine, error)
      Read(ctx context.Context, from, until uint64, textBudget int, prev time.Time) (logfile.Chunk, error)
  }
  // Opener finds a placement's log file for one request; a non-empty reason refuses it.
  type Opener func(ctx context.Context, placementRef string) (Source, msg.UnavailableReason)
  const DefaultReadTimeout = 4 * time.Second
  func WithReadTimeout(d time.Duration) Option
  func New(open Opener, inLease func(placementRef string) bool, limits func() (maxMsg, maxBatch uint64), opts ...Option) *Server
  // agent
  func newLogsRole(cfg LogsConfig, adapter runtime.Adapter, newID func() string, log *slog.Logger) *logsRole
  ```
- The role's opener:
  - nil adapter → `NOT_COLLECTING`;
  - a `Resolve` error with `errors.Is(err, runtime.ErrContainerNotFound)` → `CONTAINER_MISSING`;
  - any other `Resolve` error, no `ActuatorLocator`, or an `ActuatorAddr` error → `NOT_COLLECTING`;
  - otherwise `logfile.New(addr)`.

- [ ] **Step 1: Write the failing `serve` tests.**
  - `TestOpenRefusalIsAPage`: the opener returns `CONTAINER_MISSING`, or `NOT_COLLECTING` → one `LogPage` with that reason, for
    a page and for an export.
  - `TestHandleDoesNotBlockOnOpen`: an opener blocked on a channel.
    - `Handle` returns at once;
    - a 5th concurrent request gets `BUSY`.
  - `TestPageErrorIsNotCollecting`.
  - `TestPageTrimsTheOldestToFitMaxMessageBytes`.
  - `TestExportSnapshotEndsAtTheFirstReportedSize`: the first read reports `Size: 300`.
    - Every later `Read` gets `until == 300`;
    - the final chunk's `Seq == 300`.
  - `TestExportSeqIsTheByteOffsetAfterTheLastSentLine`: when `trim` drops the newest lines, `Seq == Ends[kept-1]`. A new export
    from that `Seq` starts with the next line: none lost, none repeated.
  - `TestExportFromBeyondTheSizeRestartsWithDroppedOldest`: `from_seq` 5000 against `Size` 300.
    - The source is re-read from 0;
    - the first chunk's `Gaps[0]` is `DROPPED_OLDEST` with `From == To ==` its first line's `TS`.
  - `TestExportAtTheExactEndIsAnEmptyFinal`: `from_seq == Size` → one chunk, no lines, `Final`.
  - `TestExportEndsCollectionPausedWhenTheFileShrinks`: the second read reports `Size` below `seq` → a final chunk with a
    `COLLECTION_PAUSED` gap.
  - `TestExportFirstReadErrorIsARefusal`: → `LogPage` `NOT_COLLECTING`, no chunk.
  - `TestExportLaterReadErrorEndsCollectionPaused`.
  - `TestExportPassesThePreviousTimestamp`: the second `Read` gets `prev` = the `TS` of the last line sent.
  - `TestExportSkipWithoutLinesSpendsNoCredit`: with one credit, a read with no lines and `Next > seq` is followed by a second
    read, whose lines are sent in that one credit.
  - `TestEachCallIsBoundedByTheReadTimeout`: `WithReadTimeout(50ms)` and a source that blocks until its `ctx` ends → the page
    is `NOT_COLLECTING` within 1 s.
- [ ] **Step 2: Write the failing end-to-end tests** in `logs_integration_test.go`.
  - Set-up: `fakegw`; an `httptest` server serving a growing default-pattern buffer through `http.ServeContent`; a fake adapter
    implementing `Resolve` and `ActuatorAddr`.
  - Every name contains `Log` or `EndToEnd`: `make test-integration` runs only `-run 'Integration|Log|EndToEnd|Retention|ConfigView'`,
    so any other name would never run.
  - Tests:
    - `TestPageEndToEndAgainstFakeGateway`: the newest lines arrive with `Stream == "file"`;
    - `TestLogsLivePollWithAfterGetsOnlyNewLines`: append lines between two pages; the second, with `After` = the first's newest
      `TS`, returns only the appended ones;
    - `TestLogsPageBackWithBefore`;
    - `TestExportEndToEndWithCreditsAndFinal`;
    - `TestLogsExportCancelStopsChunks`;
    - `TestLogsExportResumesFromTheLastSeq`: the resumed lines continue exactly after the last received chunk;
    - `TestLogsExportRotationMidwayEndsIncomplete`: the server swaps in a shorter buffer between credits → a final chunk with a
      `COLLECTION_PAUSED` gap;
    - `TestLogsContainerGoneIsContainerMissing`;
    - `TestLogfileNotExposedIsNotCollecting` (`404`);
    - keep `TestAgentWithoutLogsRoleNeverReceivesLogMessages`;
    - delete `TestRetentionAppliedLiveTrimsAndGaps`.
- [ ] **Step 3: Run them.**
  - `go test ./internal/logs/serve/ -v` → FAIL (`New`'s signature);
  - `make test-integration` → FAIL.
- [ ] **Step 4: Implement.**
  - Admission order: malformed or duplicate id ignored → not in the lease → `BUSY` → on the goroutine, `open` under the read
    timeout → refusal or serve.
  - A page is one `Source.Page(ctx, q, limit-fixed)` and `trim(…, fromFront=true)`.
  - The export loop follows Decisions 5 and 6.
  - The role's opener is as specified under Interfaces.
- [ ] **Step 5: Run them.** `make test` → PASS; `make test-integration` → PASS, and its `-v` output has a `--- PASS` line for
  each of Step 2's ten names.
- [ ] **Step 6: Commit** (`feat(logs): serve MI logs on demand from /actuator/logfile`).

### Task 6: The ring, the collector and the cap go from the agent

Deletion only, plus the guard that proves nothing can write.

**Files:**
- Delete:
  - `go-agent/internal/logs/ring/`, `go-agent/internal/logs/collect/`;
  - `go-agent/internal/runtime/docker/logs.go` and `logs_test.go`.
- Modify:
  - `internal/agent/logs_role.go`: drop `Retention`.
  - `internal/agent/loop.go`: drop `Retention` from the role interface and the `msg.LogRetention` case.
  - `internal/agent/loop_test.go`: `fakeLogsRole.Retention`.
  - `internal/agent/wiring.go`: `LogsConfig` keeps only `MaxConcurrent`.
  - `internal/agent/roles.go`: comment only.
  - `internal/msg/msg.go`: drop `LogRetention`; the `LogGap` comment says which reasons are still emitted.
  - `internal/wire/codec/codec.go`, `codec/logs.go`, `codec/logs_test.go`: drop the `LogRetention` mapping both ways.
  - `cmd/sm-agent/main.go`: drop `--logs-default-max-bytes` and `SM_AGENT_LOGS_DEFAULT_MAX_BYTES`; drop `parseBytes` if nothing
    else uses it.
  - `internal/runtime/docker/docker.go`: drop `ContainerLogs` from `engineAPI` and from its test fakes.
- Modify, the fake gateway:
  - `internal/fakegw/console.go`: drop the `cap` command and its usage text;
  - `internal/fakegw/logs.go`: drop `SetLogRetention` and `sendLogRetention`;
  - `internal/fakegw/scenario.go`: drop `LogRetentionStep`;
  - `internal/fakegw/session.go`;
  - `internal/fakegw/scenarios/logs.yaml`: drop the `log_retention` step;
  - `internal/fakegw/console_logs_test.go`, `internal/fakegw/scenarios_test.go`.
- Create: `go-agent/internal/logs/nostore_test.go`. A test-only package is accepted by `go vet ./...`, `go build ./...` and
  `go test` (run while writing this plan).

**Interfaces:**
- Produces: no Go source outside `internal/wire/pb/` refers to `LogRetention`, so Task 8 can remove it from the wire.

- [ ] **Step 1: Write the guard test.** `TestLogsCodeCannotWriteToDisk`:
  - parse the imports of every non-test `.go` file under `internal/logs/` and of `internal/agent/logs_role.go`;
  - fail on `os`, `io/ioutil` or `…/internal/store`;
  - its planted-violation case feeds the checker a source string importing `os` and asserts it is reported (`L7`).
- [ ] **Step 2: Run it.** `go test ./internal/logs/ -v` → PASS already, since Task 5 removed the store. Then confirm the planted
  case fails when the check is disabled, and restore it.
- [ ] **Step 3: Delete and trim** per the Files list.
- [ ] **Step 4: Run everything.**
  - `make test` → PASS;
  - `make test-integration` → PASS;
  - `go build ./...` → OK;
  - `grep -rnE 'LogRetention|logs/ring|logs/collect|FollowLogs|DefaultMaxBytes|logs-default-max-bytes|SetLogRetention' --include=*.go . | grep -vE 'internal/wire/pb/|oldGo-Agent/'`
    → no output.
- [ ] **Step 5: Commit** (`refactor(logs): delete the log ring, collector and cap from the agent`).

### Task 7: Demo, runbooks and docs

**Files:**
- `go-agent/RUNBOOK.md` "Logs";
- `go-agent/CLAUDE.md`;
- `docs/runbooks/backend/read-and-export-agent-logs.md`;
- `docs/design/backend/agent-gateway/specs/2026-10-20-agent-mi-logs-design.md` (banner only);
- `docs/plans/backend/016-agent-mi-logs.md` (a one-line pointer at the top).

- [ ] **Step 1: Rewrite the "Logs" demo and run it as written.**
  - The demo container must serve a growing default-pattern file at `/actuator/logfile` with `Range` support, on the port its
    `SERVER_PORT` env names, with the `com.solace.sm.binding-id` label.
  - Pick the image, and record the exact commands **as run**, with the date and ports, in the section's status line.
  - Remove the `cap` rows and the ring-inspection step.
  - Keep the "if it goes wrong" table's rows that still apply. Add one for `404` (`logfile` not exposed → `NOT_COLLECTING`).
- [ ] **Step 2: Update `go-agent/CLAUDE.md`.**
  - The logs paragraph: on demand from `/actuator/logfile`, nothing stored.
  - The architecture row: `internal/logs/` = `logfile/`, `serve/`.
  - The `--roles` comment.
- [ ] **Step 3: Add an "MI preconditions" section to the DP runbook:**
  - `logging.file.name` or `logging.file.path` set;
  - `logfile` in `management.endpoints.web.exposure.include`;
  - the actuator at `/actuator` (no moved base path, path mapping or context path);
  - the port in launch args or env;
  - no actuator authentication;
  - downloads cover the current file only (rotation at 10 MB by default).
- [ ] **Step 4: Banners.**
  - At the top of the 2026-10-20 design: "Superseded in part by `2026-10-28-agent-mi-logs-from-actuator-design.md`".
  - The same pointer, naming plan 018, at the top of plan 016.
  - Neither body is rewritten.
- [ ] **Step 5: Check links.** Every relative link in the changed files resolves (`[ -e ]` from each file's directory).
- [ ] **Step 6: Commit** (`docs(logs): actuator logfile demo, MI preconditions, supersession pointers`).

### Task 8: Wire: `LogRetention` removed, field 15 reserved

**Files:**
- Modify:
  - `contracts/agent/v1/agent_gateway.proto`;
  - `contracts/agent/v1/baseline.json`;
  - the generated `go-agent/internal/wire/pb/agent_gateway.pb.go`.

**Interfaces:**
- Consumes: Tasks 1 and 6. No Java or Go source refers to `LogRetention`.

- [ ] **Step 1: Edit the proto.**
  - Delete `message LogRetention` and the oneof member `LogRetention log_retention = 15;`.
  - In `GatewayToAgent`, add `reserved 15;` and `reserved "log_retention";` at message level.
  - Comments: `CAPABILITY_LOGS` ("understands `LogRequest` and opens the `Logs` RPC; serves the MI's `/actuator/logfile`");
    `LogGap` (`CONTAINER_UNREADABLE` and `CAP_REDUCED` are no longer emitted); `LogExportQuery`/`LogChunk` (`seq` is a byte
    offset of the current file).
- [ ] **Step 2: Run the checker.** `python3 contracts/agent/v1/check_proto_append_only.py` → FAIL, naming the removed baseline
  entries. That proves the gate sees the change.
- [ ] **Step 3: Edit `baseline.json` by hand.**
  - Remove `GatewayToAgent`'s `"15"` entry and the `"LogRetention"` message block. `--write-baseline` never drops entries.
- [ ] **Step 4: Run the checker's modes.**
  - Plain `check_proto_append_only.py` → `ok`.
  - `--self-test` → `ok`.
  - `--superset` → `ok`. Its reference is the merge base with `origin/UAT`, `b0950cd`, whose baseline has no `LogRetention`;
    checked while writing this plan.
- [ ] **Step 5: Regenerate the Go code with the pinned generators.**
  - The host's `~/go/bin/protoc-gen-go-grpc` is `1.6.2`; the tree was generated with `v1.5.1`.
  - Install `v1.5.1` into a scratch `GOBIN` with `go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.5.1`. This needs
    the network and was not run while writing this plan.
  - Run `make proto` with that `GOBIN` first on `PATH`, then `~/go/bin` (buf 1.73.0, `protoc-gen-go` v1.36.12).
  - Expected: `git diff --quiet internal/wire/pb/agent_gateway_grpc.pb.go`, because no rpc changed. In
    `agent_gateway.pb.go`, only `LogRetention` and the descriptor change.
- [ ] **Step 6: Run everything.**
  - In `go-agent/`: `make test` and `make test-integration` → PASS.
  - The Java `agent-gateway` module builds its stubs from the proto: run its tests with Task 1's command → green.
  - `./contracts/run-checks.sh` → steps 12 and 18 `ok`. Report any other red against its pre-existing state; plan 017 lists the
    Python preflight.
- [ ] **Step 7: Commit** (`feat(contracts): remove LogRetention from the agent wire; reserve field 15`).

---

## Results

*(Filled in at execution: commits per task, the Java command actually run, the demo run, deviations from this plan. State
whether a real gateway, DP and agent ran this together; if not, say so (`L26`).)*
