# Backend design — event-driven backbone, live console, plane modules

**Status: PROPOSED, UNBASELINED.** Decisions arising here are `BE-D1`…`BE-D9` in
[`decisions.md`](decisions.md) — all `PROPOSED`, resting on the unratified `SM-D*` base (`I4`). This
document designs the SM backend's shape: how the modules divide, how state changes propagate over the
customer's event mesh, how the browser participates through the Solace WebApp SDK, and what that changes
for the frontend and agent workstreams. Requirement: [`REQ-012`](../../requirements/REQ-012-event-driven-live-console.md).

What this document does **not** re-decide: the plane split and thin-CP posture (`SM-D1`,
[`../architecture.md`](../architecture.md)), storage (`D14` 🔒, `SM-D8`/`SM-D26`), the stack (`SM-D18` —
Java 17 / Spring Boot, greenfield), the agent (`D8` 🔒, `AG-D*`), the apply protocol (open item 1 —
**still open**, see §10), the RBAC model (`D10` 🔒) or its verb catalog
([`../D10-AMENDMENT-PROPOSAL.md`](../D10-AMENDMENT-PROPOSAL.md)).

## 1. Objectives

1. **Live, honest state.** A health or config-state transition reaches an open console in seconds — and
   when it can't, the console *says* it degraded rather than presenting stale data as live.
2. **Events never own truth.** PostgreSQL rows are the system of record; every event is a change notice a
   consumer can ignore, lose, or receive twice without becoming wrong (`BE-D1`).
3. **The mesh the customer already runs.** SM attaches to the site's existing event mesh in a dedicated
   Message VPN (`BE-D2`) — the mesh **push-mode** MIs' business/reporting telemetry already travels on
   (File Mesh command-center events; Cache is HTTP-polled and unaffected), which is also the ingest path
   (`BE-D9`).
4. **Browser on the mesh, after RBAC.** The SPA uses the Solace WebApp SDK (`solace-webapp-sdk`, the
   RapidPilot `webAppSDK` repo — browser-first TypeScript, unified SMF/MQTT over websocket, OAuth-capable),
   connecting only through the gateway's proxied websocket, only with a short-lived post-RBAC ticket
   (`BE-D5`/`BE-D7`).

## 2. Module architecture (`BE-D3`)

```
                    browser (SPA + WebApp SDK)
                       │ HTTPS REST        │ wss  /mesh
                       ▼                   ▼
                ┌─────────────────────────────────┐
                │        console-gateway          │  sessions · authn adapter (BE-D8)
                │  /api/cp/*  /api/dp/*           │  ticket minting + JWKS (BE-D5)
                │  /api/console/v1/* (composed)   │  reverse proxy → broker websocket
                └───────┬─────────────────┬───────┘
              service   │                 │   service
              interface ▼                 ▼   interface
                ┌───────────┐      ┌────────────────────┐
                │  cp-core  │      │      dp-core       │
                │ sm_control│      │      sm_data       │
                └─────┬─────┘      │  ┌──────────────┐  │
                      │            │  │ mesh-bridge  │  │  JCSMP: publish sm/v1/*,
                      │ publish    │  └──────┬───────┘  │  consume MI telemetry topics
                      ▼            └─────────┼──────────┘
                ┌─────────────────────────────▼─────────┐
                │   customer event mesh · SM's ded. VPN │
                └───────────────────────────────────────┘
```

- **`cp-core`** — catalog, schema registry, entitlement, RBAC store, package load. Owns `sm_control`.
  Publishes catalog notify events; otherwise REST.
- **`dp-core`** — configuration-of-record, apply/rollout state machine, telemetry ingest + rollups, the
  `DM-D1` query API implementation. Owns `sm_data`. Publishes state-change notify events and chart-tail
  streams. The **only consumer of raw MI telemetry** (`DM-D3` holds).
- **`console-gateway`** — the composition layer open item 21 demanded: serves the SPA, terminates REST,
  composes cross-plane reads at `/api/console/v1/*`, hosts the authn adapter, mints mesh tickets, proxies
  `/mesh`. Depends on both planes' **service interfaces**, never their data-access modules; owns no schema
  (`I7` holds).
- **`mesh-bridge`** — the JCSMP layer, inside `dp-core` initially, module-bounded so it can split. All mesh
  I/O for the backend goes through it; no other module holds a broker session.

On-prem: one deployable, module and schema boundaries enforced (ArchUnit when CI exists). SaaS split: the
**REST surface** is a boundary change — the gateway already talks to planes over interfaces and the browser
already sees one origin. **The browser↔mesh path does NOT survive the split as designed** (R5 finding 1): a
Solace-hosted gateway proxying websockets into a broker inside the customer's network is inbound reach —
the posture this architecture confines to `dp-remote`. SaaS live delivery needs a DP-side relay or an
outbound tunnel, designed when the split is; the seam is named now as open question 7 (§16), and nothing
in the v1 on-prem shape forecloses it.

## 3. Event model (`BE-D1`)

**Delivery.** Notify and internal events are **direct (non-persistent)** messages. Consumers must tolerate
loss (heal by reading), duplication (notifications are idempotent by content — invalidating twice is
harmless), and reordering — made tolerable by the envelope below, whose per-entity `stateVersion` lets a
consumer discard a notice older than state it already holds. Chart-tail points are also direct: a lost
point resurfaces as a server-declared
gap on the next backfill (`DM-D16`), never as client-invented continuity.

**Ordering of write and notice.** A module publishes an event only **after** its PostgreSQL transaction
commits. A crash between commit and publish loses only freshness (the fallback poll picks it up). No outbox
table in v1 — nothing in this design requires at-least-once — but the publish sites are confined to one
`EventPublisher` seam per module so an outbox is a bounded upgrade if a future consumer (e.g. alerting)
needs it.

**Common event envelope (R5.1 finding 1).** Every event — notify, internal, stream annotation — carries one
envelope in addition to its event-specific fields:

```
{ eventId, occurredAtUtc, producer, tenant, entity: { kind, ids… }, stateVersion }
```

`stateVersion` is a **monotonic per-entity version**: ⚠️ *[superseded by [`BE-D27`](decisions.md) / `OR-35`: the
next value of one `sm_data` sequence, stamped by trigger — **not** the config generation, which one generation's
several `instance.config-state` notices would share and the stale-discard rule would then drop]* ~~the config
generation where one exists (config-state, rollout), otherwise a per-entity sequence the owning module
increments with the row it commits.~~ **Ordering
and stale-discard use `stateVersion` only — never wall-clock** (`B/D15` 🔒 applied to events);
`occurredAtUtc` is display and diagnostics. A consumer may discard a notice whose `stateVersion` is not
newer than the state it already fetched; refetch remains the always-safe default. The envelope is part of
the AsyncAPI contract (§4), defined once, referenced by every event schema.

**Event catalog (v1).** Emitted by `dp-core` unless noted. The payload column lists **event-specific fields
only** — every event additionally carries the envelope above (so `instance.version` and `catalog.package`
get their ordering from `stateVersion`, not from fields repeated per event):

| Event | Trigger | Payload (identifiers only) |
|---|---|---|
| `instance.health` | rollup transition — the engine's `HealthRollup` keys exactly (`GREEN`/`RED`/`RESTART_EXPECTED`/`INACTIVE_REPLICA`/`NOT_APPLICABLE`), plus the four `NOTFOUND` sub-states as **distinct** keys (`unreachable`/`not_exposed`/`missing`/`bootstrap`), never collapsed | instanceId, **placementId** (identity scoping below), new state key |
| `instance.config-state` | any movement on the **five-state `SM-D22` ladder** — `desired` → `rendered` → `persisted` → `restart-observed` → `effective-verified` (the last emitted only where the MI makes it achievable; its permanent absence for Cache is a stated state, not a missing event) | instanceId, reached state (`stateVersion` ~~= the config generation~~ — superseded, see [`BE-D27`](decisions.md)) |
| `instance.version` | version-state change (OK/DRIFT/UNKNOWN/UNVERIFIED) | instanceId, state key |
| `instance.rollout` | per-replica progress during an apply | instanceId, applyId, replicaId, step |
| `catalog.package` (`cp-core`) | package loaded / entitlement changed | solution, packageVersion |
| `metrics.quarantine` | undeclared series arrived (`UI-D5`) | instanceId, count |

State keys reuse the transcribed state vocabulary — the closed vocabularies stay canonical in their existing
homes; the AsyncAPI document references them by **exact enum name**, never redefines them (`UI-D14`). Two
health key-spaces exist in the vocabulary
([`STATE-VOCABULARY.md`](../frontend/reference/STATE-VOCABULARY.md): the lowercase `HEALTH` keys and the
SCREAMING rollup keys of `GOV_HEALTH`/`REPLICA_HEALTH`/the engine's `HealthRollup`): **events use the rollup
key-space**, matching `contracts/view-models` `types.ts`, and the AsyncAPI schema names that choice so the
two spaces are mapped once, not per consumer.

**Identity scoping (`SM-D23` — an Instance may be bound to several Targets).** Events whose underlying
state is per-placement are **placement-scoped**: `instance.health` carries `placementId`, and
`instance.rollout` already carries `applyId` + `replicaId`. A dual-bound instance's two sites therefore
never overwrite each other in a live console (R5 finding 3). `instance.config-state`, `instance.version`
and `catalog.package` are **deliberately instance-scoped invalidations** — coarse on purpose: the REST
refetch they trigger returns per-binding detail, and a finer key would only multiply invalidations of the
same queries. Chart streams carry full series identity (placement and source included) as `SM-D26` defines
it.

## 4. Topic taxonomy and its contract (`BE-D4`)

See the taxonomy block in [`decisions.md`](decisions.md) `BE-D4`. Properties that are load-bearing:

- **`v1` in the topic** from the first commit; a breaking payload or taxonomy change is `sm/v2/…` with a
  compatibility window, mirroring `DM-D2`'s posture for the query API.
- **Tenant is a topic level**, so broker ACLs can scope subscriptions by prefix — the mesh-side half of
  `I5`. The authoritative tenant is still the server-derived claim (`BE-D5`); the topic level exists so the
  ACL can enforce it without parsing payloads.
- **Notify payloads carry no values.** A fully leaked notify subscription reveals that instances exist and
  change — never config content, secrets, or telemetry values (`I6` by construction).
- The taxonomy + payload schemas are an **AsyncAPI document in `contracts/`**, with fixtures, negative
  cases, and a check in `contracts/run-checks.sh`, because both a Java producer and a TypeScript consumer
  parse these — the two-implementation drift `UI-D15`/`DM-D20` guard against elsewhere.

## 5. Browser connection lifecycle (`BE-D5`, `BE-D7`, `BE-D8`)

1. **Login** at the gateway — local DB user or OIDC SSO, one adapter, one session (`BE-D8`). The SDK has
   nothing to connect with before this point.
2. **Ticket:** SPA calls `POST /api/console/v1/mesh/ticket`. The gateway runs the same deny-by-default
   check as any request (`D10`), then mints a short-lived JWT (~15 min, renewable): issuer = gateway,
   validated by the broker's OAuth profile against the gateway's JWKS (configured once at install —
   verified against Solace docs, see `BE-D5`). Claims: server-derived tenant (`I5`), notify-scope.
   ⚠️ **Authentication is verified; subscribe *authorization* from the token is NOT settled** (R5
   finding 2) — the mechanism candidates and the Phase-0 broker spike that decides between them are in
   `BE-D5`.
3. **Connect:** WebApp SDK → `wss://<origin>/mesh` (gateway reverse-proxy → broker websocket listener),
   OAuth credential = the ticket. Subscribes to `sm/v1/<tenant>/notify/>`.
4. **Chart tail grant:** a `DM-D1` backfill query that passed RBAC returns `streamTopic` + a refreshed
   token carrying that one stream claim — authorization to see values is a by-product of the query
   authorization, never a separate ACL to keep in sync. Tail rule per `BE-D6`: raw tier, range ends at now.
5. **Renewal is a planned reconnect** (R5 finding 10). The SDK's `updateAccessToken` re-establishes the
   session rather than renewing in place (verified in the webAppSDK source and README: *"updated/
   reconnected"*), so renewal deliberately reuses the reconnect path of step 6: subscriptions are restored
   and any open chart re-runs backfill from its watermark, so a renewal can produce a server-declared gap
   but never a duplicate or an invented point. The renewal REST call happens ahead of expiry; **idle
   timeout** (`FE-D24`) ends the session, stops renewal, and disconnects the SDK. Revocation latency is
   bounded by token TTL (limit stated in `BE-D5`).
6. **Disconnect/reconnect:** SDK backoff; UI shows *live / degraded-to-polling*; on reconnect, TanStack
   Query refetches active queries and charts re-run backfill from their watermark.

## 6. Telemetry ingest (`BE-D9`)

Per-binding acquisition (`SM-D4`/`D5`) through the **canonical `solace_event` `source.kind`** the executable
contract already defines (`contracts/solution-package.schema.json`) — extended, never renamed (`UI-D14`):

- **Push MIs** (File Mesh command-center events today): the package declares topics/queue + mapping to
  canonical `name`, `metricType`, `unit`, replica identity. `mesh-bridge` subscribes read-only on the
  customer's own VPN/topics, normalizes, writes `sm_data`, then emits notify/stream messages.
- **Pull MIs** (Cache actuator poll): unchanged; the poller emits the identical notify events after its
  writes. Downstream cannot tell push from pull (`DM-D3`).
- **Quarantine holds** (`UI-D5`): an undeclared series arriving by broker is quarantined like any other —
  transport grants no trust.
- Raw MI payloads terminate in `dp-core`. Nothing MI-shaped ever reaches a browser-subscribable topic.

⚠️ **Collector locus — flagged, not resolved** (R5 finding 5). [`../architecture.md`](../architecture.md)
assigns the `solace_event` collector to the **Go agent** under `host-local` (Java DP under
`dp-remote`/`observe-only`, implemented twice by design). This section's "`mesh-bridge` is the DP's only
mesh client" would instead centralize collection in the DP now that SM rides the customer mesh —
attractive (one implementation, no open-item-14 dependency), but it is a supersession of the
architecture's locus assignment, and DP→mesh reachability is site-specific, which is what `host-local`
exists for. Routed as open question 8 (§16), decided with the `AG-D*` owners. **v1 is unaffected** —
Cache is HTTP-polled; File Mesh is phase 2.

## 7. REST surface

Unchanged from the existing designs: session/login, catalog CRUD, package load, instance declaration, draft
save with generation + `409` (`FE-D18` clause A), apply/restart commands, RBAC admin, the `DM-D1` query
API, streaming CSV export (`DM-D14`). Additions:

- `POST /api/console/v1/mesh/ticket` (+ renewal) — §5.
- The backfill response's `streamTopic` + token extension — §5 step 4.
- The **Console OpenAPI** becomes a deliverable of this design's plan, alongside the AsyncAPI document —
  the two contracts ship together, and `FE-D7`'s generated types come from them.

## 8. Charts: backfill-then-tail (`BE-D6`)

The live stream never owns history. Open chart → `DM-D1` query (history, tier routing, type-aware
aggregation `DM-D8`, server-declared gaps `DM-D16`, retention truncation) → response carries an end
**watermark** → subscribe to the granted tail topic → append only points newer than the watermark, deduping
at the seam. **Tail exists only when served tier = raw and the range ends at now**; hourly/daily-tier charts
stay query + fallback-refresh — appending live seconds onto rollup buckets is the tier-crossing hazard
`DM-D8` refuses. The browser never aggregates. Range change, scroll-back, dashboard reopen: pure query API.

## 9. Failure modes — degrade, never lie

| Failure | Behaviour |
|---|---|
| Broker unreachable / VPN misconfigured | Console banner "live updates unavailable"; `DM-D17` polling takes over; **all REST functions unaffected** (`BE-D1`) |
| Websocket drops | SDK backoff reconnect; on success: Query refetch + chart backfill from watermark; gaps come back server-declared |
| Event lost / duplicated / reordered | Loss: healed by fallback poll or next fetch. Duplicate: idempotent invalidation. Stale: discarded by the envelope's monotonic `stateVersion` (§3) — never by wall-clock (`B/D15`) |
| Gateway proxy down | SPA itself is down (it serves from the same gateway) — no split-brain state |
| Ticket expiry mid-session | Renewal ahead of expiry; failed renewal → degraded-to-polling, session itself unaffected |
| Broker slow / backpressure on tails | Tails are direct messaging — drops surface as gaps on next backfill, never as blocked publishes in `dp-core` |

## 10. What this design deliberately does not change

- **Apply semantics.** Rollout notify events are *observed state arriving faster* — not an apply protocol.
  `FE-D18` clause B stays **blocked** on open item 1; `APPLY_INDETERMINATE` remains the `dp-remote` default.
- **The agent loop.** `B/D1`'s outbound agent↔DP channel stands; the mesh sits behind the DP (§12).
- **Storage.** Plain PostgreSQL, partitions and rollups per `SM-D26`. No event store.
- **RBAC model and catalog.** Enforcement stays the single centralized deny-by-default check; the verb
  catalog gap (open items 23/24, the `D10` amendment) is a dependency of this design, not resolved by it.

## 11. Security analysis

- **`I5`** — tenant is a server-derived JWT claim minted from the session; the topic-level tenant exists so
  broker ACLs enforce the same boundary without payload inspection. Required negative test: tenant A's
  token cannot subscribe `sm/v1/<tenantB>/…` (fixture in §14).
- **`I6`** — notify topics carry no values by schema (AsyncAPI negative cases enforce it); stream topics
  carry normalized numeric points + series identity only; secrets never ride any topic; raw MI payloads
  never leave `dp-core`.
- **`I7`** — the gateway composes over service interfaces; `mesh-bridge` lives inside the DP; no
  cross-schema access appears anywhere in this design.
- **Credential custody** — SM holds SEMP credentials for **install/config-time** administration of its own
  VPN only; no per-session SEMP; the browser holds only a ≤15-min ticket; standing broker credentials exist
  only in `mesh-bridge`'s server-side config (secret-backend resolved, `I6`).
- **Signing-key lifecycle** (R5 finding 7) — the gateway's JWT signing key is persisted across restarts;
  rotation is overlapping (old and new keys published in JWKS through the broker's cache window); the
  broker's JWKS refresh interval is an install-runbook parameter; negative tests cover old-token-after-
  rotation and new-token-before-refresh (§14). A gateway restart must never invalidate every live ticket.
- **New authorization surfaces get verbs, not ad-hoc checks** (R5 finding 9) — ticket minting, chart-tail
  grant (and its revocation) are named into the `D10` verb-catalog amendment
  ([`../D10-AMENDMENT-PROPOSAL.md`](../D10-AMENDMENT-PROPOSAL.md), open item 24), so mesh access goes
  through the same deny-by-default catalog as everything else.
- **Stated limits:** token TTL bounds revocation latency; the notify namespace leaks *existence* to any
  in-VPN principal a customer over-grants (dedicated-VPN posture is the mitigation, prefix-fallback weakens
  it — `BE-D2`); the SEMP install procedure needs its own runbook + review (§15).

## 12. Impact notices — relay to the owning workstreams

### 12.1 Frontend (owner: frontend developer)

1. **New dependency:** the Solace WebApp SDK, imported **only** through a new `shared/mesh` module — the
   `FE-D16` pattern, with a check-guards guard + self-test to match.
2. **`FE-D19` amended, not reversed:** still one public origin; it gains `wss://<origin>/mesh`. **`FE-D7`
   unchanged** — the mesh is not a third query client. **Events never write into the TanStack Query
   cache**: a notify event only invalidates keys; the refetch is REST. Chart-tail appends live outside the
   Query cache (chart-local buffer).
3. **`DM-D17` demoted to fallback**, not deleted; new visible UI state: *live / degraded-to-polling*.
4. **Charts:** backfill-then-tail per §8 — watermark stitching, raw-tier-and-now only, reconnect heals via
   re-backfill.
5. **Session:** after login, fetch the mesh ticket; renew ahead of expiry; `FE-D24` idle timeout also
   disconnects the SDK. No standing broker credential ever reaches the browser.
6. **Contracts:** generated types will come from the Console OpenAPI + the AsyncAPI document (§7); until
   they exist, nothing mesh-related is hand-typed into `shared/`.

### 12.2 Go agent (owner: agent developer)

**No v1 change.** The agent↔DP loop (`B/D1`, `AG-D*`) is untouched; the mesh sits behind the DP, which
emits notify events after its own writes regardless of how data arrived. Two watch items, neither v1:
(1) a later increment may let the agent publish telemetry over the customer mesh — existing open item 14
(Solace Go API maturity), explicitly **out of scope** here; (2) the architecture currently assigns the
`host-local` `solace_event` collector to the Go agent, and this design raises central DP collection as a
flagged alternative — **open question 8 (§16), to be decided with the `AG-D*` owners before phase-2 File
Mesh work**. If the architecture's assignment stands, the agent gains the collector open item 14 already
anticipates; if central collection wins, the agent gains nothing.

## 13. Phasing

**The plan built from this design is gate-first, not feature-first** (R5.1 finding 2): implementation work
that depends on an open gate does not start on optimism. Gate-independent work — contracts, event envelope,
module scaffolding, `mesh-bridge` seams — may proceed in parallel with the gates.

> **2026-08-31:** the walking skeleton's three remaining blocker areas — the `dp-remote` apply protocol
> (open item 1), minimal authorization (G2 / open item 24) and the live-console session contract (open
> items 32/33's v1 postures) — are now designed in
> [`eda-walking-skeleton.md`](eda-walking-skeleton.md) (`BE-D*`, PROPOSED). The gate table
> below stands unchanged: G2 still gates auth-bearing tasks, and items 32/33 still gate `BE-D5`
> ratification and the ACL-provisioning runbook — the new design gives each gate a concrete proposal
> rather than lifting it.
>
> ⛔ **2026-09-16 — the note's G2 clause is no longer true, and the correction matters because
> implementers read this table.** **G2 is CLOSED** and the `D10` amendment is RATIFIED and `LOCKED`
> ([`../D10-G2-APPROVAL-2026-09-15.md`](../D10-G2-APPROVAL-2026-09-15.md)), so **G2 gates no auth-bearing task**
> and the row below is struck. ⚠️ **Items 32/33 — and 35 — are still open** (⚠️ **three items; this note and the G1 row below both said "two"**) — their *framing* was NARROWED 2026-09-16 (OR-5/OR-6) but their status was not changed, and
> with broker enforcement **unmeasured** — so they still gate `BE-D5` ratification, the ACL-provisioning runbook
> and every mesh-backed live-delivery task. **Two different statuses; do not read one onto the other.**

| Gate / phase | Scope |
|---|---|
| **Gate G1 — broker spike** (gates `BE-D5`/`BE-D6` ratification and every ticket/stream task — R5 finding 2) — **ran 2026-08-27; result checked in ([spike-authz-result.md](spike-authz-result.md)): candidate (a) chosen, gaps remain as open items 32/33 — ⚠️ **and 35, which `eda-walking-skeleton.md` §6 adds and this row omitted**** | live PubSub+ test deciding the subscribe-authorization mechanism between `BE-D5`'s three candidates (substitution-variable ACLs · per-grant authorization groups · tenant-coarse ACL + unguessable grant-ids), plus measured JWKS rotation/refresh behaviour and SMF-over-websocket behind the chosen reverse proxy. Written result checked in and cited by `BE-D5` |
| ~~**Gate G2 — `D10` closure**~~ ✅ **CLOSED 2026-09-16** — the `D10` amendment is **ratified and `LOCKED`** ([`../D10-G2-APPROVAL-2026-09-15.md`](../D10-G2-APPROVAL-2026-09-15.md)); **the project owner is the final architecture authority**, so no architect-group decision was owed, and open items 24/38/40 are closed. Auth-bearing tasks are unblocked within that record's scope; the catalog is **38** verbs with three built-in roles. Historical statement follows:  (gates every authn/authz-bearing task: login, ticket endpoint, RBAC-gated surfaces) | the architect group decides the [`D10` amendment](../D10-AMENDMENT-PROPOSAL.md) — open item 24: verb catalog (now including `ticket mint` / `stream grant+revoke`), first-admin bootstrap, local identity store, audit log. Until it closes, `REQ-006`'s own acceptance makes any auth-bearing endpoint unbuildable — building it anyway smuggles the decision |
| **Gate G3 — event envelope before AsyncAPI** | **closed in-spec (envelope — the catalog carries Known-gap items pending the `BE-D4` amendment)** (§3, R5.1 finding 1): the AsyncAPI document generates from the common envelope + catalog as specified; no event schema ships without the envelope. The four open items are *catalog vocabulary*, listed in [`contracts/events/README.md`](../../../contracts/events/README.md) Known gaps — quarantine topic absent from `BE-D4`'s list, rollout `step` without a canonical home, §3's rollout row omitting `placementId`, `packageVersion` untyped — not envelope defects; generated client/server types are not settled until that amendment lands |
| **v1 walking skeleton** (extends `D17`'s Cache slice; ~~auth-bearing tasks start only after G2~~ ✅ **G2 closed 2026-09-16 — satisfied**, mesh-access tasks only after G1 ✅ **closed — but `BE-D5` ratification is still blocked on open items 32, 33 *and* 35**, which are three items, not the "two ACL gaps" an earlier revision named) | `console-gateway` + local auth (`BE-D8` v1) + ticket endpoint · `sm/v1` notify events for health + config-state · SDK integration behind `shared/mesh` · polling fallback + degraded indicator · AsyncAPI + OpenAPI contracts · install-time VPN/OAuth/ACL provisioning runbook |
| **v1 remainder** | rollout + version + catalog + quarantine events · ticket renewal hardening · `I5` negative-test fixtures in CI |
| **Phase 2** | chart live tails (`BE-D6` stream half — after the query API exists) · `solace_event` ingest for File Mesh (`BE-D9`, with the reviewed schema extension) · OIDC SSO provider · outbox upgrade if alerting lands |

## 14. Testing & verification

- **Contract:** AsyncAPI fixtures + negative cases (a notify payload carrying a value field **fails**) wired
  into `contracts/run-checks.sh`; OpenAPI-generated types on both sides.
- **Integration:** docker-compose PubSub+ broker (reuse the webAppSDK repo's broker tooling: setup, certs,
  OAuth profile scripts) driving gateway-mint → broker-validate → subscribe → publish → receive end-to-end.
- **Security fixtures (required):** tenant A token vs tenant B topic (deny) · notify-scope token vs stream
  topic (deny) · expired ticket (deny) · stream grant expires with token · old token after key rotation
  (deny once refresh window passes) · new token before broker JWKS refresh (documented behaviour, tested).
- **Renewal seam:** token renewal (a planned reconnect) under an open chart — no duplicate points, any real
  gap comes back server-declared after re-backfill.
- **Phase-0 spike deliverable:** a written result naming the chosen authorization mechanism with the broker
  configuration that proved it, checked into `docs/design/backend/` and cited by `BE-D5` at ratification.
- **Chaos:** broker down → REST unaffected + banner + polling; websocket drop mid-chart → reconnect,
  re-backfill, gap server-declared; duplicate/reordered notify → no state regression.
- **Frontend guard:** SDK importable only from `shared/mesh`, proven by a planted-violation self-test.

## 15. Capability limits — stated, not implied

1. Liveness (not correctness) depends on a broker SM does not operate; the degraded mode is polling.
2. Mesh-access revocation latency = ticket TTL.
3. Notify subscriptions leak existence within the VPN; the dedicated-VPN posture is the boundary.
4. No live tail on rollup-tier charts — by design (`BE-D6`), not as a gap.
5. Day-one install requires broker-admin cooperation (VPN, OAuth profile, ACLs); SM cannot self-provision.
6. Event delivery is best-effort; any consumer needing at-least-once (future alerting) requires the outbox
   upgrade named in §3 first.
7. The stream-grant authorization mechanism is **unproven until the Phase-0 spike**; if the tenant-coarse
   candidate wins, within-tenant stream visibility becomes a stated limit at that point — never a silent
   one.
8. Live delivery is designed for the on-prem topology only; the SaaS browser↔mesh path is an open design
   (question 7), and until it exists a SaaS console would fall back to polling.

## 16. Open questions (routed, not silently absent)

> **Questions 1, 3 and 5 are answered by the G1 spike** — [`spike-authz-result.md`](spike-authz-result.md), measured
> against a live broker. The answers are appended to their rows below; the rows are kept so the routing history stays
> readable. No other question here was settled by the spike.

| # | Question | Routed to |
|---|---|---|
| 1 | Minimum PubSub+ broker version for the OAuth/JWKS resource-server profile — verify against version matrix before the install runbook is written (`I2`) · **ANSWERED** by [`spike-authz-result.md`](spike-authz-result.md) §Broker version floor: everything the chosen mechanism uses is present at **SEMP 2.48** (the tested version) and the hard prerequisite is the **Solace Enterprise image** — Standard has no working `authenticationOauthProfiles` at runtime; recommended runbook floor **SEMP 2.32 / Enterprise**, which is the broker's own *"available since"* annotation for candidate (b)'s `authorizationGroupsClaimStringFormat` and still needs confirming against Solace's published version matrix before it ships as a supported minimum | plan task |
| 2 | Dedicated-VPN vs prefix-fallback: is the fallback ever acceptable for production, or dev-only? | architect group, with `BE-D2` |
| 3 | Ticket TTL and renewal cadence — 15 min is a proposal; pick against real revocation requirements · **ANSWERED in its measured constraints** by [`spike-authz-result.md`](spike-authz-result.md) §JWKS rotation and §Renewal (the 15-minute number itself still ratifies with `BE-D5`): **rotation is not revocation** — a token signed with an evicted key kept connecting for **151 s** (uncommitted transcript; the committed test records 60 538 ms) with the broker's JWKS fetch counter unmoved and died only when other traffic forced a re-read, so **ticket TTL is the only revocation lever** and a retired key must stay published for **≥ one full TTL + the 60 s JWKS refresh floor** (~16 min at a 15-minute ticket); and because `disconnectOnTokenExpirationEnabled` defaults `true` (measured: a live subscribed session on a 30 s ticket disconnected at ~31 s) and the broker's refusal carries no reason, the gateway must **re-ticket proactively on its own clock**, never reactively | ratification of `BE-D5` |
| 4 | Exact SEMP objects the install provisions (client-profile, ACL profile, OAuth profile parameters) | install runbook (`docs/runbooks/`), plan task |
| 5 | Does the gateway proxy websockets itself (Spring) or delegate to the packaging reverse proxy (nginx)? · **ANSWERED — not forced by the protocol** ([`spike-authz-result.md`](spike-authz-result.md) §Reverse proxy): nginx terminated TLS and proxied `wss://localhost:9443/mesh` with nothing beyond `proxy_http_version 1.1` + the `Upgrade`/`Connection` headers and **no path rewrite**, so §5's `wss://<origin>/mesh` shape works as designed; SMF keepalives measured at **3.0 s in both directions**, putting the proxy read/send timeout floor at ~10 s — nginx's own 60 s default would have sufficed, and the spike's 3600 s is a judgement call about backgrounded browser tabs, unverified in a real browser. **Which side proxies remains a packaging decision** | plan task — packaging decision with `RT-D*` |
| 6 | The `sm/v1/internal/*` namespace's consumer set in v1 (gateway only?) — avoid speculative internal events | plan task |
| 7 | **SaaS browser↔mesh topology** (R5 finding 1): a Solace-hosted gateway cannot proxy into a customer broker without inbound reach — DP-side relay, outbound tunnel, or polling-only SaaS console? Designed with the SaaS split; the seam is named now | architect group, with the split |
| 8 | **`solace_event` collector locus** (R5 finding 5): keep the architecture's per-locus assignment (Go agent under `host-local`, twice-implemented) or supersede it with central `mesh-bridge` collection now that SM rides the customer mesh? DP→mesh reachability on `host-local` sites is the deciding fact | architect group + `AG-D*` owners, before phase-2 File Mesh |
