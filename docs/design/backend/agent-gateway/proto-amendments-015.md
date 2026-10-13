# Plan 015 T0.3 Step 1 — the wire additions (DRAFT, not in `contracts/`)

**Status: DRAFT, reviewed in T0.3 Step 5 (dispositions at the end).** Nothing here is generated, compiled into either
toolchain or visible to a check: T1.2 moves `go-agent/proto/agentgateway/v1draft/agent_gateway.proto` to
`contracts/agent/v1/` and **T2.3** appends what this file specifies, taking its field numbers verbatim.

| | |
|---|---|
| **Base** | `origin/dp-agent-integration` @ `87e338e1`, `go-agent/proto/agentgateway/v1draft/agent_gateway.proto` (529 lines), read with `git show`. Package `solace.sm.agentgateway.v1draft` is kept (the gRPC service name must not move). |
| **Rule** | `AGW-D11`: **append-only, new field numbers only**, enum members appended, nothing renumbered, retyped or removed; `contracts/agent/v1/check_proto_append_only.py` (T1.2) is the gate. |
| **Authority** | Plan 015 T0.3 Step 1, `OR-126` (A5, A6, A7), `OR-128`, `OR-112` (same-clock rule), `OR-101` (no invented health), `OR-73` (cadence). Decision texts: [`decisions-015-draft.md`](decisions-015-draft.md) (`AGW-D26`–`AGW-D30`, `AG-D26`–`AG-D29`). |
| **Measured** | Highest existing numbers at `87e338e1`: `ActualStateReport` 10, `CommandAck` 6, `CommandOffer` 11, `AgentToGateway.msg` 10, `GatewayToAgent.msg` 12, `Operation` 4, `ErrorCode` 37, `Capability` 3. The numbers below are the next free ones. |

## 1. The numbers, in one table

| # | Addition | Where | Number |
|---|---|---|---|
| (a) | `HealthObservation health` | `ActualStateReport` | **11** |
| (a′) | `optional Timestamp container_started_at` (host clock) | `ActualStateReport` | **12** |
| (b) | `optional Timestamp container_started_at` (host clock; APPLY, REMATERIALIZE, RESTART) | `CommandAck` | **7** |
| (c) | `optional string expected_current_sha256` | `CommandOffer` | **12** |
| (c′) | `OPERATION_REMATERIALIZE` | enum `Operation` | **5** |
| (d) | `DiscoveryReport discovery_report` | `AgentToGateway.msg` | **11** |
| (d) | `DiscoveryAck discovery_ack` | `GatewayToAgent.msg` | **13** |
| (d′) | `string binding_id` in `DiscoveryResult` (the placement id) | `DiscoveryResult` | **4** |
| (e) | `TelemetryDeclarations telemetry_declarations` | `GatewayToAgent.msg` | **14** |
| (f) | `HealthProbe health_probe` (the declared probe, `OR-130` D-4) | `TelemetryDeclarations` | **5** |
| (f) | `uint32 http_status` / `string actuator_status` / `ProbeFailure failure` | `HealthObservation` | **4 / 5 / 6** |
| — | `ERROR_CODE_TARGET_MOVED` | enum `ErrorCode` | **38** |
| — | `ERROR_CODE_RENDER_CHANGED` | enum `ErrorCode` | **39** |
| — | `CAPABILITY_DISCOVERY` / `CAPABILITY_REMATERIALIZE` / `CAPABILITY_DECLARATIONS` | enum `Capability` | **4 / 5 / 6** |

**What goes beyond the literal text of Step 1, and why** (each is a pure subtraction if the owner or the review
rejects it; none is decided by this draft's existence):

* **(a′) `ActualStateReport.container_started_at`** — `§4.2.1` and T3.3 say report items gain "health and container
  start time"; Step 1 lists only the ack's. The existing `restart_observed_at` (field 7) cannot serve: the Go agent
  *adds the clock offset* to it (`loop.go:276`, host time → DP time). `OR-112`'s same-clock rule forbids comparing a
  data-plane timestamp with a host one (`V018`, `V020`), so the DP needs the host's **uncorrected** `StartedAt` in the
  report as well as in the ack. `restart_observed_at` is left untouched.
* **(d′) `DiscoveryResult.binding_id`** — Step 1 gives `DiscoveryResult{container_id, state, code}`. With that shape the
  agent can never learn which placement a discovered container became, and a command names only a `binding_id` (the
  placement id, `AGW-D25`): nothing would let it find the container (`AG-D20`'s `com.solace.sm.binding-id` label cannot exist
  for an id the DP mints *after* discovery). `binding_id` is set when the state is `REGISTERED` (and echoed for `UNDECLARED` /
  `RETIRED`) and empty otherwise; the agent persists `container_name -> binding_id` and resolves a command's container by
  name (`AG-D26`). This is a hole in the plan's wire, not an extension: without it `OR-27` registers placements no command can
  reach. Draft finding **F-1**.
* **(c′) `OPERATION_REMATERIALIZE`** — `rematerializeIfCurrent` needs an operation the agent can *refuse when it
  does not know it*. Carrying it as `OPERATION_APPLY` plus a present `expected_current_sha256` would make an agent
  built before this field silently ignore the precondition and overwrite — exactly what the precondition exists to
  prevent. An unknown enum value is preserved by proto3 and refused by the agent (`ERROR_CODE_CAPABILITY_MISSING`).
* **`ERROR_CODE_TARGET_MOVED`** (the agent's `REFUSED` for a failed precondition; the DP's `reconcile.target.moved`)
  and **`ERROR_CODE_RENDER_CHANGED`** (the gateway's refusal of a pull when a re-render after an SM restart no longer
  matches `agent_command.config_hash`; the DP's `agent.payload.render.changed`) are the two codes T3.1 and T2.3 name
  without a wire home.
* **Three `Capability` members** — an old agent given an unknown `oneof` member of `GatewayToAgent` sees an empty
  message. `AGW-D11` gives the closed capability enum for exactly this: the gateway sends `DiscoveryAck`,
  `TelemetryDeclarations` and `OPERATION_REMATERIALIZE` **only** to a session whose `SessionOpen.capabilities_supported`
  names the matching member. They are *feature* capabilities: never put in a lease's `capabilities` and never in
  `SessionAccept.required_capabilities`, so lease authority (`apply-config`, `lifecycle`, `report-only`) is unchanged.

## 2. The draft text (what T2.3 appends)

```proto
// ---- appended by plan 015 T2.3 (AGW-D11: new numbers only) ----

enum Capability {                       // existing 0..3 unchanged
  CAPABILITY_DISCOVERY = 4;             // sends DiscoveryReport, understands DiscoveryAck
  CAPABILITY_REMATERIALIZE = 5;         // understands OPERATION_REMATERIALIZE + expected_current_sha256
  CAPABILITY_DECLARATIONS = 6;          // understands TelemetryDeclarations
}

enum Operation {                        // existing 0..4 unchanged
  OPERATION_REMATERIALIZE = 5;          // write only if the file on the host still has expected_current_sha256
}

enum ErrorCode {                        // existing 0..37 unchanged
  ERROR_CODE_TARGET_MOVED = 38;         // CommandAck REFUSED: the host file is not the expected current one
  ERROR_CODE_RENDER_CHANGED = 39;       // gateway refuses a CommandPull: the re-render differs from the recorded one
}

// ---- (a) health ----
enum HealthState {
  HEALTH_STATE_UNSPECIFIED = 0;
  HEALTH_STATE_HEALTHY = 1;             // Docker health status "healthy"
  HEALTH_STATE_STARTING = 2;            // "starting"
  HEALTH_STATE_UNHEALTHY = 3;           // "unhealthy"
  HEALTH_STATE_NOT_DECLARED = 4;        // the container has no health check at all (OR-101: never invented)
  HEALTH_STATE_UNREACHABLE = 5;         // the container is not running, or inspect failed
}
enum ProbeStrength {
  PROBE_STRENGTH_UNSPECIFIED = 0;       // the agent cannot know it (a Docker HEALTHCHECK says nothing of strength)
  PROBE_STRENGTH_READINESS = 1;         // mirrors solution-package.schema.json health.probe.strength
  PROBE_STRENGTH_LIVENESS = 2;
}
message HealthObservation {
  HealthState state = 1;
  google.protobuf.Timestamp observed_at = 2;   // the agent's clock (display; the DP stamps its own receipt time)
  ProbeStrength probe_strength = 3;            // informational: the DP's gate reads the DECLARED strength, never this
}
// OR-130 D-4: the agent RUNS the package's declared probe and reports FACTS. The DP maps them with ProbeResultMapper,
// identically to dp-remote. HealthObservation.state (below) is the Docker health check: an informational liveness hint, never the gate.
// HealthObservation gains (appended; no collision: the message is new in this draft and uses 1..3):
//   uint32 http_status = 4;           // the probe's HTTP status; 0 = no HTTP response
//   string actuator_status = 5;       // the "status" string of an actuator-health JSON body ("UP", "DOWN", ...); "" otherwise
//   ProbeFailure failure = 6;         // why there was no response
enum ProbeFailure {
  PROBE_FAILURE_NONE = 0;
  PROBE_FAILURE_REFUSED = 1;            // connection refused on the host loopback
  PROBE_FAILURE_TIMEOUT = 2;
}
// ActualStateReport gains:
//   HealthObservation health = 11;
//   optional google.protobuf.Timestamp container_started_at = 12;  // Docker State.StartedAt, host clock, NO offset applied

// ---- (b) CommandAck gains:
//   optional google.protobuf.Timestamp container_started_at = 7;
//   // APPLY and REMATERIALIZE (both close a MATERIALIZE attempt): the container's StartedAt read back when the write was
//   // verified (the OR-112 baseline); RESTART: the StartedAt after the restart. Host clock, no offset. Unset for PREFLIGHT, STOP.

// ---- (c) CommandOffer gains:
//   optional string expected_current_sha256 = 12;
//   // Only on OPERATION_REMATERIALIZE. Bare lowercase hex (AGW-D17 item 2). Present and empty = "the file must
//   // still be ABSENT"; absent field on a REMATERIALIZE offer is a protocol error (REFUSED, ERROR_CODE_TARGET_MOVED
//   // is NOT used for it: ERROR_CODE_KEY_HASH_MISMATCH is).

// ---- (d) discovery: facts, not conclusions ----
message DiscoveredMount { string source = 1; string destination = 2; }
message DiscoveredPort  { uint32 container_port = 1; uint32 host_port = 2; }   // only PUBLISHED ports
message DiscoveredContainer {
  string container_id = 1;                    // full hex id
  string container_name = 2;                  // without the leading "/"
  string image_ref = 3;                       // as Docker reports it; never a digest the agent computed
  map<string, string> labels = 4;             // ONLY keys with the prefix "com.solace.sm." (others are never sent)
  repeated DiscoveredMount mounts = 5;        // bind mounts only
  repeated DiscoveredPort published_ports = 6;
}
message DiscoveryReport {                     // a complete snapshot, never a delta
  Envelope envelope = 1;
  repeated DiscoveredContainer items = 2;     // every container (running or stopped) labelled com.solace.sm.instance
}
enum DiscoveryState {
  DISCOVERY_STATE_UNSPECIFIED = 0;
  DISCOVERY_STATE_PENDING = 1;
  DISCOVERY_STATE_REGISTERED = 2;
  DISCOVERY_STATE_REFUSED = 3;
  DISCOVERY_STATE_ABANDONED = 4;
  DISCOVERY_STATE_UNDECLARED = 5;
  DISCOVERY_STATE_RETIRED = 6;
}
message DiscoveryResult {
  string container_id = 1;
  DiscoveryState state = 2;
  string code = 3;                            // the DP's closed GatewayCode vocabulary (decisions-015-draft AGW-D30); opaque to the agent
  string binding_id = 4;                      // the placement id once REGISTERED (also echoed for UNDECLARED / RETIRED); else ""
}
message DiscoveryAck {
  Envelope envelope = 1;
  string in_reply_to_message_id = 2;
  repeated DiscoveryResult results = 3;       // one per item, in the report's order
}
// AgentToGateway.msg   gains:  DiscoveryReport discovery_report = 11;
// GatewayToAgent.msg   gains:  DiscoveryAck discovery_ack = 13;

// ---- (e) telemetry declarations, gateway -> agent ----
enum OnUnavailable {
  ON_UNAVAILABLE_UNSPECIFIED = 0;
  ON_UNAVAILABLE_DEGRADE = 1;
  ON_UNAVAILABLE_ERROR = 2;                   // behaves as DEGRADE in v1 (OR-92 OQ-13)
}
message HttpJsonSource {
  uint32 port = 1;                            // the CONTAINER port; the agent maps it to the host port Docker published
  string path = 2;                            // starts with "/"
  string pointer = 3;                         // RFC 6901; "" is the whole document
  OnUnavailable on_unavailable = 4;
}
message TelemetryMetric {
  string name = 1;
  MetricType metric_type = 2;                 // COUNTER or GAUGE in v1 (HISTOGRAM refused by the agent, as today)
  string unit = 3;
  oneof source { HttpJsonSource http_json = 4; }   // later source kinds append here
}
message TelemetryDeclarations {
  Envelope envelope = 1;
  string binding_id = 2;                      // on the agent path this carries the PLACEMENT id (AGW-D25)
  google.protobuf.Duration interval = 3;      // the fixed 5 s of OR-73 / OR-128 (8); never the package's intervalSeconds
  repeated TelemetryMetric metrics = 4;       // the COMPLETE set for the binding: it replaces the previous set atomically
}
// GatewayToAgent.msg   gains:  TelemetryDeclarations telemetry_declarations = 14;

// ---- (f) the declared health probe, per binding (OR-130 D-4) ----
enum ProbeKind {
  PROBE_KIND_UNSPECIFIED = 0;
  PROBE_KIND_ACTUATOR_HEALTH = 1;       // GET path, parse the JSON "status"
  PROBE_KIND_HTTP_STATUS = 2;           // GET path, the HTTP status alone
}
message HealthProbe {                   // mirrors dp.api.inventory.HealthProbeSpec(kind, path, hostPort, strength)
  ProbeKind kind = 1;
  string path = 2;                      // starts with "/"
  uint32 host_port = 3;                 // the HOST port Docker published: the agent GETs the host loopback here
  ProbeStrength strength = 4;           // the DECLARED strength; the agent only carries it, the DP's gate reads it
}
// TelemetryDeclarations gains:  HealthProbe health_probe = 5;
//   // Absent = the package declares no probe: NOTHING is probed and the DP records NOT_APPLICABLE (OR-101). Sent only to a
//   // session whose capabilities_supported names CAPABILITY_DECLARATIONS (no new Capability member).
```

## 3. Semantics each addition must keep

**(a) Health (`OR-130` D-4, supersedes this section's first draft).** The agent **runs the package's declared probe itself** (the
`HealthProbe` of (f): a GET on the host loopback at `host_port`) and reports *facts about the probe*: `http_status`,
`actuator_status`, or `failure` (refused / timeout). It never maps to the DP's nine-value health vocabulary and never infers
"healthy" from "running" (`OR-101`, `AG-D27`). `HealthObservation.state` is the container's Docker health state (`NOT_DECLARED`
when it has no health check, `UNREACHABLE` when it is not running or inspect failed) and is an **informational liveness hint,
never the gate**. The DP derives the placement's health with the existing `ProbeResultMapper` (identical to dp-remote);
**a placement whose package declares no probe is `NOT_APPLICABLE` whatever the agent reports** (`OR-101`) and is not probed.
`RESTART_EXPECTED` and `INACTIVE_REPLICA` stay the engine's. Persisted **on change only** (`OR-39`).

**(b)/(a′) Start time.** Raw Docker `State.StartedAt`, the host's clock. The agent applies **no** `Clock.Offset()` to
it. The DP stores the ack's value as the attempt's `write_baseline.startedAt` (`V020`) **and**, in the same transaction, as
the placement's start observation (`V018`), which only ever moves **forward**; a report's value updates the observation by the
same rule. *A start different from and later than the baseline is the new file loaded* (`OR-112`). The ack's value is the
baseline everywhere (review R-8); a stale report can never lower it. No data-plane timestamp is compared with either, anywhere. `0001-01-01` (never started) is
reported as **absent**, not as that value.

**(c) Precondition.** On `OPERATION_REMATERIALIZE` the agent hashes the file as it stands on the host *before*
writing: equal to `expected_current_sha256` (or the file absent when it is present and empty) → write as `APPLY`;
otherwise `OUTCOME_REFUSED`, `ERROR_CODE_TARGET_MOVED`, **no write**. The check and the write are one journaled step
(`AG-D4`), so a crash between them re-evaluates the precondition. The hash is the plain SHA-256 of the file: it exists on the wire, in the DP's memory and in the agent's own on-host command
journal (`AG-D4`, `commands.go:250`, measured), and is **stored by SM nowhere** (`OR-32`); it is not logged on either side
(`AG-D29`).

**(c″) Ceilings, capability and refusals the agent must follow (review R-7).** `OPERATION_REMATERIALIZE` is an `apply-config`
operation. The agent finds a step's ceiling in the lease's `verb_ceilings` keyed by the lowercase operation name; a missing key
gives a zero ceiling and every step fails `step deadline exceeded` (the `AGW-D17` item 3 defect), so **the CP's ceilings gain
`rematerialize`** (60 s, as `apply`) and the agent's operation-to-capability and ceiling tables gain the member (T2.3 tests it). An
`Error{ERROR_CODE_RENDER_CHANGED}` answering a `CommandPull` is a **terminal refusal of that command** (`OUTCOME_REFUSED`,
journaled, acknowledged): the agent's `wireError` handles it as it handles `ERROR_CODE_SUPERSEDED`; without that mapping the pull
is stranded and, if a pull is refused, the `RECEIVED` record never becomes terminal.

**(d) Discovery.** Sent once after every `SessionAccept` and again whenever the set of labelled containers or any
`container_id` changes (Docker `start`, `die`, `destroy` events); each report is the **complete snapshot**, so the DP
needs no delta protocol and a lost report is healed by the next. Only keys with the prefix `com.solace.sm.` leave the
host (a customer's other labels, which may carry secrets, never do — `I6`). Absence never deregisters anything; only
an operator's `undeclareInstance` or `retirePlacement` does (`AGW-D30`). The agent caps one report at 64 items
(ordered by `container_name`), counting the excess in `sm_agent_discovery_truncated_total`; the per-target placement
cap (16) is the DP's. **No field names an environment, an organization or a target**: the DP resolves all three from
the agent's enrolment (`I5`).

**(e) Declarations.** The gateway sends one `TelemetryDeclarations` per binding of the lease after `SessionAccept` and
on any change; a message replaces that binding's set whole. The metrics are the package's `http_json` metric objects
for that placement, exactly the shape `go-agent/examples/telemetry.yaml` parses today; the `--telemetry-config` file
remains for development only (`AG-D28`). A metric the agent cannot collect (histogram, unknown source kind) is dropped
with a counter, never a zero sample.

## 4. Compatibility (both directions)

| Pair | Behaviour |
|---|---|
| Old agent, new gateway | Unknown fields skipped. The gateway never sends `DiscoveryAck`, `TelemetryDeclarations` or `OPERATION_REMATERIALIZE` without the matching `capabilities_supported` member; an old agent has none, so it keeps the file-flag telemetry, sends no discovery and the DP answers `rematerializeIfCurrent` on its placements with `agent.operation.unsupported` (T3.1). |
| New agent, old gateway | **Not supported, and not reachable in v1**: the gateway and the agent ship from this one plan, and no gateway without these members exists in the field. An old gateway would see `discovery_report` as an unset `oneof` (a protocol error to its switch). Recorded as **D-3** (§6) rather than hidden. |
| Both new | Everything above. |

## 5. What each addition costs on each side

| Addition | Gateway (Java) | Agent (Go) | DP |
|---|---|---|---|
| (a), (a′), (f) | `ReportItem` gains `health`, `containerStartedAt` (T2.1a); intake maps the probe facts with `ProbeResultMapper` and persists on change (T3.3); `TelemetryDeclarations` carries the `HealthProbe` (T3.4) | the declared-probe GET on the host loopback, Docker health as a hint, `StartedAt` (T2.3) | `placement_health`, `placement_start_observation` |
| (b) | `AgentAck.containerStartedAt` | ack carries it on APPLY/RESTART | `write_baseline` for the attempt |
| (c), (c′) | `AgentOperation.REMATERIALIZE`, offer field | precondition check before write | `rematerializeIfCurrent` (T3.1) |
| (d) | `AgentDiscoveryPort` (declared T2.1a, stub T2.1b, built T4.1) | scan of `com.solace.sm.instance` containers (T2.3) | `agent_discovery`, tombstones |
| (e) | `AgentTelemetryDeclarationsPort` (declared T2.1a, stub T2.1b, built T3.4) | `declare` replaces a binding's set atomically (T2.3) | the placement's package metrics |

## 6. Open design questions this draft names rather than decides

* **D-1, D-2** *Retired by `OR-130` D-4:* the DP maps the agent's probe facts with the existing `ProbeResultMapper`, identically to
  dp-remote, so there is no Docker-state-to-health mapping to choose (the `STARTING` question disappears) and the strength is the
  declared one (`HealthProbe.strength`); `HealthObservation.probe_strength` stays as an informational field, unused by the gate.
* **D-3** the "new agent, old gateway" row: whether the agent should wait for a gateway-side signal before its first
  `DiscoveryReport`. No such gateway exists in the field, so the draft records the row and adds **no** gateway-side
  signal beyond the three `Capability` members; if a mixed fleet ever appears, the signal is one more appended
  `SessionAccept` field.

* **D-4** (review R-9) *Ruled, `OR-130` D-4:* the agent runs the package's declared probe itself (a `health_path` GET on the published
  port, from the host), so `rolling-health-gated` means the same on both paths; the container's Docker health check is a liveness hint
  only, never the gate. The wire carries the probe: `HealthProbe` (section 2, (f)), the sixth wire addition.

## Mechanical check (2026-10-08)

The base proto at `87e338e1` and the base with section 2 applied were each compiled with `protoc` (the `grpcio-tools` build, in a
throw-away virtualenv in the session scratch directory; nothing installed on the system, nothing added to any pin): both compile;
a descriptor comparison finds **every `(message, number, name, type, label)` of the base present unchanged in the amended file and
every base enum value unchanged** (0 violations); 10 new messages. This is the check `check_proto_append_only.py` (T1.2) will own;
it is not a substitute for it. `buf` is not installed here (T0.4), so `buf build` / `buf breaking` were not run.

## Review dispositions (Step 5)

**One adversarial review** of the four drafts ([`proto-amendments-015.md`](proto-amendments-015.md) this file,
[`agent-console-contract-draft.yaml`](agent-console-contract-draft.yaml), [`schema-015-draft.sql`](schema-015-draft.sql),
[`decisions-015-draft.md`](decisions-015-draft.md)) against `I5`/`I6`/`I7`/`OR-25`/`OR-32`/`AGW-D2`/`OR-27` and plan 015, run once,
read-only, on 2026-10-08 by an Opus session (`claude -p --model opus`, tools limited to Read, Grep, Glob and read-only git; spawned
by the T0.3 implementer because the Agent tool was not available in that session). It returned **1 BLOCKER, 12 MAJOR, 10 MINOR**
findings (R-1..R-23). **One fix round** was applied; the DDL was then replayed again on a fresh `postgres:14` (94 probes, all PASS,
the sm_data and sm_control migrations green) and the console fragment re-merged and re-checked. Dispositions:

| # | Sev. | Verdict | Disposition and where |
|---|---|---|---|
| R-1 | BLOCKER | **Accepted** | Retention reused wire generations. VDB now never deletes the highest `wire_generation` of an (agent, placement); probe: after retention the highest survives. `AGW-D31`; `AgentMaintenanceIT` case named |
| R-2 | MAJOR | **Accepted** | The agent-actor exemption was table- and tenant-unscoped. AGW-EDIT 4 now scopes `AGENT_ENROLLED` to `agent_registration`/`target`, `AGENT_INSTANCE_REGISTERED` to `solution`/`instance`/`scope`, each requiring an ACTIVE registration of the row's organization (consumed, for the second). 6 new probes. `BE-D70` |
| R-3 | MAJOR | **Accepted** | `agent_identity` copies `catalog_solution_id`, `solution`, `declared_package_version` at enrolment; `EnrollmentResult` carries them. VDA; `BE-D71`; `AGW-D30` item 2. Finding R-3 in the closing table |
| R-4 | MAJOR | **Accepted** | `solution_package.package_version` is the whole array (declare uses jsonb `@>`, `JdbcCatalogRepository:132`). FK dropped; the registration stores one member and a BEFORE INSERT trigger checks containment. Probe uses `[1]` loaded / `1` named / `9` refused |
| R-5 | MAJOR | **Accepted** | `renewal_nonce_digest` is `text` with a 64-hex CHECK; the no-bytea probe no longer has an exception. `AGW-D26` |
| R-6 | MAJOR | **Accepted** | One live command per `(intent_key, operation)` (index); `ACKED`-success reuse only if no later attempt closed FAILED. VDA; `BE-D66` item 2; two cases added to the T3.1 tests. Probes: REMATERIALIZE beside a live APPLY allowed, a second refused |
| R-7 | MAJOR | **Accepted** | `rematerialize` joins the lease's `verb_ceilings` and the agent's operation tables; `ERROR_CODE_RENDER_CHANGED` is a terminal refusal in the agent's `wireError`. `proto-amendments-015.md` (c″); `AGW-D24` item 4; `AG-D27` item 5; `AG-D29` |
| R-8 | MAJOR | **Accepted** | `container_started_at` on the REMATERIALIZE ack too; the ack's value is the baseline everywhere and is written to the attempt and to the observation in one transaction; the observation only moves forward. Proto table, (b)/(a'); `AGW-D28` item 2 |
| R-9 | MAJOR | **Accepted; the escalated point ruled** | No declared probe -> `NOT_APPLICABLE` always (`OR-101`); the DP mapping is declared-probe-only. That a Docker health check **stands in** for the declared probe was draft question D-4: **ruled `OR-130` D-4**, the agent runs the declared probe (the sixth wire addition, section 2 (f)) |
| R-10 | MAJOR | **Accepted** | A refusal after reserve abandons the pending instance and retires the placement in the same DP transaction; the three unique placement indexes are pre-checked and answered with codes. `AGW-D30` item 3 |
| R-11 | MAJOR | **Accepted** | Skew window lower bound 24 h (the agent's buffer age), upper 30 s. `AGW-D27` item 5 (the value is still a proposal; `OR-128` (7) did not size it) |
| R-12 | MAJOR | **Accepted** | `AG-D29` rewritten neutral: both variants with their costs, including that (b) fails an apply when the rendering changed during an agent crash; the false "can always re-render" is gone |
| R-13 | MAJOR | **Accepted** | One composite FK `(tenant, apply, intent, key, placement)` onto `apply_step_intent` (new `UNIQUE` there); a trigger ties a command to a placement on the agent's own target. G-6 (i) adopted. Probes for both |
| R-14 | MINOR | **Accepted** | CP-side stickiness trigger (status never back to active, epochs never decrease); DP epochs `> 0` |
| R-15 | MINOR | **Accepted** | `agent_event.request_id` is gateway-minted per item; a pre-identity refusal has no tenant and is a metric. `AGW-D29` |
| R-16 | MINOR | **Accepted** | VDB deletes non-ACTIVE leases expired more than 30 days; `AGW-D31` |
| R-17 | MINOR | **Accepted** | Agent-id routes use `x-sm-scope: named` (D-C4 refuted); capabilities fixed server-side (column default `apply-config, lifecycle`); `revoked_at` column added; `draining` dropped from the session states |
| R-18 | MINOR | **Accepted** | `AGW-D23` and `BE-D70` now *record* the owner's rulings (`OR-126` A2, `OR-128` (5)) instead of claiming to amend |
| R-19 | MINOR | **Accepted** | "Stored by SM nowhere"; the agent's on-host journal copy is recorded in `AG-D29` and `AGW-D26` |
| R-20 | MINOR | **Accepted** | `AG-D26` item 5: the config path is the label's on the agent path |
| R-21 | MINOR | **Accepted** | `BE-D69` says "in the launcher"; `target_id` is bound to the organization (composite FK + `UNIQUE (organization_id, target_id)` on `target`); consumption is a biconditional CHECK; pending token holds its target name (partial unique index), released by revoking it |
| R-22 | MINOR | **Accepted, plan text to follow** | The plan's T1.1 text `search_path = pg_catalog, sm_data` leaves `pg_temp` to be searched first; the draft's `sm_data, pg_temp` (as `sm_metrics` V001) is right. PostgreSQL 14, `CREATE FUNCTION`, "Writing SECURITY DEFINER Functions Safely" (fetched 2026-10-09): *"Particularly important in this regard is the temporary-table schema, which is searched first by default, and is normally writable by anyone. A secure arrangement can be obtained by forcing the temporary schema to be searched last. To do this, write `pg_temp` as the last entry in `search_path`."* The plan's section 10 item 14 "(b) if not given" is now moot: `OR-129` ruled (a) |
| R-23 | MINOR (UNVERIFIED in code) | **Accepted as a test** | A contract-kit case that `pull`/`ack`/`received` bind `(tenant, agent)` to the command. `AGW-D26` enforced-by |

**Author findings confirmed true by the reviewer:** G-1, G-2, G-3, G-5, F-1, F-2, F-3, F-6, D-C2, D-C3, D-C5, D-1, D-2, D-3 (as
handled). **F-4** (the `AGW-D15` ledger is superseded in effect) and **F-5** (a cross-tenant `DELETE` by constants rides on "the
`OR-77` pattern") are true and each **needed an owner ruling by name** (since given: `OR-130` F-4, F-5, F-6), not the reviewer's or the implementer's. G-4/D-C1 was true but
half fixed (R-3, R-4). D-C4 was refuted (R-17).

**Left unresolved, on purpose (as of T0.3; all since ruled 2026-10-08/09):** `AG-D29` (`OR-129`, on-disk spool); D-4 (`OR-130`, the agent runs the declared probe); F-4, F-5, F-6 (`OR-130`); the finalization of the DDL (bound 2026-10-09 on `2568862f`: V028/V029/V021, `schema-015-draft.sql`). **Still open for the owner:** F-5's scope over `agent_lease`/`agent_session`, and F-6's window on sample age or on measured clock offset.

**Not done by the reviewer, so not claimed:** `buf`/`protoc-gen-*` builds (not installed, T0.4); the Java compile of any port (T2.1a);
`validate-openapi.py` against the fragment (its catalog is 43 until T1.3); the Postman generator.
