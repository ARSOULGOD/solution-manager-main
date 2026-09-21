# go-agent — control path against the agent-gateway design

**Component:** `go-agent` · **Locus:** `host-local`, Docker runtime · **Date:** 2026-09-21 · **Status:** DESIGN, approved in brainstorming; implementation plan pending
**Authority:** [`../../design/backend/agent-gateway/design.md`](../../design/backend/agent-gateway/design.md) (§3.5, §4, §11, §13), [`../../design/backend/agent-gateway/decisions.md`](../../design/backend/agent-gateway/decisions.md) (`AGW-D1`–`AGW-D16`), and the baseline agent family [`../../design/go-agent/design.md`](../../design/go-agent/design.md) / [`decisions.md`](../../design/go-agent/decisions.md) (`AG-D1`–`AG-D12`). Where anything older under `go-agent/docs/` collides with these, they win (precedence ruling 2026-09-21).

---

## 1. Scope

### 1.1 Builds — slice 1, the control path

`Enroll` → mTLS `Connect` → `SessionOpen`/`SessionAccept` with lease → `LeaseRenew` at 50 % and the `LEASE_RENEW_REQUIRED` re-entry → `Heartbeat`/`HeartbeatAck` with clock offset → `GenerationAvailable` / `CommandPull` / `CommandOffer` / `CommandReceived` / `ApplyProgress` / `CommandAck` through the `AG-D4` journal → `ActualStateReportBatch` / `ReportAck` → `Revoke`, `Drain`, `TrustBundleUpdate`, `Teardown`. Operations `preflight | apply | restart | stop` executed on a Docker host through a runtime adapter. Config written atomically, `0600`, symlink escape refused.

### 1.2 Does not build — named so nothing is silently missing

| Out | Why / when |
|---|---|
| `Telemetry` stream, credits, the `AG-D9` disk buffer | slice 2 |
| Kubernetes leader election (`AG-D6`), ConfigMap writer (`SM-D25`) | slice 3 |
| Version introspection beyond the image digest | `AG-D12`; the DP adjudicates. Reported honestly, never inferred |
| `solace_event` collector | open item 35 / `AGW-3` (cgo) |
| Report-only credential | `AGW-12` — does not exist plane-side either |
| Agent self-upgrade, HTTP/1.1 fallback, `scheduled` workloads | deferred by the register |

### 1.3 Assumptions recorded

- **The walking-skeleton gate is cleared.** The `dp-remote` Plan 004–005 apply engine is merged on this lineage (root `CLAUDE.md`); the user confirmed product agent code may be written.
- **No `.proto` exists.** Open item 6 / `AG-1` / `AGW-2` is still the architect group's; this design writes a DRAFT (`AG-D13`) as the `AG-D*` owners' input and swaps it for the reviewed one when it lands under `contracts/`. Field numbers and names may shift at review — the cost is bounded to `internal/wire` by construction (§3).
- **v1 is one agent, one target, one binding, one Docker host.** Concurrency decisions below are sized to that; every later scale step is named where it would land.

---

## 2. Shape

### 2.1 Package layout — `internal/` rewritten (`AG-D15`)

The old `internal/{config,docker,persist,telemetry}` packages, both demo subcommands (`start|stop|restart|status`, `collect`) and `testdata/java-demo` are removed. Nothing is kept for sentiment; what was right is re-derived.

```
cmd/sm-agent/            flags+env (AG-D7), wiring, run
cmd/fake-gateway/        scripted test-double binary (AG-D16)
proto/agentgateway/v1draft/agent_gateway.proto   DRAFT, transcribed from gateway §4.3/§4.11 — NOT contracts/
internal/wire/pb/        generated Go (go generate → buf); the only package that imports pb is internal/wire
internal/agent/          the reactor: Event, Effect, inbox, loop, executor, one runtime worker (AG-D14)
internal/authority/      lease reducer — SM-D28's states as the gateway consumes them
internal/commands/       command journal reducer — AG-D4 RECEIVED→STAGED→PERSISTED→REPORTED
internal/reports/        actual-state reducer — item_id minting, sequence, periodic cadence (AG-D17, AG-D18)
internal/wire/           gRPC adapter: mTLS dial, Enroll, Connect stream, pb↔Event/Effect translation
internal/identity/       key, CSR, cert chain, trust bundle store
internal/runtime/        Adapter interface; docker/ implementation; configwriter
internal/store/          typed durable records under $SM_AGENT_DATA_DIR; atomic write with parent-dir fsync
internal/clock/          server-clock offset → CLOCK_SUSPECT input
internal/fakegw/         fake gateway server, shared by cmd/fake-gateway and integration tests
```

### 2.2 Configuration (`AG-D7` — stdlib `flag` + env, no Viper)

| Flag | Env | Meaning |
|---|---|---|
| `--data-dir` | `SM_AGENT_DATA_DIR` | state root, default `/var/lib/sm-agent` |
| `--connect-endpoint` | — | bootstrap DP endpoint; replaced by `EnrollResponse.connect_endpoint` once enrolled and persisted |
| `--enrollment-token` | `SM_AGENT_ENROLL_TOKEN` | single-use; never persisted |
| `--trust-bundle` | — | install-time server trust bundle or pin |
| `--admin-addr` | — | loopback metrics/health listener, default `127.0.0.1:9464` |
| `--log-level` | — | slog level |

`agent_version` is injected with `-ldflags`. Roles: control only in this slice; the telemetry role flag arrives with slice 2.

---

## 3. The event-driven core (`internal/agent`, `AG-D14`)

```
 wire reader ──┐                          ┌─► Send(msg)         → wire writer
 timers ───────┤   inbox    ┌──────────┐  ├─► Persist(record)   → store (fsync'd)
 runtime worker┼──(chan)──► │ loop     │──┼─► RunOp(cmd)        → runtime worker (1 goroutine)
 conn events ──┤            │ reduce() │  ├─► ArmTimer(kind,at) → timer table
 signals ──────┘            └──────────┘  └─► Reconnect / Close
```

**Events** — a closed sum type, one constructor per source: `WireIn{msg}` (one per inbound §4.3 message), `TimerFired{kind}` (`RenewDue`, `HeartbeatDue`, `ReportDue`, `StepDeadline`, `ReconnectDue`), `OpFinished{commandID, result}`, `Connected`, `Disconnected{cause}`, `EnrollResult`, `Resumed` (synthetic, on boot), `Shutdown`. Every event carries `At time.Time` stamped by the loop. **Reducers never read a clock.**

**Reducers** — pure: `reduce(State, Event, Env) (State, []Effect)`. `State` is one composite `{session, authority, commands, reports}`. The top-level reducer dispatches in fixed order `session → authority → commands → reports`; each later reducer reads the earlier ones' *new* state read-only, so `commands` refuses an offer because `authority` just left `ACTIVE`. `Env` carries the only impurities, injected: `NewID()` (UUIDv7 in production, a counter in tests) and `Limits` (from `SessionAccept`). Minting ids inside the reducer into state is what makes "the same `LeaseRenew` reuses the same `message_id`" expressible (§5.3).

**Effects** — executed by the loop in a fixed order per event: **`Persist` → `Send` → `RunOp` → timers.** This is the agent-side write-before-act: the `RECEIVED` journal entry is fsync'd before `CommandReceived` leaves the process; the lease is stored before any command is admitted. A failed `Persist` is fatal — the loop emits `Shutdown{cause}` and attempts nothing else.

**Runtime worker** — one goroutine, one op in flight (gateway §4.4 rule 1: one outstanding `apply` per binding; v1 is one binding). `RunOp` enqueues; completion returns as `OpFinished`. A `StepDeadline` firing mid-op is just another event; the `commands` reducer decides the outcome (§6.5).

**Boot / resume** — load `session/lease.json`, `apply/**`, `reports/*` → build initial `State` → push `Resumed` → reducers emit what is owed: re-arm the renew timer from the stored `expires_at`, re-send an un-acked `CommandReceived`, resume an in-flight step from its journaled phase (§6.3), or go to `ENROLLING` when no identity exists.

**Inbox** — one bounded channel. The wire reader blocks when full, pushing back into gRPC/h2 flow control rather than dropping. Logging and metrics attach to the loop, one line per event and per effect, never inside reducers (§8.3).

**Why not the alternatives.** Goroutine-per-stream with shared state (rejected): lease and journal transitions become lock-guarded mutations across five goroutines — the rules "reports from `LEASE_EXPIRED` are untrusted" and "same `message_id` on renewal retry" become hard to prove. Full event sourcing (rejected): the design requires only the command journal and the lease grant to be durable (`AG-D4`, `AGW-D15`); journaling heartbeats and credits is weight without a requirement, and the repo already takes the "events are notices, rows are the record" position (`BE-D1`).

---

## 4. Wire layer, DRAFT `.proto`, identity, enrollment

### 4.1 The DRAFT `.proto` (`AG-D13`)

`proto/agentgateway/v1draft/agent_gateway.proto` is a 1:1 transcription of gateway §4.3 and §4.11 — same message names, same field names, nothing invented. Three RPCs: `Enroll` unary; `Connect` bidi with `oneof` envelopes `AgentToGateway` / `GatewayToAgent`; `Telemetry` declared, unimplemented in this slice. A shared `Envelope{message_id, sent_at}` on every message. Closed enums `ErrorCode` (§4.11), `Capability` (`apply-config`, `lifecycle`, `report-only`), `Operation`, `Outcome`, `ProgressStep`, `Trust`, `Disposition`, each with `_UNSPECIFIED = 0`. `CommandAck.detail.code` is its own enum, `AckDetailCode`: gateway §4.4 rule 5 names `ADMISSION_BLOCKED` there and §4.8 names `LEASE_NOT_ACTIVE` as a reason, neither of which is in §4.11 — so the detail vocabulary is `ADMISSION_BLOCKED`, `LEASE_NOT_ACTIVE`, plus every §4.11 code, and is flagged in the DRAFT header as the one place the transcription had to name an enum the design left implicit. The enrollment token travels as gRPC metadata `x-sm-enrollment-token`. §4.12's evolution rules apply from the first commit: fields only added, never renumbered or retyped; enums only appended. The file header states DRAFT, non-authoritative, superseded by `contracts/` — that swap is `AG-D13`'s exit condition.

### 4.2 `internal/wire`

The only package importing `pb`. Translates pb ↔ internal message structs both ways, so reducers never see generated types and a field rename at review is a one-file change. Two goroutines per connection: a reader (`pb → WireIn` → inbox) and a writer draining a `Send` channel, so the loop never blocks on the network. One gRPC connection over `crypto/tls`: client certificate from `identity`, server verified against the install-time trust bundle or pin. Conservative keepalive and max-message defaults at dial; `SessionAccept.limits` are enforced in reducers (`CommandOffer.payload_bytes ≤ max_message_bytes` is a refusal, not a transport setting).

### 4.3 `internal/identity`

ECDSA P-256 key generated once (`identity/key.pem`, `0600`). **CSR generated once and persisted (`identity/csr.der`) before the first `Enroll` call**, so a retry after a lost reply sends identical bytes and receives the stored `Enrolled` rather than `TOKEN_CONSUMED` (gateway §3.2 `EnrollmentPort`). Stores `cert.pem`, `trust-bundle.pem` (with `valid_from`/`valid_to`) and `agent_id`. Computes `cnf_thumbprint` (SHA-256 of the leaf DER) locally and compares it to `LeaseGrant.cnf_thumbprint` — defence in depth, not the check. Manual rotation for v1 (`B/D16`): the operator replaces files and restarts. `TrustBundleUpdate` is written atomically and used on the next dial.

### 4.4 Session lifecycle (the `session` reducer)

States: `IDLE → ENROLLING → CONNECTING → OPENING → ACTIVE → DRAINING → CLOSED`, plus terminal `HALTED{code}`. The agent-side mirror of gateway §4.2.

1. No identity → `Enroll` effect: a cert-less dial to `--connect-endpoint`, unary call, close → `EnrollResult`. Success → persist identity and `session/endpoint` → `Dial(connect_endpoint)`.
2. `Connected` → `Send(SessionOpen{protocol_version: 1, agent_version, capabilities_supported[], resume{last_acked_generation_by_binding[], last_telemetry_seq_by_stream[]: empty}, agent_clock})`.
3. `SessionAccept` → `ACTIVE`; store the lease and limits; arm renew, heartbeat and report timers; emit the full actual-state report (§7.1).
4. `Error(LEASE_RENEW_REQUIRED)` → `authority` emits the stored `LeaseRenew` (§5.3); `SessionAccept` follows or `SessionReject(RE_ENROLL_REQUIRED)` halts.
5. `Drain{reconnect_after}` → `DRAINING`: finish in-flight acks, close, reconnect after the given delay with jitter.
6. `Disconnected` from `ACTIVE`/`OPENING` → `CONNECTING` on the backoff schedule.

**Reconnect backoff (`AG-5`, agent-owned):** exponential 1 s → 5 min, full jitter, reset on `SessionAccept`; `retry_after` and `Drain.reconnect_after` override the schedule.

**`HALTED`** is entered on the identity-terminal codes (§8.4): no reconnect, the process stays up, the reason is on `/healthz` and the `sm_agent_halted` gauge, and it exits only on signal — so a Docker restart policy does not loop against an operator problem.

---

## 5. The `authority` reducer (lease)

Implements `SM-D28`'s machine as the gateway design consumes it (§4.6, §4.8, §4.9). The stored record is the entire `LeaseGrant` — every claim, `cnf_thumbprint`, `verb_ceilings`, `admission_margin`, and the `server_clock` it arrived with — persisted to `session/lease.json` **before** the reducer enters `ACTIVE`, on `SessionAccept` and on every later `LeaseGrant`.

### 5.1 Time

All lease arithmetic runs on **DP time**: `dpNow = event.At + clock.offset`, the offset coming from the latest `HeartbeatAck.clock_offset_ms` or `LeaseGrant.server_clock`. Timers are armed as durations from `dpNow`, never from the wall clock. The agent's clock is never an input to trust, only to when it acts (`B/D15`).

### 5.2 States

| State | Entered by | Agent may | Leaves by |
|---|---|---|---|
| `ACTIVE` | `SessionAccept.lease` / `LeaseGrant` | everything `capabilities[]` allow, on `binding_ids[]`, subject to admission | renew refused · `expires_at` · `Revoke` · clock suspect |
| `RENEW_DEGRADED` | `Error(LEASE_RENEW_REFUSED)` | finish the in-flight step; reconcile cached desired only; accept no new generation; report | `LeaseGrant` · `expires_at` |
| `LEASE_EXPIRED` | `dpNow ≥ expires_at` | report only; no restart, no config write, no new step | reconnect → `Error(LEASE_RENEW_REQUIRED)` → `LeaseRenew` → `LeaseGrant` |
| `CLOCK_SUSPECT` | `HeartbeatAck.clock_state = SUSPECT` or `\|offset\| > skew_tolerance` | as `LEASE_EXPIRED`, keep heartbeating | `HeartbeatAck.clock_state = OK` → recompute → `ACTIVE` if still valid |
| `REVOKED` | `Revoke`, or a stored-epoch mismatch on a grant | nothing; discard un-persisted desired; close | terminal → `session.HALTED(IDENTITY_REVOKED)` |
| `RE_ENROLL_REQUIRED` | `SessionReject(RE_ENROLL_REQUIRED)` | nothing | terminal → `session.HALTED` |

`ADMISSION_BLOCKED` is **not a state** (gateway §4.4 rule 5): it is the predicate `expires_at − dpNow ≥ verb_ceilings[op] + admission_margin`, evaluated by `commands` on offer and again before each step, surfacing as `CommandAck{REFUSED, detail.code: ADMISSION_BLOCKED}`. Each step's deadline is `min(step_start + verb_ceilings[op], expires_at)`. The values of `verb_ceilings` and `admission_margin` are the DP's (`AGW-8`); the agent only applies them.

### 5.3 Renewal

Timer at `issued_at + 50 %·(expires_at − issued_at)`, DP time. `LeaseRenew{nonce_consumed: grant.nonce, agent_clock}` with a `message_id` minted once into `authority.pendingRenew` and **reused on every resend** — across reconnects, `INTERNAL_UNAVAILABLE`, `IN_PROGRESS` — until a *terminal* reply arrives (`LeaseGrant`, or a refusal code). This is the plane's adoption/takeover contract (`AGW-D15`, gateway §8 rows 35/42): a fresh id would look like a second request against a nonce already spent. After a terminal refusal a later attempt (the reconnect path) gets a new id.

A `LeaseGrant` whose epochs are lower than the stored ones, or whose `cnf_thumbprint` ≠ our leaf, is ignored and logged as `GRANT_INVALID` — the DP already validates; this is the local mirror.

### 5.4 Expiry mid-apply

The runtime worker finishes the *atomic step* it is inside (a rename cannot be half-undone); no further step starts. `commands` records the outcome per baseline `AG-D5` and still sends `CommandAck` and reports on the same session — the DP labels them untrusted (gateway §4.8). The agent never self-labels.

### 5.5 Local mirrors of the DP's offer gate

Applied when an offer arrives: capability ∉ `capabilities[]` → `REFUSED(CAPABILITY_MISSING)`; `binding_id ∉ binding_ids[]` → `REFUSED(BINDING_NOT_IN_LEASE)`; state ≠ `ACTIVE` → `REFUSED(LEASE_NOT_ACTIVE)`. Defence in depth — the plane refuses these before any notice (gateway §4.6).

---

## 6. The `commands` reducer, journal and runtime

### 6.1 Flow per command (gateway §4.7)

1. `GenerationAvailable` → §5.5 checks + `generation > journal watermark[binding]` else `CommandAck{REFUSED, STALE_GENERATION}` → `Send(CommandPull)`.
2. `CommandOffer` → `len(payload) ≤ max_message_bytes` else `REFUSED(PAYLOAD_TOO_LARGE)`; `sha256(payload) == config_sha256` else `REFUSED(KEY_HASH_MISMATCH)`. **Idempotency:** journal already holds `(generation, idempotency_key)` with the same hash → redelivery: re-emit from the journaled step (`CommandReceived` if not terminal; the stored `CommandAck` if `REPORTED` — the plane answers `DUPLICATE`); same key, different hash → `REFUSED(KEY_HASH_MISMATCH)`, journaled as a protocol error. New → `Persist(journal RECEIVED + payload file)` → `Send(CommandReceived)` → `Send(ApplyProgress{RECEIVED})` → `RunOp`.
3. `OpFinished` → journal the terminal step → `ApplyProgress` per step passed → `Send(CommandAck{outcome, detail})` → journal `REPORTED`.
4. A pull or ack answered `SUPERSEDED` closes the record as `SUPERSEDED` without acting.

### 6.2 Journal (baseline `AG-D4` §5.2, adopted verbatim)

One fsync'd record per command at `apply/<binding>/<generation>.json`:

```
rollout_generation · idempotency_key · binding_id · target_path · config_hash
step ∈ RECEIVED | STAGED | PERSISTED | REPORTED · step_deadline · temp_path · outcome (terminal only)
```

Monotonicity: a generation below the highest durably recorded one for the binding is refused (`STALE_GENERATION`), never applied late.

### 6.3 Deterministic resume (on `Resumed`)

| Last step | Action |
|---|---|
| `RECEIVED` | discard; the DP redelivers. Nothing was written |
| `STAGED` | verify `temp_path` against `config_hash`: match → complete the rename; mismatch or missing → delete and re-pull |
| `PERSISTED` | re-read the file, verify the hash, re-report. **Never rewrite** |
| `REPORTED` | closed |

Temps belonging to a generation below the last persisted one for the binding are deleted at startup and after every successful apply; the deletion is journaled.

### 6.4 `internal/runtime.Adapter` and the Docker implementation

```go
type Adapter interface {
    Resolve(ctx, bindingID) (Placement, error)
    Preflight(ctx, Placement) (PreflightResult, error)
    WriteConfig(ctx, Placement, payload []byte, sha256 string) (WriteResult, error)
    Restart(ctx, Placement) error
    Stop(ctx, Placement) error
    Observe(ctx, Placement) (ReplicaFacts, error)
}
```

**Binding → container resolution (`AG-D20`).** Nothing in gateway §4.3 carries placement facts to the agent, and the baseline says the config path is *discovered by pre-flight* from the artifact's own launch spec (`SM-D21`). On Docker:

| Fact | Source |
|---|---|
| container for `binding_id` | label `com.solace.sm.binding-id=<id>` — deployment configuration, `I1` fair game |
| config path in the container | `Cmd`/`Args` `--spring.config.additional-location=` or env `SPRING_CONFIG_ADDITIONAL_LOCATION` |
| host path the agent writes | that container path mapped through `Mounts`; a **bind mount is required** — the agent writes on the host |
| `replica_ref` / `incarnation` | container name (RFC 1123-valid) / container ID (hex) |
| `restart_observed_at` | `State.StartedAt`, converted to DP time |
| `version_observation` | `{mechanism: "image-digest", value: RepoDigest, confidence: "low"}`, or `{mechanism: "none", confidence: "none"}` |

Preflight refuses (`PREFLIGHT_REFUSED`) when any of these cannot be established, and returns `preflight_result{container_id, image_digest, config_path_in_container, host_path, mechanism, competing_sources[], writable}`. This convention is flagged to the `AGW` owners as an ask: the plane carries no placement facts to the agent today.

**Outcome mapping per operation**

| Op | Adapter call | Journal | Outcome |
|---|---|---|---|
| `preflight` | `Resolve` → `Preflight` | `RECEIVED → REPORTED` | `PREFLIGHT_OK` / `PREFLIGHT_REFUSED` |
| `apply` | `WriteConfig` — **never restarts** (`AG-D3`) | full ladder | `APPLIED` (read-back hash verified) · `FAILED_NO_EFFECT` (before rename) · `FAILED_ROLLED_BACK` (after rename, `previous` restored) |
| `restart` / `stop` | `Restart` / `Stop` via the Engine API, then `Observe` | `RECEIVED → REPORTED` | `APPLIED` iff the post-state is observed — `restart`: running with a `StartedAt` newer than before; `stop`: not running · `FAILED_NO_EFFECT` iff the call failed **and** `Observe` shows the pre-state unchanged |

**Gap routed (`AG-D19`):** `AG-D5`'s vocabulary has no outcome for a lifecycle verb that changed state and still failed (stopped, did not come back). This design reports the truth through `Observe` → the next `ActualStateReport` — which is what convergence reads (gateway §4.8) — and records the missing outcome as an ask on the `AG-D*`/`AGW` owners rather than inventing an enum value.

### 6.5 Config write (`configwriter`)

Keeps the rule the gateway design credited as a genuine addition. Canonicalise the declared directory; **refuse if the resolved target escapes it via symlink**; copy the current file to `apply/<binding>/<gen>/previous`; temp in the same directory → `fsync` → `chmod 0600` (chown to the declared service account when running as root) → `rename` → **`fsync` parent directory** → read back and verify the hash. Journal `STAGED` before the temp write, `PERSISTED` after the parent fsync. A crash between rename and `PERSISTED` is resolvable by reading the file — which is why `APPLY_INDETERMINATE` stays narrow.

### 6.6 `APPLY_INDETERMINATE`

Reported only in baseline `AG-D5`'s single case — a step in flight when the lease left `ACTIVE`, and the only report available is untrusted — and resolved by journal replay on the next `ACTIVE` session, never by an operator.

---

## 7. The `reports` reducer, heartbeat and clock

### 7.1 Triggers

1. Every terminal command outcome.
2. A restart observed via `Observe` (container `StartedAt` newer than last seen) — polled on the report timer and after every op; a Docker-events subscription is a later optimisation.
3. **Periodically at `SessionAccept.limits.report_interval`, even when nothing changed — `AG-D18`.** This answers `AGW-19`: `Heartbeat` carries no replica identity by design, so without a periodic report the registry would silence-retire a healthy incarnation.
4. Once after every `SessionAccept`: a full report of every binding's current state — also what returns the DP-minted ids slice 2's telemetry needs.

### 7.2 Items are immutable once minted — `AG-D17`

An `ActualStateReport` item is created with `item_id` = UUIDv7, `sequence`, `binding_id`, `replica_ref`, `incarnation`, `persisted_generation`, `restart_observed_at?`, `effective_hash?`, `version_observation`, and is **persisted to `reports/pending.json` before it is sent**. It is re-sent byte-identical — same `item_id`, same `sequence`, same content — across reconnects and restarts until a `ReportAck.results[]` entry names its `item_id`. A new observation of the same replica is a new item; a pending item is never edited.

The four `AGW-20` properties: **type** UUIDv7 string · **uniqueness within one agent** (global in practice) · **persistence across restarts** via the pending file · **non-reuse horizon** unbounded — ids are never reissued.

### 7.3 `sequence` — `AG-D17`

A per-`(binding_id, replica_ref)` `uint64`, incremented and **written to `reports/cursor.json` before the item is created** (write-before-act), so a restart never resets it — the "genuine counter reset" `AGW-20` names cannot occur by construction. If the cursor file is missing (data directory lost), the counter is **seeded from `dpNow.UnixNano()`**: any previous value is smaller, so monotonicity holds without knowing it. Ordering is meaningful only within this agent, per replica — the scope the DP's watermark uses.

### 7.4 Batching and acks

All pending items go in one `ActualStateReportBatch` per flush (several bindings and replicas per batch, gateway §3.1 rule 5); the batch `message_id` is fresh per send — transport correlation only. On `ReportAck`, matched by `in_reply_to_item_id` never by position: `replica_id`/`incarnation_id` are stored in `reports/registry.json` keyed `(binding_id, replica_ref, incarnation)`; `APPLIED`, `STALE` → item closed; `REFUSED(REPLICA_REF_INVALID | REPLICA_CAP_EXCEEDED | REPORTER_DISPLACED)` → item closed, logged `WARN`, counted, no ids stored; `trust` is recorded and otherwise ignored — **the agent never self-labels and never retries to change a label.**

### 7.5 Heartbeat and clock

`Heartbeat{agent_clock, buffer_depth_bytes: 0, drop_count: 0, journal_depth: open records, lease_remaining_at_send}` at `heartbeat_interval`. `HeartbeatAck` → `internal/clock` stores the latest `clock_offset_ms` and `clock_state` (latest value, no smoothing — the DP computed it) → `authority` reacts to `SUSPECT`/`OK` (§5.2). Three consecutive intervals without an ack → `Disconnected` → reconnect; gRPC keepalive catches the TCP-level case sooner. Heartbeat is never renewal (gateway §4.9).

---

## 8. Durable state, observability, error taxonomy

### 8.1 Store layout (`$SM_AGENT_DATA_DIR`, dir `0700`, files `0600`)

```
identity/   key.pem · csr.der · cert.pem · trust-bundle.pem · agent_id
session/    lease.json · endpoint
apply/<binding>/<gen>.json          journal record
apply/<binding>/<gen>/              payload · staged · previous
reports/    cursor.json · pending.json · registry.json
```

Every record is a typed Go struct with `schema_version`. One write primitive, `store.WriteAtomic`: temp in the same directory → `fsync` → `chmod` → `rename` → **`fsync` parent**. **Boot validation is strict:** an unreadable or unknown-version record under `identity/`, `session/` or `apply/` is fatal with the path in the log — no silent migration. Two records self-heal: `reports/cursor.json` (reseeded, §7.3) and `reports/pending.json` (dropped items are re-observed on the next report timer).

### 8.2 Secrets on disk (`I6`)

The DP renders `secretRef` values into the payload, so `payload`, `staged` and `previous` may hold secrets: `0600`, never logged, short-lived — `payload` and `staged` are deleted at `REPORTED`; `previous` survives only until the next successful apply for that binding supersedes it, then goes with the superseded-partials sweep. The enrollment token is never written anywhere.

### 8.3 Observability

A loopback-only listener (`--admin-addr`): `/metrics` (Prometheus, `AG-D11`) and `/healthz` (JSON: session and authority state, `expires_at`, last `HeartbeatAck`, open journal records, pending reports, `halted_reason`). Metrics attach to the loop: `sm_agent_events_total{kind}`, `sm_agent_effects_total{kind}`, `sm_agent_wire_messages_total{dir,type}`, `sm_agent_reconnects_total`, `sm_agent_lease_state`, `sm_agent_lease_remaining_seconds`, `sm_agent_clock_offset_ms`, `sm_agent_journal_open_records`, `sm_agent_reports_pending`, `sm_agent_commands_total{op,outcome}`, `sm_agent_halted{reason}`. Logging is `log/slog` JSON (`AG-D8`): every event and effect at `DEBUG`, transitions at `INFO`, refusals at `WARN`; payload bytes, tokens, nonces and keys pass through a redacting `LogValuer` and never appear.

### 8.4 Error taxonomy — gateway §4.11 sorted into four behaviours

| Class | Codes | Agent |
|---|---|---|
| retry, honour `retry_after` | `ADMISSION_LIMITED` · `INTERNAL_UNAVAILABLE` · `DRAINING` · `IN_PROGRESS` · `REQUEST_EXPIRED` | reconnect/resend on the backoff schedule, `retry_after` overriding |
| act | `LEASE_RENEW_REQUIRED` → send `LeaseRenew` · `SESSION_SUPERSEDED` → reconnect **now** · `LEASE_RENEW_REFUSED` → `RENEW_DEGRADED` · `CLOCK_SUSPECT` → fail closed | §4.4, §5 |
| per-command | `STALE_GENERATION` · `UNKNOWN_COMMAND` · `SUPERSEDED` · `KEY_HASH_MISMATCH` · `PAYLOAD_TOO_LARGE` · `CAPABILITY_MISSING` · `BINDING_NOT_IN_LEASE` · `TARGET_MISMATCH` | close that journal record with the outcome; session unaffected |
| halt | `IDENTITY_REVOKED` · `TOKEN_CONSUMED` · `RE_ENROLL_REQUIRED` · `ISSUE_NOT_ALLOWED` · `UPGRADE_REQUIRED` · `PROTOCOL_VERSION_UNSUPPORTED` · `UNAUTHENTICATED_IDENTITY` | `session.HALTED{code}` |

Transport-level gRPC errors → `Disconnected` → backoff. An **unknown code** (a gateway one minor version ahead, §4.12) is treated as *retry* and logged `WARN` — forward-compatible within `[N−1, N]` rather than halting on an unrecognised code.

---

## 9. Fake gateway (`internal/fakegw`, `cmd/fake-gateway`, `AG-D16`)

A wire-level double of the DRAFT `.proto`, **not a Data Plane**: no ports, no persistence, no evidence about the plane's correctness. At start it mints an in-memory CA (written out for the agent's trust bundle) and takes an enrollment-token list. `Enroll` validates the token, signs the CSR, replays the stored reply for the same `(token, CSR)`, answers `TOKEN_CONSUMED` otherwise. `Connect` runs a **scenario** — an ordered YAML script of `accept{lease, limits}`, `offer{command}`, `expect{type, field asserts}` (blocks until the agent sends it, with timeout), `renew: grant | refuse{code}`, `revoke`, `drain`, `error{code}`, `disconnect`, `sleep`, `report-ack{results}` — and records a transcript.

The same engine runs in-process from tests (`fakegw.Start(t, scenario)`) and as the binary (`fake-gateway --listen :8443 --scenario s.yaml --enroll-token … --ca-out ca.pem`). A `demo.yaml` scenario walks enroll → accept → preflight → apply → restart → report → periodic against any container carrying the `com.solace.sm.binding-id` label with a bind-mounted config directory; the README documents the one `docker run` line. No `testdata/` tree returns.

---

## 10. Testing

| Layer | Proves | How |
|---|---|---|
| Reducer table tests | every transition in §4–§7 and the effect order | `(state, event) → (state′, effects)` with fixed `At` and a counter `NewID`; golden effect lists |
| Crash/resume tests | the §6.3 table row by row; `item_id`/`sequence` survival; cursor reseed | fault-injecting executor kills after each `Persist`; restart against `t.TempDir()` |
| Unit: `configwriter`, `store`, `runtime/docker`, `wire`, `identity` | symlink refusal, parent fsync, rollback, `0600`; label/args/mount resolution against a fake `engineAPI`; pb↔internal round-trip for every message incl. unknown enum values; CSR persisted before first `Enroll` | `go test ./...` |
| Integration (`-tags integration`) | the wire works | in-process `fakegw` over real mTLS/h2 — scenarios listed below |
| Docker E2E (`-tags docker`) | preflight/apply/restart/stop on a real labelled container | needs the socket; CI-optional |

**Integration scenarios:** happy path · lost `Enroll` reply → same CSR replay · expired-lease reconnect → `LEASE_RENEW_REQUIRED` → `LeaseRenew` with the **same `message_id`** · renew refused → `RENEW_DEGRADED` → expiry → report-only · `Revoke` → `HALTED` · `SESSION_SUPERSEDED` → immediate reconnect · `Drain` honours `reconnect_after` · duplicate offer answered from the journal · `KEY_HASH_MISMATCH` · `SUPERSEDED` pull · clock `SUSPECT` fail-closed then `OK` · periodic report at `report_interval` · report retry after crash reuses `item_id`.

**Baseline §12 success criteria → tests:** enroll + mTLS (happy path) · lease renew/expire (renew scenarios) · crash mid-apply resumes or narrow `APPLY_INDETERMINATE` (crash/resume + expiry-mid-apply scenario) · file at declared path `0600` (configwriter + Docker E2E) · `restart-observed` reported (Docker E2E + reports table tests) · no secrets in logs (redaction test over a captured log with a known payload).

---

## 11. Decisions produced (`docs/design/go-agent/decisions.md`, appended)

| ID | Decision |
|---|---|
| `AG-D13` | The agent↔DP `.proto` is drafted under `go-agent/proto/agentgateway/v1draft/`, transcribed 1:1 from gateway §4.3/§4.11, labelled DRAFT; replaced by the reviewed `contracts/` file when it lands. Only `internal/wire` imports the generated code |
| `AG-D14` | The agent is an event-driven reactor: one inbox, pure reducers `session → authority → commands → reports`, effects executed `Persist → Send → RunOp → timers`, one runtime worker; reducers never read a clock or mint ids outside `Env` |
| `AG-D15` | `internal/` is rewritten clean; the lifecycle and collect demo subcommands and `testdata/java-demo` are retired |
| `AG-D16` | The DP test double is a scenario-driven fake gateway at the wire level, shared by tests and a binary; it is not evidence about the plane |
| `AG-D17` | `item_id` is a UUIDv7 minted once per report item and immutable across retries; `sequence` is a per-`(binding, replica)` counter persisted before use and reseeded from `dpNow.UnixNano()` if lost — the agent's answer to `AGW-20` |
| `AG-D18` | A full `ActualStateReport` is sent at `SessionAccept.limits.report_interval` even when unchanged — the agent's answer to `AGW-19` |
| `AG-D19` | Lifecycle-verb outcomes map to `APPLIED` (post-state observed) or `FAILED_NO_EFFECT` (call failed, pre-state observed); a changed-but-failed state has no outcome in `AG-D5` and is routed to the `AG-D*`/`AGW` owners, with the truth carried by the next report |
| `AG-D20` | On Docker the binding's container is found by label `com.solace.sm.binding-id`, the config path from the container's launch args/env, the host path through a required bind mount; `replica_ref` = container name, `incarnation` = container ID. Flagged to the `AGW` owners as the placement-facts gap |

---

## 12. Docs migration

- `git mv go-agent/docs docs/design/archive/go-agent-fork/`, with a README stating it was superseded 2026-09-21 by the gateway design and the baseline family.
- `docs/design/go-agent/decisions.md`: append `AG-D13`–`AG-D20`, changelog row, and rows in the conflicts table for the `AGW` asks answered here (`AGW-19`, `AGW-20`) and routed (`AG-D19`, `AG-D20`).
- `go-agent/README.md` rewritten for the new build/run/fake-gateway flow; `go-agent/CLAUDE.md` updated to the new layout.

---

## 13. Phasing for the implementation plan

| Phase | Delivers | Green when |
|---|---|---|
| P1 | DRAFT `.proto`, `wire`, `identity`, `store`, `agent` loop, `session` reducer, `fakegw` enroll/accept | agent reaches `ACTIVE` against the fake gateway; lost-`Enroll`-reply scenario |
| P2 | `authority`, `clock`, heartbeat, reconnect/backoff, `HALTED`, `/healthz` + `/metrics` | all renew/expiry/revoke/supersede/drain/clock scenarios |
| P3 | `commands`, journal, `runtime/docker`, `configwriter` | crash/resume table; duplicate-offer, hash-mismatch, superseded scenarios; Docker E2E |
| P4 | `reports`, registry, periodic cadence | report scenarios; `item_id`/`sequence` crash tests |
| P5 | docs migration, decisions, README, CLAUDE.md | links resolve; decision IDs complete |

Each phase ends green on its own tests before the next starts. The plan is written with `superpowers:writing-plans`; execution mode (same session vs. parallel) is the developer's choice at that point.

---

## 14. Open asks on other owners, restated

| Ask | To | Where it lands |
|---|---|---|
| Placement facts for a binding (container/unit/pod, declared path, verbs) are carried nowhere on the wire | `AGW` owners | `AG-D20` fixes a Docker convention for v1 |
| An outcome for a lifecycle verb that changed state and failed | `AG-D*` baseline / `AGW` owners | `AG-D19` interim mapping |
| `verb_ceilings` / `admission_margin` values | `AGW-8` | applied, not decided, here |
| The reviewed `.proto` | open item 6 / `AGW-2` | `AG-D13` swaps to it |
