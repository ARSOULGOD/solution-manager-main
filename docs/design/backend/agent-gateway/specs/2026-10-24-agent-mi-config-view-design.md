# Agent MI config view — design

Status: DRAFT for owner review (2026-10-24). Branch `feature/agent-mi-config-view`, cut from `feature/agent-mi-logs`
(it reuses the gateway broker pattern and the DP routing shape from the logs feature,
[`2026-10-20-agent-mi-logs-design.md`](2026-10-20-agent-mi-logs-design.md)).

## 1. Purpose

An operator can see the live `application.yml` of a managed integration (MI) that runs on a `host-local` agent
placement. Only keys on an allow-list are shown; every other value is hidden **on the host**, before anything
crosses the wire.

Settled with the requester (2026-10-24):

| Question | Answer |
|---|---|
| Whose file | The MI's own `application.yml` (the one the agent's `configwriter` writes) |
| Purpose | Show it. Drift detection and import are later features |
| Secrets | Allow-list: show listed keys only. Default `server.port`, `management.server.port`, `management.endpoints.*`, `management.endpoint.*`, `logging.*`, `info.*` (revised in the final review from `management.*`; see §4) |
| Placements | Agent (`host-local`) only. SSH (`dp-remote`) is later, through the same port |
| Where it filters | In the go-agent. The DP re-checks and rejects a reply that carries anything off the list. The DP owns the default list and sends it with each request |
| Scope | Agent + wire + DP REST read. The console UI is out of scope |

Not in scope: editing, drift, import, SSH placements, a user-editable list, the console UI.

## 2. Invariants this must keep

- **I6.** No secret value, and no key name or value outside the allow-list, leaves the host, is logged, or is in an
  error. Replies carry fixed text only.
- **I5.** The organization comes from the session. The placement is re-resolved under it. Parameters are bound.
- **I1.** No MI change. The file is read as it is.
- **I7 / AGW-D2.** The gateway holds no datasource. The DP reaches it through a typed port in `dp-api`, with a
  contract kit a remote adapter must pass.
- **AGW-D11.** Proto changes append new field numbers only.
- **No new permission verb.** The existing `config.view` authorizes the read, at instance scope, through the one
  `authorizationService.require` path (verify the verb's scope row when writing the plan).

## 3. Wire

Append-only, in `contracts/agent/v1/agent_gateway.proto`:

- `GatewayToAgent.config_view_request = 17` — `request_id`, `placement_id`, `repeated string allow` (key paths; a
  trailing `.*` matches a whole subtree), `uint32 max_bytes`.
- `AgentToGateway.config_view_reply = 12` — `request_id`, a status, `repeated ConfigEntry entries`
  (`path`, `value`), `uint32 hidden_count`, `bool truncated`, and an `UnavailableReason`.

Why `Connect` and not a new RPC: the reply is small and bounded by `max_bytes`, so it needs no credits and no
stream of its own. Refusals are always a reply with a reason, as for logs. (Check against the `Connect` handler
when writing the plan: the existing `LogRequest` replies on the `Logs` RPC, so a request/reply on `Connect` is
new to the gateway broker.)

Reason vocabulary, closed: `PLACEMENT_NOT_IN_LEASE`, `FILE_MISSING`, `FILE_UNREADABLE`, `FILE_TOO_LARGE`,
`INVALID_YAML`, `NOT_PERMITTED` (path escapes its directory), `TOO_MANY_KEYS`. The DP maps anything else to
`UNKNOWN`.

## 4. Go agent

A handler in `go-agent/internal/agent` (beside `ops.go`) resolves the placement's `runtime.Placement.HostPath` the
same way the apply path does, then:

1. Refuses a symlink or a path outside its declared directory (the check `configwriter` already applies).
2. Refuses a file larger than the agent cap, before parsing.
3. Parses the YAML with a depth, node and alias limit (no billion-laughs expansion), flattens it to
   dotted key paths (`a.b[0].c`), and keeps only paths matching the request's allow-list.
4. Returns the kept `(path, value)` pairs and the count of hidden keys. A value of a non-scalar type is returned
   only as its scalar leaves. Nothing outside the list is returned, not even its key name.
5. Never logs file content, paths or values.

The allow-list match is deliberately dumb: exact path, or prefix with `.*`. A secret under a listed prefix is the
operator's list to own. ⚠️ **Do not list `management.*`:** that subtree holds Spring Boot's Micrometer export credentials
(`management.datadog.metrics.export.api-key`, `management.newrelic.metrics.export.api-key`,
`management.dynatrace.metrics.export.api-token`, `management.appoptics.metrics.export.api-token`,
`management.humio.metrics.export.api-token`, `management.influx.metrics.export.password`,
`management.elastic.metrics.export.password` and `.api-key-credentials`, `management.kairos.metrics.export.password`,
`management.otlp.metrics.export.headers.*` and `management.opentelemetry.tracing.export.otlp.headers.*`, which carry
`Authorization`; verified against the Spring Boot application-properties appendix, 2026-10-24). The default therefore
lists only `management.server.port`, `management.endpoints.*` and `management.endpoint.*` under it. `logging.*` holds
levels and file settings. `info.*` is free-form: an MI can put anything there, so the owner is asked to confirm keeping it
(`DECISIONS.md` open item 51).

## 5. Gateway

`AgentConfigViewPort` in `dp-api`, implemented by a method on the broker in `agent-gateway`: admit by agent and
lease, send the request on `Connect`, wait for the matching reply under a deadline, release on every exit.
A placement that leaves the lease ends its pending view at once (the lesson from logs I-1). One in-flight view
per agent, so a slow host cannot stack requests. A contract kit covers the port.

## 6. Data plane

- **Service** (`dp-core`): resolves the placement under the organization, refuses a retired placement, checks the
  placement is an agent placement, builds the allow-list from `sm.dp.config-view.allow` (default above), calls the
  port.
- **Re-check:** every returned path must match the list it sent. Otherwise the whole reply is discarded and the
  caller gets a fixed `502`-class refusal. The DP never forwards the agent's raw reply.
- **Console route:** `GET .../placements/{id}/config` in `console.yaml` and `ENDPOINT-INVENTORY.md`, operation
  `viewConfig`, verb `config.view`. A non-agent placement gets `501`. A reply with a reason maps to a real status
  (404 file missing, 413 too large, 422 invalid YAML, 503 agent not connected).
- Response: `{entries:[{path,value}], hiddenCount, truncated}`. No file path, host or container information.

## 7. Errors and limits

Properties: `sm.dp.config-view.allow` (default in §1; the vendor-export subtrees under `management.*` named in §4 must not
be listed), `sm.dp.config-view.timeout` (default 10s), `sm.dp.config-view.max-bytes` (default 256 KiB, at most 1 MiB,
also the agent's request limit). Agent hard caps: file size (1 MiB), YAML depth, node count, keys returned. A reply cut
at the key cap sets `truncated`.

## 8. Testing (red first)

- Go: the filter on nested maps, lists, anchors and aliases, a secret under an unexpected key name (must be hidden),
  a symlinked file, an oversized file, invalid YAML, an alias bomb, and a log capture that finds no value.
- Gateway: reply routing, deadline, release on every exit, a placement leaving the lease, one in-flight per agent;
  the contract kit.
- DP: the re-check rejects a stray key (mutation-checked), tenant isolation, a retired placement, `config.view`
  required, no value in logs.
- Console: OpenAPI, endpoint inventory, `ProductionAuthenticationIT` operation set, Postman, `SchemaMirrorLockTest`.

## 9. Open items

1. Confirm `config.view` is granted at instance scope for this read (the plan checks the catalog row; no verb is
   added if it is).
2. The same pre-existing reds as the logs branch block the launcher integration tests (`sm_control` V028); this
   feature's launcher test edits are unverified until that is fixed.
3. Whether a read should write an audit row (the same question as logs open item 5).
4. SSH placements: a later adapter behind `AgentConfigViewPort`'s routing, not designed here.
