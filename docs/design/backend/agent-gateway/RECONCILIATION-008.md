# Reconciliation record: what plan 008 means for the Agent Gateway design

**Status: a record, not a decision.** Written 2026-10-08 by plan 008 S4 (wave 3). It **routes** each item to the owners of the
`AGW-D*` family and **resolves none of them**. `design.md` and `decisions.md` in this folder are deliberately **not edited**: the
family is PROPOSED and ratifies after `BE-D*` and `AG-D*` (`I3`, `I4`), so changing its meaning from the backend side would
decide a proposal against a proposal. The agent-gateway revisit that starts 2026-10-08 (`OR-115`) is the consumer of this file;
it must **extend this file** or cite it, and must not create a second reconciliation document for plan 008.

**What plan 008 built, in one paragraph.** The `dp-remote` path now has a placement registry (`sm_data.placement`, plan 010)
that the apply journal is keyed by. `sm_data` V021 made `apply_step_intent` placement-aware (`BE-D44`, S2); S3 added
`addPlacement`, `retirePlacement` and the live-placement instance-wide stop (`BE-D45`, `BE-D46`, `BE-D47`); S4's `sm_data` V024
made the database refuse an `apply_request` that names a placement the registry does not hold for the request's own instance
(`BE-D48`). **No code in this repository speaks to an agent: there is no `agent-gateway` module and no `.proto`.** Nothing below
is a claim that the gateway is compatible with any of this. It is a list of the places a designer of the gateway will find the
ground has moved or has not.

## 1. The journal identity the gateway writes against has changed (`BE-D23` → `BE-D44`)

`design.md` §3.5 and the `AGW-D*` text were written against `BE-D13`'s journal as `BE-D23` described it: `apply_step_intent`
unique on `(tenant_id, apply_id, step, replica_ref)`, **placement-blind**, with one placement per instance in v1.

**Now (V021, `BE-D44`):** `apply_step_intent` carries `placement_id`; uniqueness is `(tenant_id, apply_id, placement_id, step,
replica_ref)`. The idempotency key names the placement **only for an instance-wide request** (`applyId:placementId:step:replica`);
a placement-scoped request keeps `applyId:step:replica`. `BE-D23`'s prohibition on encoding a placement into `replica_ref` is
carried forward. An `agent_command` row that records the intent it answers must therefore carry, or be able to derive, the
placement the intent addresses; an agent that is told only `(apply_id, step, replica_ref)` is told an identity that is no longer
the journal's. **Routed to:** the `AGW-D*` owners (the `agent_command` shape, design §5 and §3.5's `prepare`/`materializeConfig`
rows).

## 2. `sm_data.placement` is the registry for both execution paths; `agent_replica`'s relation to it is open (OQ-8.6 (b))

`sm_data.placement` is the Data Plane's placement registry. For `dp-remote` it is plan 010's, and `apply_request.placement_id`
now has a database-level foreign key to it (V024: `(tenant_id, instance_id, placement_ref)` → `placement (tenant_id, instance_id,
placement_id)`, `MATCH SIMPLE`, so the instance-wide sentinel is exempt). The foreign key is **existence, not liveness**: a
retired placement stays referenceable and is refused at pre-flight by the catalog.

**Whether `AGW-D16`'s `agent_replica` (and its stable and ephemeral registry levels) is a child of `sm_data.placement` is the
agent-gateway plan's question.** Owner ruling `OR-121` (OQ-8.6 (b)) chose to record this and not decide it; nothing plan 008
built forecloses either answer. Facts a decision needs:

- `AGW-D9` already says a `DeclaredInstance` telemetry batch carries "the inventory's `placement_id`", i.e. the telemetry side
  already treats `placement_id` as the inventory's key.
- `AGW-D16` mints `replica_ref` identity from agent reports ("DP-minted", grammar-bounded, capped), where `sm_data` today has
  `replica_ref` as an adapter-supplied string inside a placement.
- A request for an agent-path placement would, under V024, require a `sm_data.placement` row for the same instance before any
  `apply_request` can name it. Whether an agent-path placement is a `placement` row, a registry row, or both is undecided.

**Routed to:** the `AGW-D16` owners, with `AGW-D16`'s own gates (design §13 AGW-17, AGW-19, AGW-20) unchanged.

## 3. `BE-D22`: §3.5's table maps one operation; the seam has two (UNRECONCILED, unchanged by plan 008)

`design.md` §3.5 still lists `rollbackOrMarkIndeterminate(placement, G)` as one `RuntimeAdapter` operation. `BE-D22` replaced it with
two (`assessRollback` and `restore`), matching the journal's two steps (`ROLLBACK_ASSESS`, hashless, and `ROLLBACK`, hash-bearing).
The §3.5 row's content survives as a description of those two steps, but the table does not name the seam as it is. Plan 008
neither introduced nor closed this. **Routed to:** the `AGW-D*` owners (a documentation correction in their family).

## 4. `BE-D24`: a config location names a writer access mode (UNRECONCILED, unchanged by plan 008)

`BE-D24` replaced the seam's local-`Path` directory with a closed writer-access value, `LocalFilesystem(Path)` or
`RemoteSftp(canonicalDirectory)`. §3.5's `materializeConfig` row does not name a writer mode, and an agent writes on the host
itself, which is a third mode `BE-D24`'s closed value does not contain. Whether the agent path adds a variant to that value or
returns a different shape is open. Plan 008 does not touch `ConfigLocation`. **Routed to:** the `AGW-D*` owners and `BE-D24`'s
owner together.

## 5. `AGW-20`: `status(replica)` has no actual-state projection (STILL ABSENT)

§3.5's `status(replica)` row reads "`dp-core`'s canonical per-replica actual-state projection defined by §13 `AGW-20`" and states
that, until `AGW-20` closes, "this row defines required behaviour but no persistence artifact". **Plan 008 does not supply that
projection.** The `ActualStatePort` contract stays PROVISIONAL, and every `AGW-D16` clause that consumes report ordering,
watermark and liveness stays gated on it.

## 6. Two consequences of the placement-aware operations the gateway design does not mention

- **A live-placement instance-wide stop (`BE-D46`).** An instance-wide `stop` now addresses exactly the instance's **live**
  placements from the registry (`PlacementRows.live`), not the placements its history names. On the agent path that means one
  `agent_command{operation = stop}` per live placement, and a placement that was retired is not addressed. The gateway's
  `stop` mapping in §3.5 should be read against that.
- **`addPlacement` and `retirePlacement` (`BE-D47`, S3).** A placement now appears and disappears on a running instance. The
  design's lease binding, which names one target, is not told what happens to a lease bound to a placement that is retired.
  Plan 008 retires only `dp-remote` placements and says nothing about leases.

## 7. What plan 008 does *not* claim

- No ArchUnit rule in this repository proves the gateway respects `dp-core`'s boundary: there is no gateway bytecode to scan.
  `agwUsesDpApiOnly`, `agwHoldsNoDatasource` and `onlyAgwTouchesGrpc` stay obligations on the gateway's own plan (`L19`).
  Plan 008 fences the *history* enumerator (no `apply.persistence` method returns a collection of `PlacementId`s) and keeps
  persistence types out of `dp-api`; both hold today and say nothing about a module that does not exist.
- No `.proto`, no agent protocol message and no `agent_*` table was added or changed.
- The foreign key in §2 is a statement about `dp-remote` requests. It does not say an agent-path request would satisfy it.

## Sources

[`../../../plans/backend/008-placement-registry-and-apply-journal-identity.md`](../../../plans/backend/008-placement-registry-and-apply-journal-identity.md)
(OQ-8.5, OQ-8.6; slices S2, S3, S4) · [`../decisions.md`](../decisions.md) `BE-D22`, `BE-D23`, `BE-D24`, `BE-D44`, `BE-D45`, `BE-D46`, `BE-D47`, `BE-D48` ·
[`design.md`](design.md) §3.5 and §13 · [`decisions.md`](decisions.md) `AGW-D9`, `AGW-D16` ·
`sm_data` V021 and V024 (`backend/dp-core/src/main/resources/db/migration/sm_data/`).
