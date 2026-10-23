# `host-local` agent — managed-integration application logs, stored on the host and served on demand

| | |
|---|---|
| **Component** | `go-agent` + `contracts/agent` + `agent-gateway` + `dp-core` |
| **Date** | 2026-10-20 |
| **Status** | **DRAFT — brainstormed with the project developer; not reviewed, not ratified.** Decision ids are not allocated here: `DECISIONS.md` is the one central allocator. |
| **Companion designs** | [`2026-10-05-dp-agent-production-integration-design.md`](2026-10-05-dp-agent-production-integration-design.md), [`../design.md`](../design.md), [`../../../go-agent/design.md`](../../../go-agent/design.md) |

## 1. Purpose and scope

A `dp-remote` placement already has its container logs readable by the DP over SSH (`LogServiceImpl`, `OR-43`). A
`host-local` placement has no equivalent: the agent is outbound-only, so the backend cannot reach in.

This design lets the go-agent **collect the managed integration's (MI's) application logs, keep them in a size-capped
store on the host, and send them to the DP only when the DP asks.**

**In scope:** the agent collector and ring store; the DP-set per-placement size cap; the wire additions for a paged
read and a bulk export; the DP implementation of the existing `LogService` for agent placements; a DP export endpoint.

**Out of scope:** console screens and the cap control in the UI; redaction of log lines; the agent's own `slog` output;
a record of the agent's actuator calls; any change to the MI (`I1`).

## 2. Decided in brainstorming

| # | Decision | Alternative rejected |
|---|---|---|
| 1 | Source is the MI's application logs (container stdout/stderr via the Docker Engine log API). | Agent-own logs; a record of actuator calls. |
| 2 | Retention is a **byte cap**, per placement, **set by the DP**; an agent-YAML default applies until the DP sets one. | Agent-local-only cap. |
| 3 | The DP can request a **paged read (A)** and a **bulk export (B)**; both are served from one store. | Only one of them. |
| 4 | Scope is agent + wire + DP. The console is a separate piece. | Agent and wire only; everything through the console. |
| 5 | Requests ride `Connect`; **all log data rides a new `Logs` RPC** with credits, so heartbeats and lease renewal never queue behind log data. | Everything on `Connect`; pages on `Connect` and export on a new stream. |

## 3. Agent (`go-agent/internal/logs/`)

- **Collector.** One goroutine per bound placement follows the container's stdout/stderr through the Docker Engine log
  API and resumes from the last stored timestamp after an agent restart, container restart or recreate. A recreated
  container has a new id, so the agent re-resolves it by name (`ResolveByName`). A window it could not read is recorded
  as a gap marker; it is never silently skipped.
- **Ring store.** `<data-dir>/logs/<placement>/` holds time-indexed segment files in the style of `telemetry/buffer`. A
  paged read seeks by timestamp. A placement is limited to its cap in bytes; when full, the oldest segment is dropped
  and a `DROPPED_OLDEST` gap marker is recorded. The store survives agent restart.
- **Cap.** The DP sends `LogRetention{placement_ref, max_bytes}`. The agent persists it and applies it at once, trimming
  the ring if the new cap is smaller. `logs.defaultMaxBytes` in the agent YAML applies until the DP sets a value. A cap
  of 0 turns collection off for that placement and deletes its stored logs.
- **Serving.** *Page (A):* up to `limit` lines `after`/`before` a timestamp, within `max_message_bytes`. *Export (B):*
  the ring oldest to newest in chunks, with credits, resumable by sequence number.
- **Not redacted.** Lines are the MI's own output, consistent with `LogService`.

## 4. Wire contract (`contracts/agent/v1/agent_gateway.proto`)

Additions use **new field numbers only** (`AGW-D11`).

- `CAPABILITY_LOGS` is advertised in `SessionOpen`. The DP sends no log message to an agent that lacks it.
- `GatewayToAgent` (on `Connect`): `LogRetention{placement_ref, max_bytes}`; `LogRequest{request_id, placement_ref,
  mode}` where `mode` is `PAGE{limit, after, before}` or `EXPORT{from_seq}`.
- New RPC `Logs(stream LogUp) returns (stream LogDown)`, opened after `SessionAccept`, as `Telemetry` is.
  - `LogUp`: `LogPage{request_id, lines[], gaps[], unavailable_reason}` and `LogChunk{request_id, seq, lines[],
    final}`.
  - `LogDown`: `LogCredit` and `LogCancel{request_id}`.
- A page must fit `Limits.max_message_bytes`; a chunk must fit `Limits.max_batch_bytes`.
- Gate: `contracts/run-checks.sh` passes on the amended proto; Go and Java stubs are regenerated.

### 4.1 Semantics the gateway must match

Fixed by the agent implementation (plan 016 Task 5); the gateway side (Task 7) and the export path (Task 10) must
match them.

- **`LogChunk.seq` is the resume point:** the `from_seq` that continues right after that chunk, not the sequence
  number of its first line. An interrupted export resumes with `LogExportQuery{from_seq: <last received chunk's seq>}`.
- **Deliver the `LogRequest` before any credit.** A `LogCredit` that reaches the agent before its `LogRequest` is
  dropped as an unknown id; `Connect` and `Logs` are separate streams, so their relative order at the agent is not
  guaranteed. The agent starts every export with `Limits.initial_credits` of credit (as `Telemetry` does), and the
  gateway counts those per export. Credit granted after a chunk of that request has arrived cannot race; with
  `initial_credits` 0 the first credit can, so the gateway should keep `initial_credits` above 0 for logs (a lost
  first credit stalls the export until the request deadline).
- **Accepting a session that advertised `CAPABILITY_LOGS` is accepting `CAPABILITY_LOGS`.** `SessionAccept` has no
  per-capability field; the agent opens `Logs` only after such a `SessionAccept`.
- **`placement_ref` is the binding id** — one of the lease's `binding_ids`.
- **A refusal is always a `LogPage` with an `UnavailableReason`**, for an export request too, because `LogChunk` has
  no reason field.
- **No final chunk on a broken export.** An export that is revoked (its placement left the lease), cancelled, or ends
  with the session or the `Logs` stream sends **no** final chunk; the gateway relies on its own deadline.
- **The cap counts on-disk encoded bytes.** `LogRetention.max_bytes` caps the encoded records on disk (JSON with
  base64 text), so only about 70% of the cap is log text.
- **Vocabulary.** Gap reasons emitted: `DROPPED_OLDEST`, `COLLECTION_PAUSED`, `CONTAINER_UNREADABLE`, `CAP_REDUCED`.
  `OVERSIZE` is reserved and never emitted (an oversize line is truncated and flagged `truncated = true`).
  `LogLine.stream` is `"stdout"` or `"stderr"`.
- **Outage behaviour.** See §7: requests are served from the store while Docker is unreachable; the gap shows up after
  collection resumes. `CONTAINER_MISSING` is reserved.
- **A repeated gap marker** across consecutive chunks is possible; the gateway must tolerate it.

### 4.2 Contract notes for the Java side

Verified against the Go agent (`go-agent/internal/logs/`, `go-agent/internal/agent/logs_role.go`,
`go-agent/internal/wire/codec/`).

1. Never send `LogRetention` or `LogRequest` (`GatewayToAgent` fields 15 and 16) to a session without
   `CAPABILITY_LOGS`: an older agent's `GatewayFromPB` rejects an unknown variant with an error ("unsupported gateway
   message"), it does not skip it.
2. Pages: `limit = 0` means no line limit (only the byte budget applies). `after` and `before` are exclusive, optional,
   and their presence is meaningful (unset is not epoch 0). A page returns the **newest** lines in the window, oldest
   first, with no "more" flag. To page backwards use `before` = the oldest returned timestamp; lines sharing that exact
   timestamp can be skipped.
3. Exports: `from_seq = 0`, or a value older than the ring, starts at the oldest retained line; sequence numbers start at
   1. An export is not a snapshot: it follows the live ring until it catches up, so the DP's total-size and deadline
   caps are the only bound. A read error mid-export ends with a final chunk carrying a `COLLECTION_PAUSED` gap; cap 0
   mid-export ends with a final chunk carrying `CAP_REDUCED`.
4. Message sizes: a chunk is sized to `max_batch_bytes` and a page to `max_message_bytes`; the gateway's maximum inbound
   message size must be at least the larger. A limit of 0, or over 1 GiB, makes the agent use 1 MiB.
5. The agent allows 4 requests in flight by default (`LogsConfig.MaxConcurrent`, `serve.DefaultMaxConcurrent`) and
   answers more with `BUSY`; keep the DP bound at or below that.
6. `initial_credits` comes from the session-wide `Limits` shared with `Telemetry`; the gateway counts it once per export
   and keeps it above 0.
7. Gateway threading: never block inside the `Logs` `onNext` (hand the message off), and keep Netty's auto-refill
   connection flow controller. (Operational advice; not a property the agent code enforces.)
8. `placement_ref` must match `^[A-Za-z0-9._-]+$`. Any other ref is silently ignored for retention (a warning in the
   agent log) and, for requests, refused with `PLACEMENT_NOT_IN_LEASE`, so binding ids must stay within that set.
9. A `LogRequest` whose `request_id` is already in flight gets **no reply** (ignored and counted); use unique ids.

## 5. Data plane

- **`AgentLogService`** implements `dp-api`'s `LogService` for agent placements, so callers do not change.
  - The placement is resolved by `(organization, instance, placement)` together (`I5`).
  - It sends `LogRequest` and waits for the matching `LogPage` with a deadline. A missing capability, no session or a
    timeout returns the declared degraded page with fixed text naming no host, path or container (`I6`).
  - Requests are held in memory and are **not** written to the command journal: a read is ephemeral.
  - Concurrency is bounded like `maxConcurrentReads`; a request over the limit gets a "busy" page, never a queue.
- **Routing.** The existing log service selects SSH for `dp-remote` and `AgentLogService` for `host-local`; neither
  implementation changes for the other.
- **Export.** A DP endpoint streams `LogChunk`s to the caller as a download, bounded by a maximum total size and a
  deadline.
- **Cap.** A per-placement cap (`log_max_bytes`) is stored in the DP side table `sm_data.placement_log_retention` (not a column
  on `placement`, which stays immutable per `OR-29`; a missing row means never set); changing it is an UPDATE of that row and
  sends `LogRetention`, and it is re-sent on agent reconnect.
- **Module rule (`AGW-D2`).** `agent-gateway` holds no datasource; requests and replies cross typed ports in `dp-api`,
  and every in-process port has a contract test a remote adapter must pass.

## 6. Authorization — open points for the owner

The permission catalog is closed at 38 verbs. This design **assumes** the paged read and export reuse `logs.read`, and
that setting the cap uses an existing verb (which one is to be confirmed against the catalog in the plan). If either
assumption fails, that is a **blocker to raise with the project owner**, not a reason to add a verb.

**Status of the cap write (plan 016 task 9).** The REST write of the per-placement cap is **not built**: no endpoint, no OpenAPI
change. Which existing catalog verb authorizes it is an **open question for the project owner** (`OR-1` authority); **no verb
has been added** and no decision id is allocated here. What exists is internal only: `PlacementLogRetention.setCap(organizationId,
placementId, maxBytes)` in `dp-core` (bounded by `sm.dp.logs.agent-max-cap-bytes`, default 1 GiB; `0` turns collection off;
stored first in `sm_data.placement_log_retention`, then pushed through `AgentLogPort.sendRetention`, and it reports whether the
push happened), and the stored caps are supplied to the gateway as its `AgentLogRetentionSource`, so a reconnecting agent is
sent them. Nothing calls `setCap` until the verb is named.

## 7. Errors

| Situation | Behaviour |
|---|---|
| Agent lacks `CAPABILITY_LOGS`, or no session | Degraded page; nothing is sent. |
| No reply by the deadline | Degraded page; `LogCancel` sent; pending request dropped. |
| Docker unreachable or container missing | Page and export requests are **served from the store** during the outage. The missing window appears as a `CONTAINER_UNREADABLE` gap only after collection resumes (written when the next line arrives or the collector exits). `UnavailableReason` `CONTAINER_MISSING` is **reserved** and not emitted in v1 (§9 item 4). |
| Disk full or write error | Collection pauses for that placement and a gap is recorded; the agent stays up. |
| Cap reduced below current size | Oldest segments trimmed at once; gap recorded. |
| Export interrupted | Resume from the last acknowledged `seq`, or fail the download cleanly; total bytes and time capped. |
| Restart or container recreate | Resume from the stored timestamp; missed window becomes a gap marker. |
| Duplicate or late reply | Matched by `request_id`; unknown ids ignored and counted (the counters are accessors on the serving and codec layers, not yet exported on `/metrics`). |
| Concurrent reads over the bound | "Busy" page; no queue. |

## 8. Testing

- **Agent unit:** ring store (cap, drop-oldest, gap markers, restart survival, timestamp seek); collector against a fake
  Docker engine (recreate, rotation, resume); cap changes.
- **Agent ↔ `fakegw`:** page and export end to end, including credits, cancel and resume.
- **Contract:** `./contracts/run-checks.sh` after the proto change; regenerated stubs compile.
- **DP:** `AgentLogService` against a fake agent session (timeout, missing capability, another organization's placement
  unresolvable); the existing `LogService` contract tests also run against the agent implementation.
- **Architecture:** ArchUnit rules and planted-violation proofs extended to the new ports.
- **Caveat:** there is no CI; these gates run only when someone runs them. The plan lists the exact commands.

## 9. Open questions

1. Which existing verb authorizes setting the cap (§6)?
2. Default values: `logs.defaultMaxBytes`, export size and time caps, request deadline.
3. Whether `LogRetention` belongs beside `TelemetryDeclarations` or in its own message family — to settle against
   `AGW-D11` when allocating decision ids.
4. `UnavailableReason` `CONTAINER_MISSING` is reserved. Follow-up: surface the collector's pending gap as an open-ended
   gap marker in replies while collection is down (option b of the outage-behaviour ruling), so a reader sees the
   outage while it is happening rather than after collection resumes.
