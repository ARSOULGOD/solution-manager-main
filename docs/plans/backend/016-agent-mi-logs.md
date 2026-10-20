# 016 — Agent-collected MI application logs, served on demand — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

| | |
|---|---|
| **Component** | `go-agent` + `contracts/agent` + `backend/agent-gateway` + `backend/dp-core` + `backend/console-gateway` (cross-component; filed under `backend/` beside plan 015, whose gateway it extends) |
| **Design** | [`2026-10-20-agent-mi-logs-design.md`](../../design/backend/agent-gateway/specs/2026-10-20-agent-mi-logs-design.md) |
| **Decisions** | `AGW-D2` (module boundary), `AGW-D11` (new proto numbers only), `AG-D8` (slog), `I1`, `I5`, `I6`, `I7`. **No decision ids are allocated by this plan** — `DECISIONS.md` is the one allocator. |
| **Status** | DRAFT — not reviewed |

**Goal:** A `host-local` agent collects its placements' MI application logs into a DP-capped on-disk ring and serves them to the DP only on request: a paged read through the existing `LogService`, and a bulk export.

**Architecture:** The agent follows each container's Docker log stream into a per-placement ring (drop-oldest, gap markers). Requests ride `Connect`; all log data rides a new `Logs` RPC with credits so heartbeats and lease renewal never queue behind log data. The DP implements `LogService` for agent placements over a typed port in `dp-api`; the gateway holds no datasource.

**Tech Stack:** Go (`go-agent`, Docker Engine client v27, buf/protobuf/gRPC), Java 17 / Spring Boot / Maven reactor (`agent-gateway`, `dp-api`, `dp-core`, `console-gateway`), PostgreSQL migrations (`sm_data`), OpenAPI (`contracts/openapi/console.yaml`).

**Spec:** the design linked above — read it first; this plan argues from it.

## Prerequisites and open points

- **Unratified baseline.** The agent-gateway decisions are branch-local/PROPOSED (see the integration design's status line). This plan builds on them as the implementation baseline, not as ratified fact (`I4`).
- **Contract change.** Task 1 changes `contracts/agent/v1/agent_gateway.proto`: a deliberate, reviewed, single-writer change. Run `./contracts/run-checks.sh` before and after.
- **Authorization for the cap (spec §6) — BLOCKED, owner decision.** The catalog is closed at 38 verbs (`Permission.java`); paged read and export use the existing `logs.read`. No existing verb obviously authorizes *setting* a placement's cap (`logs.setLevel` is about log levels). Task 9 builds only the internal port and stays inert until the owner names the verb. **Do not add a verb.**
- **Verify external facts before writing them down (`I2`).** Task 4 depends on Docker Engine client behaviour (`ContainerLogs` multiplexing, `Since` granularity) — confirm in the vendored v27 source, not from memory.

## Global Constraints

- Agent module `github.com/solace/solution-manager/go-agent`; only `internal/wire` and `internal/wire/codec` import `internal/wire/pb`.
- Agent files under the data dir: directories `0700`, files `0600`, written through `store.WriteAtomic`.
- Agent logging is `log/slog` (`AG-D8`); stdlib `flag` + env for configuration (`AG-D7`). Log text from the MI is **never** written to the agent's own `slog` output.
- Proto changes use **new field numbers only** (`AGW-D11`); never renumber.
- A log reply must fit `Limits.max_message_bytes`; a chunk must fit `Limits.max_batch_bytes`.
- Degraded replies use **fixed text that names no host, path, container or exception message** (`I6`).
- Tenant scope is never client-supplied; the placement is resolved from `(organization, instance, placement)` together (`I5`). Log lines are **not redacted** (consistent with `LogService`).
- `agent-gateway` holds no datasource and imports no DP repositories or entities; it talks to the DP through typed ports in `dp-api` (`AGW-D2`).
- Every commit message ends with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Gates (no CI exists — run them): `cd go-agent && go vet ./... && go test ./...`; `./contracts/run-checks.sh`; `cd backend && ./mvnw verify` (includes `arch-tests`).

## Review Focus

Inputs the spec implies that no happy-path test would exercise, most likely first. Each has a pinning test in the owning task.

1. **A line split by a segment boundary, or a multibyte character split across reads** — the reader must never return half a line or invalid UTF-8 (Task 3, Task 4).
2. **One log line larger than a page, chunk or segment** — truncated with a marker; it must neither wedge an export nor be silently dropped (Task 3, Task 5).
3. **Duplicate lines after a resume** — the collector restarts from the last stored timestamp; the boundary line must not appear twice (Task 4).
4. **The cap lowered (or set to 0) while an export is in flight** — the export ends with a gap marker, with no panic and no stale read (Task 5).
5. **A `LogRequest` for a placement that is not in this session's lease** — refused, never served (Task 5, Task 7).

---

## File Structure

| Path | Responsibility |
|---|---|
| `contracts/agent/v1/agent_gateway.proto` | modify — log messages, `Logs` RPC, `CAPABILITY_LOGS` |
| `go-agent/internal/wire/pb/*.pb.go` | regenerated (`make proto`) |
| `go-agent/internal/msg/msg.go` | modify — log message types, `CapLogs` |
| `go-agent/internal/wire/codec/logs.go` | create — pb ↔ msg for log messages (own file; `additions.go` is already large) |
| `go-agent/internal/wire/client.go` | modify — `Conn.OpenLogs` and `LogsStream` beside `OpenTelemetry` |
| `go-agent/internal/logs/ring/` | create — the on-disk ring store |
| `go-agent/internal/logs/collect/` | create — per-placement Docker log follower |
| `go-agent/internal/logs/serve/` | create — page and export serving, credits, cancel |
| `go-agent/internal/runtime/docker/logs.go` | create — `ContainerLogs` access on the adapter |
| `go-agent/internal/agent/{roles,wiring,loop}.go` | modify — `logs` role, capability, retention handling, wiring |
| `go-agent/internal/fakegw/` | modify — script log requests and retention |
| `backend/dp-api/.../api/agw/AgentLogPort.java` | create — typed port the gateway calls |
| `backend/agent-gateway/.../agw/AgentGatewayService.java` | modify — send `LogRetention`/`LogRequest`, host the `Logs` RPC |
| `backend/dp-core/.../dp/logs/AgentLogService.java` | create — `LogService` for agent placements |
| `backend/dp-core/.../dp/logs/RoutingLogService.java` | create — selects SSH vs agent per placement |
| `backend/dp-core/src/main/resources/db/migration/sm_data/V053__placement_log_max_bytes.sql` | create — per-placement cap |
| `backend/console-gateway/.../http/{LogsController,AgentPlacements}.java` | modify — stop refusing `readLogs`; add export |
| `contracts/openapi/console.yaml` | modify — `readLogs` agent text; export operation |
| `go-agent/RUNBOOK.md` | modify — logs role, cap, how to read a page and export |

---

### Task 1: Wire contract

**Files:** Modify `contracts/agent/v1/agent_gateway.proto`; regenerate `go-agent/internal/wire/pb/`; Java stubs by the Maven build.

**Interfaces — Produces** (exact message and field names the later tasks use; field numbers are the **next free ones** — read the file, `GatewayToAgent` currently ends at 14, `AgentToGateway` at 11):
- `enum Capability` gains `CAPABILITY_LOGS` (next unused value).
- `GatewayToAgent.oneof msg` gains `LogRetention log_retention = 15; LogRequest log_request = 16;`
- `LogRetention { Envelope envelope; string placement_ref; uint64 max_bytes; }`
- `LogRequest { Envelope envelope; string request_id; string placement_ref; oneof mode { LogPageQuery page = ..; LogExportQuery export = ..; } }`, `LogPageQuery { uint32 limit; optional Timestamp after; optional Timestamp before; }`, `LogExportQuery { uint64 from_seq; }`
- `rpc Logs(stream LogUp) returns (stream LogDown);` — `LogUp { oneof msg { LogPage log_page; LogChunk log_chunk; } }`, `LogDown { oneof msg { LogCredit log_credit; LogCancel log_cancel; } }`
- `LogLine { Timestamp ts; string stream; bytes text; bool truncated; }`, `LogGap { Timestamp from; Timestamp to; string reason; }` (reasons: `DROPPED_OLDEST`, `COLLECTION_PAUSED`, `OVERSIZE`, `CONTAINER_UNREADABLE`, `CAP_REDUCED`)
- `LogPage { Envelope envelope; string request_id; repeated LogLine lines; repeated LogGap gaps; UnavailableReason unavailable; }` with `enum UnavailableReason { UNAVAILABLE_NONE; NOT_COLLECTING; CONTAINER_MISSING; BUSY; PLACEMENT_NOT_IN_LEASE }`
- `LogChunk { Envelope envelope; string request_id; uint64 seq; repeated LogLine lines; repeated LogGap gaps; bool final; }`
- `LogCredit { string request_id; uint32 credits_granted; }`, `LogCancel { string request_id; }`

- [ ] **Step 1: Run `./contracts/run-checks.sh` on the untouched tree** and record that it passes (baseline).
- [ ] **Step 2: Add the messages, enum value and RPC above**, with a transcription comment citing the design §4 and `AGW-D11`.
- [ ] **Step 3: `cd go-agent && make proto`**, then `go build ./...`. Expected: builds; `pb_test.go` still passes.
- [ ] **Step 4: `./contracts/run-checks.sh`** — expected PASS. If a check lists the proto's messages or enforces number gaps, extend its fixtures rather than weakening it.
- [ ] **Step 5: `cd backend && ./mvnw -pl agent-gateway -am compile`** — expected: Java stubs generate.
- [ ] **Step 6: Commit** `feat(contracts): agent log request/page/export wire (CAPABILITY_LOGS, Logs RPC)`.

---

### Task 2: Agent message types and codec

**Files:** Modify `go-agent/internal/msg/msg.go`; create `go-agent/internal/wire/codec/logs.go`, `logs_test.go`.

**Interfaces — Produces:** in package `msg`: `CapLogs Capability = "logs"`; structs `LogRetention{PlacementRef string; MaxBytes uint64}` (implements `Inbound`), `LogRequest{RequestID, PlacementRef string; Page *LogPageQuery; Export *LogExportQuery}` (`Inbound`), `LogLine`, `LogGap`, `LogPage`, `LogChunk` (these two implement a new `LogsOutbound`), `LogCredit`, `LogCancel` (a new `LogsInbound`). Codec: `func EncodeLogUp(LogsOutbound) (*pb.LogUp, error)`, `func DecodeLogDown(*pb.LogDown) (LogsInbound, error)`, and the `GatewayToAgent` decoder extended for `LogRetention` and `LogRequest`.

- [ ] **Step 1: Write failing codec tests** in `logs_test.go`: `TestLogRequestPageRoundTrip`, `TestLogRequestExportRoundTrip`, `TestLogRetentionDecode`, `TestLogPageEncodeKeepsGapsAndUnavailable`, `TestLogChunkFinalFlag`, `TestUnknownLogsDownMessageIsAnError`. Each asserts field-for-field equality after a round trip.
- [ ] **Step 2: Run** `go test ./internal/wire/codec/ -run Log` — expected FAIL (undefined).
- [ ] **Step 3: Implement** the types and codec functions; `CapLogs` maps to `CAPABILITY_LOGS`.
- [ ] **Step 4: Run** the same command — expected PASS; then `go vet ./... && go test ./...`.
- [ ] **Step 5: Commit** `feat(go-agent): log message types and codec`.

---

### Task 3: Ring store

**Files:** Create `go-agent/internal/logs/ring/ring.go`, `ring_test.go`.

**Interfaces — Produces:**
```go
type Ring struct{ /* per-placement; segment files under <data-dir>/logs/<placement>/ */ }
func Open(s *store.Store, placement string, maxBytes uint64) (*Ring, error)
func (r *Ring) Append(l msg.LogLine) error
func (r *Ring) MarkGap(g msg.LogGap) error
func (r *Ring) SetCap(maxBytes uint64) error            // trims oldest now; 0 deletes all
func (r *Ring) Page(after, before *time.Time, limit int, maxBytes int) (lines []msg.LogLine, gaps []msg.LogGap, err error)
func (r *Ring) ExportFrom(seq uint64, maxBytes int) (lines []msg.LogLine, gaps []msg.LogGap, next uint64, done bool, err error)
func (r *Ring) LastTimestamp() (time.Time, bool)
```
`placement` must match `^[A-Za-z0-9._-]+$` (as `telemetry/buffer`'s `validID`). Segments are time-indexed; `Page` bounds are **exclusive**; `Page` returns the newest `limit` lines in the window, oldest first (the `LogService` contract).

- [ ] **Step 1: Write failing tests:** `TestAppendThenPageReturnsNewestLimitOldestFirst`; `TestPageBoundsAreExclusive`; `TestCapDropsOldestSegmentAndRecordsGap`; `TestSetCapSmallerTrimsImmediately`; `TestSetCapZeroDeletesEverything`; `TestSurvivesReopen`; `TestRejectsUnsafePlacementID`; `TestLineSplitAcrossSegmentBoundaryIsNeverReturnedHalf` and `TestMultibyteTextRoundTripsByteExact` (Review Focus 1); `TestOversizeLineIsTruncatedWithMarkerNotDropped` (Review Focus 2); `TestExportFromResumesBySeqWithoutRepeats`.
- [ ] **Step 2: Run** `go test ./internal/logs/ring/ -v` — expected FAIL.
- [ ] **Step 3: Implement** in the style of `internal/telemetry/buffer` (one file per segment, drop-oldest, gap records); persist the cap beside the segments; writes through `store.WriteAtomic` where a record is replaced, append-fsync for segment tails.
- [ ] **Step 4: Run** the tests — expected PASS, plus `go vet ./...`.
- [ ] **Step 5: Commit** `feat(go-agent): size-capped log ring store`.

---

### Task 4: Docker log collector

**Files:** Create `go-agent/internal/runtime/docker/logs.go`, `logs_test.go`, `go-agent/internal/logs/collect/collect.go`, `collect_test.go`; modify the `engineAPI` interface in `go-agent/internal/runtime/docker/docker.go`.

**Interfaces — Consumes:** `ring.Ring` (Task 3); `Adapter.ResolveByName(ctx, containerName, bindingID string) (runtime.Placement, error)`. **Produces:**
```go
// docker package
func (a *Adapter) FollowLogs(ctx context.Context, p runtime.Placement, since time.Time) (<-chan msg.LogLine, <-chan error)
// collect package
type Source interface {
    FollowLogs(ctx context.Context, p runtime.Placement, since time.Time) (<-chan msg.LogLine, <-chan error)
    ResolveByName(ctx context.Context, containerName, bindingID string) (runtime.Placement, error)
}
func New(src Source, r *ring.Ring, log *slog.Logger, backoff func(attempt int) time.Duration) *Collector
func (c *Collector) Run(ctx context.Context, containerName, bindingID string) error
```
`engineAPI` gains `ContainerLogs(ctx context.Context, id string, opts container.LogsOptions) (io.ReadCloser, error)`.

- [ ] **Step 0 (I2): Read the vendored `github.com/docker/docker` v27 client source** for `ContainerLogs`, `container.LogsOptions` (`Since`, `Timestamps`, `Follow`) and `pkg/stdcopy`; record in the commit message what you confirmed: that the stream is multiplexed for a non-TTY container, and what granularity `Since` accepts. Do not rely on memory.
- [ ] **Step 1: Write failing tests** with a fake `Source`/`engineAPI`: `TestFollowsAndAppendsLinesWithDockerTimestamps` (uses Docker's timestamps, not the agent clock); `TestResumesFromLastStoredTimestampAfterRestart`; `TestBoundaryLineIsNotDuplicatedOnResume` (Review Focus 3); `TestContainerRecreateReResolvesByNameAndRecordsGap`; `TestDockerErrorBacksOffAndRecordsContainerUnreadableGap`; `TestCapZeroStopsCollection`; `TestMIOutputNeverReachesAgentSlog` (capture the logger; assert no line text appears).
- [ ] **Step 2: Run** `go test ./internal/logs/collect/ ./internal/runtime/docker/ -run Log` — expected FAIL.
- [ ] **Step 3: Implement** `FollowLogs` (demultiplex with the verified mechanism; split into lines; keep partial trailing bytes until a newline or stream end) and `Collector.Run` (resume from `LastTimestamp`, dedupe the boundary, gap on error/recreate, back off).
- [ ] **Step 4: Run** the tests — expected PASS; `go vet ./... && go test ./...`.
- [ ] **Step 5: Commit** `feat(go-agent): follow container logs into the ring`.

---

### Task 5: Serving, the Logs stream, and the agent role

**Files:** Create `go-agent/internal/logs/serve/serve.go`, `serve_test.go`; modify `go-agent/internal/wire/client.go`, `go-agent/internal/agent/roles.go`, `wiring.go`, `loop.go`, `go-agent/cmd/sm-agent/main.go`.

**Interfaces — Consumes:** Tasks 2–4. **Produces:**
```go
// wire
func (c *Conn) OpenLogs(ctx context.Context) (*LogsStream, error)
func (s *LogsStream) Send(m msg.LogsOutbound) error
func (s *LogsStream) Recv() (msg.LogsInbound, error)
func (s *LogsStream) Close() error
// serve
type Server struct{ /* holds rings by placement ref, the session's lease view, a credit gate per request */ }
func New(rings func(placementRef string) (*ring.Ring, bool), inLease func(placementRef string) bool, limits func() (maxMsg, maxBatch uint64)) *Server
func (s *Server) Handle(ctx context.Context, req msg.LogRequest, out LogsSender) // PAGE → one LogPage; EXPORT → LogChunks gated by credits until final
func (s *Server) Cancel(requestID string)
// roles
type Roles struct{ Control, Telemetry, Logs bool }   // ParseRoles accepts "logs"
```
`capabilities()` adds `msg.CapLogs` when `Logs` is set. A `LogRetention` message calls `Ring.SetCap` and persists it; the YAML/env default is `SM_AGENT_LOGS_DEFAULT_MAX_BYTES` (flag `--logs-default-max-bytes`, parsed with the existing `bytes.go` helper), applied until the DP sets a value.

- [ ] **Step 1: Write failing tests:** `TestPageRequestAnswersOneLogPageWithinMaxMessageBytes`; `TestExportEmitsChunksOnlyWithCredits`; `TestExportResumesFromFromSeq`; `TestCancelStopsExportAndFreesState`; `TestRequestForPlacementNotInLeaseIsRefusedPLACEMENT_NOT_IN_LEASE` (Review Focus 5); `TestNotCollectingPlacementAnswersNOT_COLLECTING`; `TestCapReducedMidExportEndsWithCAP_REDUCEDGapAndNoPanic` (Review Focus 4); `TestOversizeLineIsTruncatedInPageAndChunk` (Review Focus 2); `TestDuplicateAndUnknownRequestIdsAreIgnoredAndCounted`; `TestBusyWhenConcurrentRequestsExceedBound`. In `roles_test.go`: `TestParseRolesAcceptsLogs`, `TestLogsRoleAdvertisesCapLogs`.
- [ ] **Step 2: Run** `go test ./internal/logs/serve/ ./internal/agent/ -run 'Log|Roles' -v` — expected FAIL.
- [ ] **Step 3: Implement** `Server`, `wire.LogsStream` (mirror `TelemetryStream`), the role and capability, the `loop.go` cases for `msg.LogRetention` and `msg.LogRequest` (the loop hands the request to `Server` and never blocks on it), and wiring: open the `Logs` stream only after `SessionAccept` and only when the capability was accepted.
- [ ] **Step 4: Run** the tests and `go vet ./... && go test ./...` — expected PASS.
- [ ] **Step 5: Commit** `feat(go-agent): logs role serves page and export over the Logs stream`.

---

### Task 6: Fake gateway scripting and agent end-to-end

**Files:** Modify `go-agent/internal/fakegw/{scenario,session,server}.go`; create `go-agent/internal/agent/logs_integration_test.go` (`//go:build integration`).

**Interfaces — Produces:** scenario steps `log_retention: {placement, max_bytes}`, `log_request: {placement, page|export}`, and `expect_log_page`/`expect_log_chunks` transcript assertions, in the existing `scenario.go` style.

- [ ] **Step 1: Write failing integration tests** in `logs_integration_test.go`: `TestPageEndToEndAgainstFakeGateway`; `TestExportEndToEndWithCreditsAndFinal`; `TestRetentionAppliedLiveTrimsAndGaps`; `TestAgentWithoutLogsRoleNeverReceivesLogMessages`.
- [ ] **Step 2: Run** `go test -tags integration ./internal/agent/ -run Log -v` — expected FAIL.
- [ ] **Step 3: Implement** the fake-gateway support; add a `fakegw/scenarios/logs.yaml` demo scenario.
- [ ] **Step 4: Run** the integration tests and `make test` — expected PASS.
- [ ] **Step 5: Commit** `test(go-agent): scripted log page/export/retention scenarios`.

---

### Task 7: Gateway — typed port, Logs RPC, request routing

**Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/agw/AgentLogPort.java`; modify `backend/agent-gateway/src/main/java/com/solace/sm/agw/AgentGatewayService.java`, `AgentGatewayPortSet.java`, `AgentGatewayConfiguration.java`; tests beside `AgentGatewayServiceTest`.

**Interfaces — Produces** (in `dp-api`, no gateway types leak):
```java
public interface AgentLogPort {
    /** The agent's session for this placement, if connected with CAPABILITY_LOGS and the placement is in its lease. */
    Optional<AgentSessionRef> sessionFor(UUID organizationId, UUID placementId);
    CompletableFuture<AgentLogPage> requestPage(AgentSessionRef ref, UUID placementId, int limit, Instant after, Instant before, Duration deadline);
    LogExportHandle openExport(AgentSessionRef ref, UUID placementId, long fromSeq, Duration deadline, long maxBytes);
    void sendRetention(AgentSessionRef ref, UUID placementId, long maxBytes);
}
```
`AgentLogPage(List<LogLineView> lines, List<AgentLogGap> gaps, Optional<String> unavailableReason)`; `LogExportHandle` is a pull-based `Iterator`-style chunk source with `cancel()`. The gateway implements the port; the **DP never sees gRPC types** (`AGW-D2`). `AgentGatewayPortSet` gains the new member and `Objects.requireNonNull` it.

- [ ] **Step 1: Write failing tests:** `LogRequestSentOnlyToSessionWithCapLogs`; `PageRequestCorrelatesReplyByRequestId`; `UnknownOrLateReplyIsIgnoredAndCounted`; `NoReplyByDeadlineCompletesWithTimeoutAndSendsLogCancel`; `ExportStreamsChunksWithCreditsAndEndsOnFinal`; `RetentionPushedOnSessionOpenAndOnChange`; `PlacementNotInLeaseYieldsNoSession` (Review Focus 5); `LogsStreamDoesNotBlockHeartbeatOrLeaseRenew`; `LogsRpcRefusedBeforeSessionAccept`. An ArchUnit case in `backend/arch-tests` with a planted-violation proof: `agwHoldsNoDatasource` still holds after the change.
- [ ] **Step 2: Run** `cd backend && ./mvnw -pl agent-gateway -am test` — expected FAIL.
- [ ] **Step 3: Implement** the port, the gateway's `Logs` stream handler (credit grants mirror the telemetry credit pattern), the correlation map with deadline, and the retention push alongside `sendDeclarations`.
- [ ] **Step 4: Run** `./mvnw -pl agent-gateway,arch-tests -am verify` — expected PASS, including the planted-violation proofs.
- [ ] **Step 5: Commit** `feat(agent-gateway): AgentLogPort and the Logs RPC`.

---

### Task 8: DP — `AgentLogService`, routing, cap column, API change

**Files:** Create `backend/dp-core/src/main/java/com/solace/sm/dp/logs/AgentLogService.java`, `RoutingLogService.java`, `backend/dp-core/src/main/resources/db/migration/sm_data/V053__placement_log_max_bytes.sql`; modify `LogConfiguration.java`, `backend/console-gateway/.../http/LogsController.java` (line ~92) and `AgentPlacements.java`, `contracts/openapi/console.yaml` (`readLogs`, ~line 3260).

**Interfaces — Consumes:** `AgentLogPort` (Task 7). **Produces:** `AgentLogService implements LogService` (same `read(UUID organizationId, UUID instanceId, UUID placementId, int limit, Instant after, Instant before)`); `RoutingLogService implements LogService` delegating to `LogServiceImpl` for SSH placements and `AgentLogService` for agent placements (decided from the placement's target transport on the DP's own view — the same discriminator `AgentPlacements.refuseIfAgent` reads); migration adds nullable `log_max_bytes bigint` to the placements table (find it via `PlacementRows`). Property: `sm.dp.logs.agent-request-deadline` (default `5s`).

- [ ] **Step 1: Write failing tests:** `AgentLogServiceTest` — `ReadsPageThroughPort`; `NoSessionOrMissingCapabilityReturnsFixedDegradedPage`; `TimeoutReturnsFixedDegradedPageAndNamesNoHostOrContainer` (I6); `OtherOrganizationsPlacementIsUnresolvable` (I5); `ConcurrentReadsBeyondBoundAreBusyPages`; and the **existing `LogService` contract assertions run against `AgentLogService`** (reuse the `LogServiceImplTest` cases as a shared abstract contract test). `RoutingLogServiceTest` — `SshPlacementUsesSshImplementation`; `AgentPlacementUsesAgentImplementation`. In `console-gateway`: update `AgentPlacementsTest`/`AgentPlacementRefusalsEndpointIT` so `readLogs` on an agent placement is **200** while the log-level operations still answer `501 AGENT_OPERATION_UNSUPPORTED`. A schema test for V053 in the style of the existing `*SchemaIT`.
- [ ] **Step 2: Run** `./mvnw -pl dp-core,console-gateway -am test` — expected FAIL.
- [ ] **Step 3: Implement** the service, router, migration, controller change, and the OpenAPI text (replace the *Agent placements* paragraph; keep the unreachable-host rule). Wire `RoutingLogService` as the `LogService` bean in `LogConfiguration`.
- [ ] **Step 4: Run** `./mvnw verify` for the touched modules and `./contracts/run-checks.sh` — expected PASS; confirm `SchemaMirrorLockTest` is green or updated deliberately.
- [ ] **Step 5: Commit** `feat(dp): serve agent placement logs through LogService`.

---

### Task 9: Cap plumbing (internal port only) — verb BLOCKED

**Files:** Modify `AgentLogService` / a small `PlacementLogRetention` service in `backend/dp-core/.../dp/logs/`; test beside it.

**Interfaces — Produces:** `void setCap(UUID organizationId, UUID placementId, long maxBytes)` — validates (`0` allowed = off; upper bound from `sm.dp.logs.agent-max-cap-bytes`), persists `log_max_bytes`, calls `AgentLogPort.sendRetention`. **No REST write is added.** The gateway re-sends the stored value on session open (Task 7).

- [ ] **Step 1: Write failing tests:** `SetCapPersistsAndPushes`; `ZeroTurnsCollectionOff`; `OverMaximumIsRejected`; `ReconnectResendsStoredCap`; `OtherOrganizationsPlacementIsUnresolvable`.
- [ ] **Step 2: Run** `./mvnw -pl dp-core -am test` — expected FAIL.
- [ ] **Step 3: Implement** the service. **Stop and raise with the project owner** which existing verb authorizes the REST write; until named, leave no endpoint. Record the answer in the design spec §6 and `DECISIONS.md` (as a new decision, not an edit).
- [ ] **Step 4: Run** — expected PASS.
- [ ] **Step 5: Commit** `feat(dp): per-placement log cap storage and push (no REST write yet)`.

---

### Task 10: Export endpoint

**Files:** Modify `contracts/openapi/console.yaml`; create `backend/console-gateway/.../http/LogExportController.java` and test; modify `RoutingLogService`'s sibling export seam in `dp-core`.

**Interfaces — Produces:** `GET /dp/instances/{instanceId}/logs/{component}/export` (`operationId: exportLogs`, `x-sm-permission: logs.read`, `x-sm-scope: instance`), `Content-Type: text/plain`, `Content-Disposition: attachment`; bounded by `sm.dp.logs.export-max-bytes` and `sm.dp.logs.export-deadline`; a truncated export ends with a trailing marker line stating that it was bounded or had gaps (fixed text). Agent placements only; SSH placements answer the existing `501` shape (no new SSH export).

- [ ] **Step 1: Write failing tests:** `ExportStreamsChunksInOrderAsDownload`; `ExportStopsAtMaxBytesWithMarker`; `ExportCancelsAgentRequestWhenClientDisconnects`; `ExportRequiresLogsReadAtInstanceScope`; `ExportOnSshPlacementIs501`; `ExportGapsAppearAsMarkerLines`. A contract check that the new operation carries `x-sm-permission` and `x-sm-scope` (the repo's existing schema-mirror tests).
- [ ] **Step 2: Run** `./mvnw -pl console-gateway -am test` — expected FAIL.
- [ ] **Step 3: Implement** the controller over `AgentLogPort.openExport`, streaming with a bounded buffer, and the OpenAPI operation.
- [ ] **Step 4: Run** `./mvnw verify` (touched modules) and `./contracts/run-checks.sh` — expected PASS.
- [ ] **Step 5: Commit** `feat(console-gateway): bounded agent log export`.

---

### Task 11: Runbook, full verification, results

**Files:** Modify `go-agent/RUNBOOK.md`; modify this plan (Results section).

- [ ] **Step 1: Add a "Logs" section to `RUNBOOK.md`:** enabling the `logs` role, the default cap env/flag, where the ring lives, how a gap appears, what a degraded page means.
- [ ] **Step 2: Run every gate** and paste the actual output tails into Results: `cd go-agent && make test && make test-integration`; `./contracts/run-checks.sh`; `cd backend && ./mvnw verify`. A failing gate is reported as failing.
- [ ] **Step 3: Manual walk-through against the fake gateway:** `make fake-gateway` with the `logs.yaml` scenario and a real container; confirm a page, an export and a cap change by eye; record the commands.
- [ ] **Step 4: Fill the Results section** (what differed from the plan and why) and list the unresolved owner question from Task 9.
- [ ] **Step 5: Commit** `docs: agent MI logs runbook and plan results`.

---

## Self-review

- **Spec coverage:** §3 agent → Tasks 3–5; §4 wire → Tasks 1–2, 5; §5 DP (service, routing, export, cap) → Tasks 7–10; §6 authorization → Prerequisites + Task 9; §7 errors → Tasks 4, 5, 7, 8 tests; §8 testing → each task plus Task 11; §9 open questions → Prerequisites, Task 9, Task 11.
- **Gap noted:** console log view and cap control are out of scope per the spec (§1).
- **Types:** `LogPage`/`LogChunk` names and `UnavailableReason` values are the same in Tasks 1, 2, 5, 7. `msg.LogsOutbound`/`LogsInbound` are introduced in Task 2 and used in Task 5.
- **Known assumption to verify while executing:** the exact table name for `log_max_bytes` (Task 8) and the placement-transport discriminator are read from `PlacementRows` and `AgentPlacements`, not guessed here.

## Results

*(Filled after execution.)*
