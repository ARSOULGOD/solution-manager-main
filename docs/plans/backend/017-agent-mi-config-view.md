# Agent MI config view Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An operator can read the allow-listed keys of a managed integration's live `application.yml` from an agent placement; every other key is hidden on the host.

**Architecture:** A request/reply pair on the existing `Connect` stream. The go-agent reads the placement's config file, flattens the YAML and keeps only allow-listed key paths. The gateway broker exposes a typed port in `dp-api`. The data plane builds the allow-list, re-checks the reply and serves `GET .../config` under `config.view`. This mirrors the logs feature (plan 016) in shape and in file layout.

**Tech Stack:** Go (go-agent, protobuf), Java 17 / Spring Boot (agent-gateway, dp-api, dp-core, console-gateway, cp-api), OpenAPI, Postman.

**Spec:** [`docs/design/backend/agent-gateway/specs/2026-10-24-agent-mi-config-view-design.md`](../../design/backend/agent-gateway/specs/2026-10-24-agent-mi-config-view-design.md). Read it and plan 016's Results first (the broker, the routing and the Docker-Maven test recipe are all described there).

## Global Constraints

- I6: no secret value, and no key name or value outside the allow-list, leaves the host, is logged, or appears in an error. Replies carry fixed text only.
- I5: the organization comes from the session; the placement is re-resolved under it; bind parameters only.
- I1: no MI change. I7 / AGW-D2: the gateway holds no datasource; it is reached through a typed port in `dp-api` with a contract kit.
- AGW-D11: proto changes append new field numbers only: `GatewayToAgent.config_view_request = 17`, `AgentToGateway.config_view_reply = 12`.
- No new permission verb: `config.view` (already `Permission.CONFIG_VIEW`). No decision id is allocated. Published migrations are frozen; this plan adds none.
- Default allow-list: `server.port`, `management.*`, `logging.*`, `info.*`. Defaults: timeout 10s, max bytes 256 KiB, one in-flight view per agent.
- Reason vocabulary (closed): `PLACEMENT_NOT_IN_LEASE`, `FILE_MISSING`, `FILE_UNREADABLE`, `FILE_TOO_LARGE`, `INVALID_YAML`, `NOT_PERMITTED`, `TOO_MANY_KEYS`; the DP maps anything else to `UNKNOWN`.
- Commit messages end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Do not push or merge.
- Java runs through Docker Maven (`maven:3.9-eclipse-temurin-17`); the recipe is in plan 016's Results. Known pre-existing reds (not ours): `SchemaMirrorLockTest` (1 error before our fields), `InventoryPlacementOperationsTest` x2, `PrivateKeyInspectorTest`, `ConnectionOrchestratorTest` x2, `contracts/run-checks.sh` Python preflight, and the launcher context (`sm_control` V028 drops `'agent'`), so launcher IT edits are verified by reading only.

## Review Focus

1. A secret under an unexpected key name (`datasource.password`, `app.api-key`) is hidden, and only counted in `hiddenCount`.
2. A YAML alias bomb or deeply nested file must not exhaust agent memory or time.
3. A list value (`logging.level: [..]`, `management.endpoints.web.exposure.include: [a, b]`) is flattened to indexed paths and matched correctly; a secret inside a list under a listed prefix is the operator's list to own, never silently widened.
4. A reply carrying one key off the sent list is discarded whole by the DP, not filtered and forwarded.
5. A placement leaving the lease while a view is pending ends the request at once and frees the per-agent slot.

---

## File Structure

| File | Responsibility |
|---|---|
| `contracts/agent/v1/agent_gateway.proto` | Two new messages and two oneof members |
| `go-agent/internal/wire/pb/`, `wire/codec/`, `msg/` | Regenerated pb (per `run-checks.sh`), codec and msg types |
| `go-agent/internal/configview/filter.go` | Pure YAML parse, limits, flatten and allow-list match |
| `go-agent/internal/agent/configview_role.go` | Handler: resolve placement path, read file, call the filter, reply |
| `backend/dp-api/.../agw/AgentConfigViewPort.java` (+ `AgentConfigView`) | Typed port and reply record |
| `backend/agent-gateway/.../AgentConfigViewBroker.java` | Admit, send on `Connect`, await reply, release; ends on lease loss |
| `backend/dp-core/.../configview/AgentConfigViewService.java` + `ConfigViewConfiguration.java` | Allow-list, re-check, mapping |
| `backend/console-gateway/.../http/ConfigViewController.java` | `GET .../config` |
| `contracts/openapi/console.yaml`, `ENDPOINT-INVENTORY.md`, Postman, `ConsoleOperations.java` | Contract and registration (`viewConfig` -> `CONFIG_VIEW`) |
| `docs/runbooks/backend/read-agent-config.md`, `DECISIONS.md` | Runbook and open items |

---

### Task 1: Wire contract and Go codec

**Files:**
- Modify: `contracts/agent/v1/agent_gateway.proto`; regenerate `go-agent/internal/wire/pb/agent_gateway.pb.go`
- Modify: `go-agent/internal/msg/msg.go`, `go-agent/internal/wire/codec/codec.go`
- Test: `go-agent/internal/wire/codec/codec_test.go`

**Interfaces:**
- Produces: proto `ConfigViewRequest{request_id, placement_id, repeated string allow, uint32 max_bytes}`; `ConfigViewReply{request_id, ConfigViewStatus status, repeated ConfigEntry entries, uint32 hidden_count, bool truncated, ConfigViewReason reason}`; `ConfigEntry{path, value}`; enums `ConfigViewStatus{..._UNSPECIFIED, _OK, _REFUSED}` and `ConfigViewReason` (prefix-named values for the seven reasons plus `_UNSPECIFIED`). Go: `msg.ConfigViewRequest`, `msg.ConfigViewReply`, `msg.ConfigEntry`, codec round-trip functions named like the log ones.

- [ ] **Step 1: Write the failing test** `TestConfigViewRoundTrip` and `TestConfigViewReasonsAreClosed` in `codec_test.go`: a request and an OK reply with two entries survive encode/decode field-for-field; every reason value maps to a distinct msg constant.
- [ ] **Step 2: Run** `cd go-agent && go test ./internal/wire/... -run ConfigView` — expect FAIL (undefined).
- [ ] **Step 3: Append the messages and enums to the proto** with the field numbers in Global Constraints (enum value names carry the enum prefix, as for `UnavailableReason`). Regenerate the pb package in place per `contracts/run-checks.sh` (generators are in `~/go/bin`). Add the msg types and codec mappings beside the log ones.
- [ ] **Step 4: Run** `go build ./... && go test ./internal/wire/...` — expect PASS. Also run `buf`/`run-checks.sh` proto-compat step if its preflight allows; otherwise record it as not run.
- [ ] **Step 5: Commit** `feat(wire): config view request and reply`.

### Task 2: Allow-list filter (Go, pure)

**Files:**
- Create: `go-agent/internal/configview/filter.go`
- Test: `go-agent/internal/configview/filter_test.go`

**Interfaces:**
- Produces: `type Limits struct{ MaxBytes, MaxDepth, MaxNodes, MaxKeys int }`; `type Entry struct{ Path, Value string }`; `type Result struct{ Entries []Entry; Hidden int; Truncated bool }`; `func Filter(doc []byte, allow []string, lim Limits) (Result, error)`; errors `ErrTooLarge`, `ErrInvalidYAML`, `ErrTooManyKeys` (use `errors.Is`).

- [ ] **Step 1: Write failing tests**, one per Review Focus 1-3, each with the spec's exact default allow-list:
  `TestSecretUnderUnexpectedKeyIsHidden` (`datasource.password: x` -> absent from Entries, `Hidden` counts it), `TestListsFlattenToIndexedPaths` (`management.endpoints.web.exposure.include: [health, info]` -> `...include[0]`, `...include[1]`), `TestPrefixWildcardMatchesSubtreeOnly` (`management.*` matches `management.server.port`, not `managementx.a`), `TestExactPathMatchesOnlyItself`, `TestAliasBombRefused` (nested anchors expand past `MaxNodes` -> `ErrTooManyKeys` or `ErrTooLarge`, within a bounded time), `TestDepthLimit`, `TestInvalidYAML`, `TestOversizedRefusedBeforeParse`, `TestHiddenKeyNamesNeverAppear` (no Entry path or error text contains a hidden key's name).
- [ ] **Step 2: Run** `go test ./internal/configview/` — expect FAIL.
- [ ] **Step 3: Implement `Filter`** using the YAML library already in `go.mod` (check first; add none if one exists). Parse into the library's node tree so aliases can be counted and capped before expansion; flatten to `a.b[0].c`; allow-list match is exact path or `prefix.*`; scalars only are emitted; `Hidden` counts every non-emitted scalar leaf.
- [ ] **Step 4: Run** `go test ./internal/configview/ -race` — expect PASS.
- [ ] **Step 5: Commit** `feat(go-agent): config view allow-list filter`.

### Task 3: Agent handler and role wiring

**Files:**
- Create: `go-agent/internal/agent/configview_role.go`
- Modify: the Connect-stream dispatcher where `LogRequest` is routed (see `logs_role.go` and the reactor) and `go-agent/internal/fakegw/` (add a config-view scenario beside `logs.go`)
- Test: `go-agent/internal/agent/configview_integration_test.go`

**Interfaces:**
- Consumes: Task 1 msg types, Task 2 `Filter`/`Limits`, `runtime.Placement.HostPath`, `configwriter` path checks (symlink escape).
- Produces: a handler that answers every `ConfigViewRequest` with exactly one `ConfigViewReply`; refusals carry a reason and no entries.

- [ ] **Step 1: Write failing tests** (against the fake gateway): `TestViewReturnsAllowedKeysOnly`, `TestPlacementNotInLeaseRefused`, `TestMissingFile`, `TestSymlinkRefusedAsNotPermitted`, `TestOversizedFileRefused`, `TestInvalidYamlRefused`, `TestRequestLimitsAreClamped` (a request `max_bytes` above the agent hard cap is clamped), `TestNoValueOrPathInLogs` (capture log output; assert no file path, key name or value appears).
- [ ] **Step 2: Run** `go test ./internal/agent/ -run ConfigView` — expect FAIL.
- [ ] **Step 3: Implement the handler**: look the placement up in the lease, take `HostPath`, apply the symlink/directory check, read at most the cap, call `Filter`, map errors to reasons, send the reply. One reply per request; never panic on a hostile file.
- [ ] **Step 4: Run** `go build ./... && go vet ./... && go test ./... -race` — expect PASS.
- [ ] **Step 5: Commit** `feat(go-agent): answer config view requests`.

### Task 4: Gateway port and broker

**Files:**
- Create: `backend/dp-api/src/main/java/com/solace/sm/dp/api/agw/AgentConfigViewPort.java`, `AgentConfigView.java`
- Create: `backend/agent-gateway/src/main/java/com/solace/sm/agw/AgentConfigViewBroker.java`
- Modify: `AgentGatewayService` (route `config_view_reply`, and call the broker where the lease changes, as `AgentLogBroker.placementsLeft` is called) and `AgentGatewayConfiguration`
- Test: broker unit tests, `AgentGatewayConfigViewTest`, and a contract kit in `dp-api` test fixtures, run by the broker

**Interfaces:**
- Produces: `AgentConfigViewPort { AgentConfigView read(String tenantId, String agentId, String placementId, List<String> allow, int maxBytes); }`; `record AgentConfigView(Status status, List<Entry> entries, int hiddenCount, boolean truncated, String reason)` with `Reason` mapped to the closed vocabulary (anything else -> `UNKNOWN`); the port never throws for a refusal.

- [ ] **Step 1: Write failing tests**: reply routes by request id; deadline returns `UNAVAILABLE`; slot released on success, timeout, stream end and refusal (no leak); second request while one is in flight for the same agent is refused `BUSY`; a placement leaving the lease ends its pending view at once with `PLACEMENT_NOT_IN_LEASE` (Review Focus 5); a reply for an unknown or finished request id is dropped; the kit asserts these for any adapter.
- [ ] **Step 2: Run** the new tests via Docker Maven — expect FAIL.
- [ ] **Step 3: Implement** the port, record and broker, copying `AgentLogBroker`'s admit/release/deadline discipline (arm the deadline only after a successful send; end requests of a replaced stream). Remember I7: `agent-gateway` imports `dp-api` only.
- [ ] **Step 4: Run** `agent-gateway`, `dp-api` `verify` and arch-tests `*BoundaryTest` — expect PASS.
- [ ] **Step 5: Commit** `feat(agent-gateway): config view port and broker`.

### Task 5: Data-plane service and re-check

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/configview/AgentConfigViewService.java`, `ConfigViewConfiguration.java`
- Create (dp-api): `backend/dp-api/src/main/java/com/solace/sm/dp/api/configview/ConfigViewService.java`, `ConfigViewResult.java`
- Test: `AgentConfigViewServiceTest`, `ConfigViewWiringTest`

**Interfaces:**
- Consumes: `AgentConfigViewPort`; the placement and agent lookups `AgentLogService` already uses (reuse them; do not add a second lookup path).
- Produces: `ConfigViewService { ConfigViewResult view(String organizationId, String placementId); }`; properties `sm.dp.config-view.allow`, `.timeout` (10s), `.max-bytes` (262144).

- [ ] **Step 1: Write failing tests**: `RecheckRejectsReplyWithStrayKey` (one path off the sent list -> whole reply discarded, fixed refusal; add a mutation check: remove the re-check and the test must fail), `TenantIsolation` (another organization's placement is 404), `RetiredPlacementRefused`, `NonAgentPlacementIs501`, `AllowListIsSentFromProperty`, `RefusalReasonsMapToResult`, `NoValueInLogs`.
- [ ] **Step 2: Run** — expect FAIL.
- [ ] **Step 3: Implement** the service and configuration (bean names must not collide; follow the `LogConfiguration` naming and add a wiring test that the context has exactly one bean per type).
- [ ] **Step 4: Run** the dp-core unit tests for the new package and `SmDataBootstrapIT` if touched — expect PASS apart from the known reds.
- [ ] **Step 5: Commit** `feat(dp): config view service with allow-list re-check`.

### Task 6: Console route, authorization and contract

**Files:**
- Create: `backend/console-gateway/.../http/ConfigViewController.java`
- Modify: `backend/cp-api/.../authz/ConsoleOperations.java` (register `viewConfig` -> `Permission.CONFIG_VIEW`), `contracts/openapi/console.yaml`, `ENDPOINT-INVENTORY.md`, the Postman collection (regenerate; `check-postman.py` must pass), `ProductionAuthenticationIT` exact operation set, `SchemaMirrorLockTest` mirror, `DeclaredBadRequestsTest` if it counts a 400 site
- Test: controller test, `ConsoleOperationsMirrorTest`

**Interfaces:**
- Produces: `GET .../placements/{placementId}/config` (use the same path prefix as `exportLogs`), operation id `viewConfig`; response `{entries:[{path,value}], hiddenCount, truncated}`; statuses 404 (file missing or unknown placement), 413 (too large), 422 (invalid YAML), 503 (agent not connected), 501 (non-agent placement). No file path, host or container info anywhere.

- [ ] **Step 1: Write failing tests**: controller maps each result and reason to its status; `config.view` required (a caller without it is 403 through the single `authorizationService.require` path); organization comes from the session; response contains no path or host text; `ConsoleOperationsMirrorTest` sees `viewConfig`.
- [ ] **Step 2: Run** via Docker Maven — expect FAIL.
- [ ] **Step 3: Implement** the controller and edit the contract files; regenerate Postman with the repo's script.
- [ ] **Step 4: Run** console-gateway tests (known reds only), cp-api `verify`, `validate-openapi.py`, `check-postman.py` — expect PASS apart from the known reds. Record the launcher IT edit as not run.
- [ ] **Step 5: Commit** `feat(console-gateway): view an agent placement's config`.

### Task 7: Docs and closing checks

**Files:**
- Create: `docs/runbooks/backend/read-agent-config.md` (follow `docs/runbooks/README.md`; add its index row)
- Modify: `docs/design/DECISIONS.md` (open items: confirm `config.view` scope row, audit row for reads, SSH placements later — no decision id), `go-agent/RUNBOOK.md`, this plan's Results section

- [ ] **Step 1: Write the runbook**: properties, the allow-list and how to change it, statuses and reasons, what `hiddenCount` means, why a key may be missing, the I6 guarantees and limits.
- [ ] **Step 2: Add the open items and the Results section** (what was built, deviations, pre-existing reds, what was not run).
- [ ] **Step 3: Verify**: `cd go-agent && go build ./... && go vet ./... && go test ./...`; Java `verify` for agent-gateway, dp-api, cp-api, arch-tests `*BoundaryTest`, the new dp-core and console-gateway tests; all touched markdown links resolve.
- [ ] **Step 4: Commit** `docs(config-view): runbook, open items, results`. Do not push.

---

## Self-review

- **Spec coverage:** §3 wire -> Task 1; §4 agent -> Tasks 2-3; §5 gateway -> Task 4; §6 DP and console -> Tasks 5-6; §7 limits -> Tasks 2, 3, 5; §8 tests -> in every task; §9 open items -> Task 7.
- **Types:** `Entry`, `Result`, `Limits` (Task 2) are used by Task 3; `AgentConfigView`/`AgentConfigViewPort` (Task 4) by Task 5; `ConfigViewService`/`ConfigViewResult` (Task 5) by Task 6.
- **Known unknown for Task 4:** the broker so far only replies to log requests on the `Logs` RPC; a request/reply on `Connect` is new, so the implementer reads `AgentGatewayService`'s `Connect` handling first and reports any structural surprise instead of improvising a new stream.
