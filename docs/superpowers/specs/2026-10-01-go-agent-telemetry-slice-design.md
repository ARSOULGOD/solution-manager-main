# go-agent — telemetry role, thin end-to-end slice

**Component:** `go-agent` · **Locus:** `host-local`, Docker runtime · **Date:** 2026-10-01 · **Status:** DESIGN, approved in brainstorming; implementation plan pending
**Authority:** [`../../design/backend/agent-gateway/design.md`](../../design/backend/agent-gateway/design.md) (§4.1, §4.3, §4.10, §4.13, §5.5, §6.1–§6.3) — which overrules anything below it collides with — then the baseline agent family [`../../design/go-agent/design.md`](../../design/go-agent/design.md) (§2, §6, `AG-D9`), [`../../design/architecture.md`](../../design/architecture.md) §10, and the canonical `metric`/`source` shapes in [`../../../contracts/solution-package.schema.json`](../../../contracts/solution-package.schema.json).
**Builds on:** slice 1, [`2026-09-21-go-agent-control-path-design.md`](2026-09-21-go-agent-control-path-design.md), merged.

---

## 1. Scope

### 1.1 Builds — the thinnest telemetry path that is real at every step

Declare `http_json` metrics per binding in a local file → collect them from the binding's container on an interval → persist each tick as a batch in the `AG-D9` disk buffer → send batches on the `Telemetry` stream under gateway credits, labelled only with DP-minted ids → trim the buffer on `TelemetryAck`, record every drop as a gap marker → survive agent restart, session loss and credit starvation. `fake-gateway` serves the stream and can starve, resume and disconnect it from its console.

**Demonstrable end state:**

```
$ sm-agent … --roles control,telemetry --telemetry-config telemetry.yaml

fake-gateway terminal:
  telemetry binding-1 seq=1 cache.hit_rate=0.93 cache.miss_count=12 -> acked
> pause                       # credits stop; the agent buffers on disk
> resume                      # buffered batches drain, in order
> disconnect                  # session drops; agent reconnects; watermark trims
  gap binding-1 seq=3..6 DROPPED_OLDEST     # with a deliberately tiny buffer cap
```

### 1.2 Does not build — named so nothing is silently missing

| Out | Why / when |
|---|---|
| Source kinds `prometheus`, `solace_event`, `exec`, `jdbc_query` | later slices; `solace_event` is additionally blocked on open item 35 / `AGW-3` (the vendor Go API is cgo, `AG-D1` requires cgo-free) |
| `metricType: histogram` | one JSON number cannot be a histogram; arrives with `prometheus` |
| `when:` predicates on a metric | they evaluate over config values; the agent has no evaluated config model yet |
| Delivery of declarations from the plane | undesigned on the wire — open item `AG-14` (§11) |
| Kubernetes discovery, DaemonSet topology, one collector per node | slice 3, with `AG-D6` |
| A telemetry-specific lease or credential | `AG-11` stays open; this slice rides the existing session |
| Segment-file buffer, compression | not needed at v1 rates (§4.1) |

### 1.3 Assumptions recorded — the four brainstorming choices

1. **Thin end-to-end**, not transport-first or collector-first: every piece exists at minimum depth.
2. **`http_json` first**, because the v1 target, Cache, exposes no `/actuator/prometheus` (`architecture.md` §5, the Cache telemetry example, verified there against `solace-cache-engine/pom.xml`); its metrics are JSON endpoints.
3. **Declarations come from a local file**, because nothing on the wire carries them (`go-agent/design.md` §0.2: `SM-D19` unratified, "§6.1 has no input format"). The file mirrors the schema's `metric` shape so moving to a plane-delivered source changes where entries come from, not what they are.
4. **A separate pipeline** beside the control reactor, not telemetry-through-the-reactor (§2.1, `AG-D21`).

---

## 2. Shape

### 2.1 A separate pipeline beside the reactor (`AG-D21`)

```
              telemetry.yaml (local, per binding)
                     │
 ┌─────────── telemetry pipeline (own goroutines) ────────────┐
 │ collect/  one goroutine per binding, panic-isolated        │
 │   tick → Resolve (AG-D20) → ScrapeAddr → GET path(s)       │
 │   → JSON Pointer → sample{name,type,ts,value,ref,inc}      │
 │        │ Append: one batch per binding per tick            │
 │ buffer/   <data-dir>/telemetry/<binding>/<seq>.json        │
 │   bytes + age caps · drop-oldest → gap markers · trim      │
 │        │                                                   │
 │ send/     own loop + pure reducer: credits, in-flight,     │
 │   watermark; Telemetry stream on the session's connection  │
 └──────────────────────▲─────────────────────────────────────┘
                        │ View: read-only, published by the loop
 control reactor (slice 1, unchanged core):
   session phase + limits · lease bindings · DP-minted ids
```

The reactor gets **no telemetry events**. Three reasons, each sufficient:

- **Collector isolation is a hard requirement** (`go-agent/design.md` §2, `architecture.md` §10): "a bad collector fails alone, never the process". A collector that shares the reactor's inbox can delay a `Revoke`.
- **`AG-12`'s costs are per event.** `Snapshot` deep-copies every reducer's state on each event (`L23`), and the journal scans are linear. `AG-12` already says pruning is "owed before the telemetry slice raises the event rate by orders of magnitude". Keeping telemetry off the reactor leaves that rate where slice 1 measured it.
- **The wire already separates them**: `Telemetry` is its own HTTP/2 stream (gateway §4.1).

The sender still uses the reactor's idiom internally — events in, a pure reducer, effects out — so its credit and in-flight logic is table-testable exactly as slice 1's reducers are.

### 2.2 Package layout

```
internal/telemetry/declare/   the declarations file: parse + validate (§3)
internal/telemetry/collect/   http_json collector, JSON Pointer (§3.3–§3.5)
internal/telemetry/buffer/    AG-D9 disk buffer (§4)
internal/telemetry/send/      sender loop + pure reducer (§5)
internal/telemetry/           Pipeline: wiring, the View interface, metrics
internal/msg/                 + TelemetryBatch/Ack/Credit/Reject, Sample, GapMarker
internal/wire/                + Conn.OpenTelemetry on the existing ClientConn
internal/runtime/             + Adapter.ScrapeAddr; docker implementation
internal/agent/               + publishes View; fills SessionOpen.resume telemetry seqs
internal/fakegw/              + Telemetry RPC, credits, watermark, console verbs (§7)
cmd/sm-agent/                 + --roles, --telemetry-config, buffer caps
proto/…/v1draft/              + the four messages, transcribed (§6)
```

`internal/telemetry/...` imports `msg`, `store`, `runtime` and nothing from `agent`; `agent` imports `telemetry` only for the `View` type it publishes. `wire` stays the only production importer of `wire/pb`.

### 2.3 Roles and capabilities

| `--roles` | Capabilities in `SessionOpen` | Pipeline | Commands |
|---|---|---|---|
| `control` (default, unchanged) | `apply-config`, `lifecycle` | off | as slice 1 |
| `control,telemetry` | `apply-config`, `lifecycle` | on | as slice 1 |
| `telemetry` | `report-only` | on | refused locally `CAPABILITY_MISSING` by the existing offer gate |

The telemetry-only agent still runs the session, lease and actual-state reports, because a `TRUSTED` report is what mints the `replica_id`/`incarnation_id` its samples must carry (gateway §6.1, `AGW-D16`). Whether it needs a lease of its own stays `AG-11`.

### 2.4 Configuration (`AG-D7` — stdlib `flag`)

| Flag | Default | Meaning |
|---|---|---|
| `--roles` | `control` | comma list of `control`, `telemetry` |
| `--telemetry-config` | — | required when `telemetry` is a role; the file of §3 |
| `--telemetry-buffer-bytes` | `64MiB` | `AG-D9` byte cap, all streams together. **Provisional** (gateway §5.8: defaults are measured, not assumed) |
| `--telemetry-buffer-age` | `24h` | `AG-D9` time horizon. **Provisional** |

Sizing note behind the provisional defaults: three metrics at a 15 s interval is about 0.5 KB per batch, roughly 3 MB per binding per day, so 64 MiB holds a day for about twenty bindings.

---

## 3. Declarations and collection

### 3.1 The declarations file (`AG-D22`)

```yaml
schema_version: 1
bindings:
  binding-1:
    interval: 15s
    timeout: 5s                 # optional; default min(interval, 5s)
    metrics:
      - name: cache.hit_rate
        metricType: gauge
        unit: ratio
        source: {kind: http_json, port: 8081, path: /api/cache/stats, pointer: /hitRate, onUnavailable: degrade}
      - name: cache.miss_count
        metricType: counter
        unit: count
        source: {kind: http_json, port: 8081, path: /api/cache/stats, pointer: /missCount, onUnavailable: degrade}
```

Each `metrics[]` entry is a valid instance of the schema's `metric` object restricted to this slice: `name`, `metricType`, `unit` and `source` are required there, so they are required here. `unit` is not sent — the DP normalizes units from the declared contract at ingest (`architecture.md` §8) — but an entry without it would not survive the move to a plane-delivered source.

### 3.2 Validation — refuse to start (exit 2), naming binding and metric index

- `schema_version` is `1`.
- The binding id is a safe path component, `[A-Za-z0-9._-]+`, because it names a buffer directory (§4.1).
- `interval` ≥ 1 s; `timeout` ≤ `interval`.
- `metricType` ∈ {`counter`, `gauge`}; `histogram` is refused with "not in this slice".
- `unit` is non-empty.
- `source.kind` is `http_json`; any other schema kind is refused with "not in this slice".
- `port` is 1–65535; `path` starts with `/`; `pointer` is a valid JSON Pointer (RFC 6901: empty, or `/`-separated tokens with `~0`/`~1` escapes).
- `onUnavailable` ∈ {`degrade`, `error`}.
- `name` is unique within a binding. `when` is refused with "not in this slice".

### 3.3 Collecting — one goroutine per binding

Each tick:

1. If the binding is not in the current lease (`View.InLease`), skip, with one warning per lease change. Collectors follow lease changes at the next tick.
2. `Adapter.Resolve(binding)` — the same `AG-D20` label lookup `apply` uses.
3. `Adapter.ScrapeAddr(placement, port)` for each distinct port (§3.4).
4. One `GET` per distinct `(port, path)`, under the binding's `timeout`; status must be 200; the body is decoded with `json.Decoder.UseNumber`.
5. Each metric's pointer must resolve to a JSON number. Anything else — missing, string, bool, null — produces **no sample**: counted in `sm_agent_telemetry_unavailable_total{binding,metric}`, and for `onUnavailable: error` also logged at `WARN`. **Never a zero** (`architecture.md` §10, `UI-D8`).
6. The samples become one batch appended to the binding's stream (§4). A tick with no samples appends nothing.

A sample is `{name, type, ts, value, replica_ref, incarnation}`: `ts` is the scrape time from the agent's clock in UTC, and `replica_ref`/`incarnation` are the container name and id from `Resolve` — the same strings the actual-state report sends. They are resolved to DP-minted ids **at send time**, not at scrape time, so a container restarted between the two keeps the identity it had when the sample was taken.

### 3.4 Where to scrape — `Adapter.ScrapeAddr`

Docker implementation, in order:

1. If the container publishes `<port>/tcp`, use the published binding. A wildcard host IP (`0.0.0.0`, `::`, empty) becomes `127.0.0.1`.
2. Otherwise use the container's IP on its first network that has one. This is for an agent **attached to the container's network** — the Docker control `architecture.md` §10 prescribes for Cache: a dedicated bridge network, the collector attached to it, 8081 not published.
3. Otherwise fail: `port <port> is neither published nor reachable on a container network`.

**On Docker Desktop, publish the port.** Docker's documentation places the container network and its `docker0` bridge inside Desktop's VM and directs host access through published ports (docs.docker.com, "Networking on Docker Desktop", fetched 2026-10-01). It does not state outright that container IPs are unreachable from the host, so this design relies on the published-port path there rather than on that claim.

### 3.5 Isolation

Each tick runs under `recover()`. A panic is counted in `sm_agent_telemetry_collector_panics_total{binding}`, and the collector continues at the next tick. A hung endpoint is bounded by `timeout`. Fetch failures are counted in `sm_agent_telemetry_scrape_errors_total{binding,reason}` with reason `resolve`, `addr`, `http`, `status` or `decode`. The process never exits because of a collector.

**`I6`:** response bodies are never logged or stored. Only the numeric values at declared pointers leave the collector.

---

## 4. The disk buffer (`AG-D9`, `AG-D24`)

### 4.1 Layout

```
<data-dir>/telemetry/<binding>/state.json          {schema_version:1, acked_seq, gaps:[{from_seq,to_seq,reason}]}
<data-dir>/telemetry/<binding>/<seq %020d>.json    {schema_version:1, stream_id, seq, produced_at, samples:[…]}
```

- One file per batch, written with the existing `store.Save` (atomic write, parent-directory fsync). At v1 rates that is one fsync per binding per interval, and it reuses the agent's single write primitive rather than adding a second. Segment files are a later optimization, not a v1 need.
- `stream_id` is the binding id (`AG-D23`).
- **Next seq on load** = max(`acked_seq`, highest file seq) + 1. A crash between writing a batch file and anything else therefore never reuses a seq.
- An in-memory index (seq, bytes, `produced_at` per file) is built at load by reading the files. That is bounded by the byte cap.

### 4.2 Bounds and drops

- **Both caps are global.** On every append, and on a 60 s sweep, the buffer:
  - drops batches older than `--telemetry-buffer-age`, reason `EXPIRED`;
  - while the total exceeds `--telemetry-buffer-bytes`, drops the batch with the oldest `produced_at` across all streams, reason `DROPPED_OLDEST`.
- **An in-flight batch is never dropped.** Its fate is the gateway's, and a gap marker must not claim a batch the DP may already have committed. There is at most one per stream (§5.2).
- **A drop:**
  - deletes the file;
  - appends a gap to that stream's `state.json`, merging with an adjacent gap of the same reason;
  - increments `sm_agent_telemetry_dropped_batches_total{reason}` and `…_dropped_samples_total{reason}`.
- **Delivery.** Pending gaps ride on the next batch sent on their stream (`gap_markers[]`), and are removed from `state.json` once that batch is acked. A stream that never sends again keeps its gaps pending; that is the limitation recorded as `AG-19`.
- **Write failure is not fatal (`AG-D25`).** A `store.Save` error drops the batch, increments `sm_agent_telemetry_buffer_write_errors_total`, logs at `WARN`, and the agent keeps running. This deliberately differs from the control path, where a failed `Persist` halts the agent: configuration change fails closed, telemetry is best-effort with marked gaps (`B/D2`).

### 4.3 Trim

On `TelemetryAck{stream_id, high_watermark_seq}`:

1. Persist `state.json` with `acked_seq = hwm` and the delivered gaps removed.
2. Delete the batch files with seq ≤ hwm.

A crash between the two leaves files at or below `acked_seq`; load deletes them.

### 4.4 Gauges

- `sm_agent_telemetry_buffer_bytes`
- `sm_agent_telemetry_buffer_batches`
- `sm_agent_telemetry_buffer_oldest_age_seconds`
- `sm_agent_telemetry_buffer_limit_bytes`
- `sm_agent_telemetry_buffer_limit_age_seconds`

The two limits are exported because `AG-D9` requires both caps to be reported.

---

## 5. Sending

### 5.1 The `View` the reactor publishes

```go
type View interface {
    Session() (SessionInfo, bool)                      // the ACTIVE session: id, limits, stream opener
    InLease(binding string) bool
    IDs(binding, replicaRef, incarnation string) (replicaID, incarnationID string, ok bool)
    Changed() <-chan struct{}                           // closed and replaced on every publish
}
```

The loop publishes an immutable value through an `atomic.Pointer` after any event that changes the session phase, the lease's binding set or the reports registry. Readers never call `Snapshot()`, so telemetry adds no deep copies (`L23`).

`SessionOpen.resume.last_telemetry_seq_by_stream` — sent empty in slice 1 — is filled from the buffer's `acked_seq` per stream, through a function given to the loop at build time. It is a hint; the DP's watermark wins (gateway §4.13).

### 5.2 The sender reducer

| Item | Contents |
|---|---|
| **State** | `credits`, `inflight[stream]seq`, `retryAt[stream]`, `streamUp`, `maxBatchBytes` |
| **Events** | `SessionUp{limits}`, `SessionDown`, `StreamOpened`, `StreamBroken`, `Ack{stream, hwm, credits}`, `Credit{n}`, `Reject{stream, code, retryAfter}`, `BufferChanged`, `ViewChanged`, `RetryDue` |
| **Effects** | `OpenStream`, `Send{stream, seq}`, `Trim{stream, hwm}`, `DropAsGap{stream, seq, reason}`, `ArmRetry{at}` |

**Selection.** While `credits > 0` and the stream is up, send the batch with the oldest `produced_at` among the streams that meet all of these:

- the stream has a batch above max(`acked_seq`, in-flight);
- nothing is in flight on it;
- its ids resolve through `View.IDs`;
- it is past `retryAt`.

Each send takes one credit. **One batch in flight per stream (`AG-D23`)**, because `TelemetryReject` carries no `batch_seq` (gateway §4.3), so this is the only way a reject names its batch. That limitation is recorded as `AG-15`.

| On | Then |
|---|---|
| `SessionUp` | `OpenStream`; credits = `limits.initial_credits`; `maxBatchBytes` = `limits.max_batch_bytes` |
| `Ack{stream, hwm, n}` | `Trim`; clear the in-flight entry if ≤ hwm; credits += n |
| `Credit{n}` | credits += n |
| `Reject` `INTERNAL_UNAVAILABLE` | the batch goes back to unsent; `retryAt` = now + `retry_after` |
| `Reject` `PAYLOAD_TOO_LARGE`, `UNKNOWN_REPLICA`, `INCARNATION_RETIRED`, `UNDECLARED_LABEL`, `LABEL_INVALID`, `BINDING_NOT_IN_LEASE`, `TENANT_MISMATCH` | `DropAsGap` with the code as the reason — retrying the same bytes cannot succeed |
| `Reject` `OUT_OF_CREDITS` | credits = 0; the batch goes back to unsent; wait for `TelemetryCredit` (an agent/gateway disagreement, counted) |
| A batch encoding > `maxBatchBytes` | `DropAsGap` with reason `OVERSIZE`, before sending |
| `StreamBroken` while the session is `ACTIVE` | in-flight batches go back to unsent; credits = 0; `OpenStream` after full-jitter backoff (1 s base, 30 s cap, the existing `fullJitter`) |
| `SessionDown` | as `StreamBroken`, but nothing reopens until `SessionUp` |

Labels are written at send time: `binding_id`, `replica_id` and `incarnation_id` — **exactly these three** (gateway §6.1). `series_key` is the metric name.

### 5.3 Counters

- `sm_agent_telemetry_batches_sent_total`
- `…_batches_acked_total`
- `…_rejects_total{code}`
- `sm_agent_telemetry_credits` (gauge)

---

## 6. Wire

### 6.1 DRAFT `.proto` (`AG-D13` discipline)

The four messages are transcribed from gateway §4.3, field for field: `TelemetryBatch{stream_id, batch_seq, samples[], gap_markers[]{from_seq, to_seq, reason}, produced_at}`, `TelemetryAck{stream_id, high_watermark_seq, credits_granted}`, `TelemetryCredit{stream_id, credits_granted}` and `TelemetryReject{stream_id, code, retry_after}`. They go into the existing `TelemetryUp`/`TelemetryDown` as `oneof`s, each carrying the shared `Envelope`.

`Sample` is the one place the transcription takes from §6.1 rather than §4.3, because §4.3 defers to "the normalized model": `NormalizedSample{series_key, type, ts, value, labels map<string,string>}`, with `type` a closed enum `MetricType {UNSPECIFIED, COUNTER, GAUGE, HISTOGRAM}`. `gap_markers[].reason` is a string, because §4.3 defines no vocabulary; this slice's values are proposed as `AG-16`. Both points are flagged in the DRAFT header, as slice 1 flagged `AckDetailCode`.

### 6.2 Binding the stream to the session

The gateway identifies the agent by its mTLS certificate (gateway §4.5) and attaches the `Telemetry` stream to that agent's current accepted session. A stream opened with no accepted session is refused. No metadata is added.

### 6.3 `internal/wire`

`Conn.OpenTelemetry(ctx) (*TelemetryStream, error)` opens the RPC on the `Conn`'s existing `ClientConn` — same TLS connection, as gateway §4.1 requires. `TelemetryStream.Send(msg.TelemetryBatch)` and `Recv() (msg.TelemetryInbound, error)` go through `wire/codec`.

---

## 7. `fake-gateway`

- **Serves `Telemetry`.** It attaches the stream to the agent's live session; with none, it refuses with `UNAUTHENTICATED_IDENTITY`. On open it sends `TelemetryAck{hwm, credits_granted: 0}` for every stream it holds a watermark for (gateway §4.13 b).
- **Credits and limits.** The built-in scenario gains `limits.max_batch_bytes: 65536` and `initial_credits: 4`, marked as test values. Each batch is acked with `credits_granted: 1` unless paused.
- **Watermark** per `(agent, stream)`, held for the gateway's lifetime. A batch at or below it is acked without being recorded and printed as `duplicate` — the DP's replay short-circuit (gateway §6.2).
- **Console output:**
  - `telemetry <stream> seq=<n> <name>=<value> … -> acked`
  - `gap <stream> seq=<a>..<b> <reason>`
  - `telemetry <stream> seq=<n> duplicate`
- **Console verbs**, added to the `--interactive` console:

  | Verb | Effect |
  |---|---|
  | `pause` | acks carry `credits_granted: 0`; the agent spends what it holds and buffers |
  | `resume` | an unsolicited `TelemetryCredit{initial_credits}`, then 1 per ack again |
  | `disconnect` | closes the agent's `Connect` session. The gateway keeps running with its CA and enrolments, so the agent reconnects and the resume/trim path runs. This replaces the gateway restart that cannot work (the CA is in memory) |

- **Scenario policy** for tests: `telemetry: {initial_credits, credits_per_ack, reject_next: <CODE>}`.

---

## 8. Error handling

| Failure | Behaviour |
|---|---|
| Collector panic | recovered, counted; next tick runs (§3.5) |
| Fetch timeout, HTTP error, non-200, non-JSON | that endpoint's metrics produce no samples; counted by reason; no zero (§3.3) |
| Pointer not a number | no sample; `unavailable_total`; `WARN` if `onUnavailable: error` |
| Buffer write fails | batch dropped and counted; agent continues (`AG-D25`) |
| Declared binding not in the lease | not collected; one warning per lease change |
| Declarations invalid | exit 2 before connecting, naming binding and index (§3.2) |
| Ids not yet minted | the stream waits; the buffer caps still apply |
| Telemetry stream breaks, session up | reopen with full-jitter backoff (§5.2) |
| Session down | nothing sent; buffering continues |
| Reject codes | per §5.2's table |
| Revoke / halt | the pipeline stops with the agent; the buffer stays on disk |

---

## 9. Testing

**Unit**, table-driven with literal expectations:

- `declare`: every §3.2 rule, accept and refuse.
- JSON Pointer: RFC 6901 examples, including `~0`/`~1` escapes and array indexes; number vs string, bool, null and missing.
- `buffer`:
  - append/trim;
  - each cap producing the right gap ranges, with merging;
  - in-flight batches spared;
  - reload after restart;
  - a planted crash state (file written, state not) never reusing a seq;
  - files ≤ `acked_seq` removed on load;
  - a write failure counted and not fatal.
- `send` reducer:
  - credits;
  - one in flight per stream;
  - oldest-first across streams;
  - waiting on unresolved ids;
  - every reject row;
  - oversize;
  - stream broken, session down and up.
- `collect`:
  - against `httptest` servers: happy path;
  - non-200, timeout and garbage body;
  - a panicking fetch recovered;
  - two metrics on one path costing one `GET`.

**Integration** (`-tags integration`): `fake-gateway`, the real loop, the fake runtime adapter, and an `httptest` JSON target.

- `TestIntegrationTelemetryBatchesCarryMintedIDs` — labels are exactly the three ids from `ReportAck`.
- `…PauseBuffersResumeDrainsInOrder`.
- `…TinyBufferEmitsGapRange` — the gap's `from_seq..to_seq` matches the dropped batches.
- `…AgentRestartSendsUnacked` — the buffer survives; only the unacked batches are sent.
- `…DisconnectWatermarkTrims` — no duplicate is recorded after reconnect.
- `…TelemetryOnlyRoleRefusesCommands` — `report-only`; a typed `restart` is acked `REFUSED (CAPABILITY_MISSING)`; batches still flow.
- `…RejectDropsBatchAsGap` — `reject_next: UNKNOWN_REPLICA`.

**Race:** `go test -race` on `internal/telemetry/...` and `internal/fakegw`.

**Mutation checks**, as in the console work: removing the in-flight guard, the in-flight drop exemption, or the seq-reuse guard must each fail a named test.

**Docker** (`-tags docker`): a BusyBox `httpd` container serving `stats.json` on a published port, with the binding label. Scrape, edit the file, and see the next sample change. A manual run is then recorded in the runbook.

---

## 10. Decisions this slice proposes

All `PROPOSED`; the plan adds them to [`../../design/go-agent/decisions.md`](../../design/go-agent/decisions.md).

| ID | Decision |
|---|---|
| `AG-D21` | The telemetry role is a **separate pipeline** with its own loop and pure reducer; the control reactor publishes a read-only `View` and receives no telemetry events |
| `AG-D22` | **Interim local declarations file**, schema-shaped, until the plane delivers declarations (`AG-14`) |
| `AG-D23` | **One logical stream per binding** (`stream_id` = binding id), **one batch in flight per stream** |
| `AG-D24` | Buffer layout: one file per batch via `store.Save`; global byte and age caps; drop-oldest by `produced_at`; in-flight batches spared; gap reasons `DROPPED_OLDEST`, `EXPIRED`, `OVERSIZE` or the reject code |
| `AG-D25` | A telemetry buffer write failure is **counted, not fatal** — unlike control-path `Persist` |

---

## 11. Open items — routed, not decided here

New rows for [`../../design/go-agent/design.md`](../../design/go-agent/design.md) §13.2. Owners: the `AG-D*`/`AGW` owners; anything on the wire is the gateway design's (pinned precedence).

| # | Item |
|---|---|
| `AG-14` | **Who delivers metric declarations to the agent?** Nothing on the wire or in the lease carries them. `AG-D22`'s file is interim |
| `AG-15` | **`TelemetryReject` carries no `batch_seq`.** `AG-D23` serializes each stream to compensate; a `batch_seq` field would allow pipelining |
| `AG-16` | **The gap `reason` vocabulary is undefined** in §4.3. Proposed: `DROPPED_OLDEST`, `EXPIRED`, `OVERSIZE`, plus §4.11 reject codes |
| `AG-17` | **Credits: per session or per stream?** §4.10 grants `initial_credits` on the session while `TelemetryAck`/`TelemetryCredit` name a stream. This slice treats them as one session pool |
| `AG-18` | **A failed scrape is not marked on the wire.** Gap markers are seq ranges of dropped batches; a tick that produced no batch has no seq. The DP sees staleness, not a marked gap |
| `AG-19` | **A gap marker does not name its series**, so the DP cannot write `metrics_gap{series_id}` (§6.3) without inferring the series. Per-binding streams narrow it to one binding's series; a gap on a stream that never sends again stays pending |
| `AG-20` | `when:` predicates and `histogram` via `http_json` are unsupported in this slice (§1.2) |

`AG-12` (journal retention) is **not** made more urgent by this slice, because `AG-D21` keeps telemetry off the reactor. That is worth recording in `AG-12`'s row.

---

## 12. Runbook

`go-agent/RUNBOOK.md` gains a telemetry section covering:

- the stand-in target (a BusyBox `httpd` container with the binding label, a published port, and an editable `stats.json`);
- a sample `telemetry.yaml`;
- `--roles control,telemetry`;
- what the gateway console prints;
- the `pause` / `resume` / `disconnect` demonstrations;
- reading the buffer gauges on `/metrics`.

It is marked executed only after a real run.
