# Console integration — the frontend ↔ backend contract reference

**Status: REFERENCE, reviewed 2026-09-15. It decides nothing of its own.** Every rule below is carried by a
decision that already exists; this document is the **map** between them, written so the frontend can be built
against it **without reading a single backend implementation class**. Where a rule is only proposed here, it says
so in the row.

| | |
|---|---|
| **Audience** | Frontend and backend engineers building the console; whoever writes the OpenAPI |
| **Authorities it composes** | ⚠️ **Owner rulings of 2026-09-16 carry four of this document's sections, and are named first because nothing else carries them:** **OR-2** (§0's six-name API/topic vocabulary) · **OR-16** (§0's **five-level grant** hierarchy — it amends `D11`, names it, and supersedes OR-2's grant half) · **OR-18** (§5 row 52a's `stop` owner) · **OR-3** (§2 rule 9's CLI/browser split) · **OR-4** (§9's approved notice) · **OR-5**/**OR-6** (§11's VPN boundary and topic shape) · **OR-10** (§13.3's `console-api`) — all in [`DECISIONS.md`](../../DECISIONS.md). Then: `BE-D1` (REST authoritative, events are notices) · `BE-D4` (envelope + taxonomy) · `BE-D6` (polling is the degraded mode) · `BE-D7` (one mesh session, confined) · `BE-D12`/`BE-D13` (apply protocol + persistence) · `BE-D14` (live-console session contract, `LiveStateClient`) · `BE-D15` + the [2026-09-15 `D10` amendment](../../D10-G2-APPROVAL-2026-09-15.md) (authorization) · `FE-D7` (REST half) · `FE-D13` (correlation ids) · `FE-D14`/`FE-D24` (session, CSRF, idle) · `FE-D20` (no optimistic mutations) · `DM-D16` (server-declared gaps) · `DM-D17` (superseded by `BE-D6` for liveness) · `I5`/`I6`/`I7` |
| **Sibling** | [`backend-consumption.md`](backend-consumption.md) — how the **backend** consumes `contracts/` without drifting. This document is the **console** side |
| **Planned owner** ⚠️ **(nothing is implemented yet — the plan is written, not started)** | [plan 007](../../../plans/backend/007-production-api-and-event-driven-walking-skeleton.md) |

⚠️ **The one thing to read before anything else.** The console is **event-driven with REST as the system of
record**, and that is not a future phase. Events say *something changed*; REST says *what it is*. A console built
on polling is not a simpler version of this design — it is a different design, and §2 rule 9 is the only place
polling is permitted.

---

## 0. The canonical hierarchy and vocabulary

✅ **Ruled 2026-09-16 (OR-2).** Every external surface in this document — REST paths, RBAC scopes, topics —
uses **this** vocabulary and **immutable ids**.

```
Organization -> Sub-organization -> Environment -> Solution -> Instance -> Placement
```

| Rule | Statement |
|---|---|
| **Vocabulary** | **Sub-organization**, not *Organization Unit* (`SM-D32`'s word, now amended) |
| **Depth** | Six levels. ⚠️ **Solution and Placement are part of the hierarchy**, not annotations on it — `SM-D32` stopped at *Solution Instance*, and a document that omits **Placement** is stale |
| **Identity** | **Immutable ids** on every external surface. A rename must not move a REST path, a scope or a topic |
| **`tenant_id`** | may remain, and only as an **internal storage key**. ⛔ It is not an external concept: it appears in no path, no scope, no topic and no payload (`SM-D34`, confirmed) |
| **Why Placement matters here** | It is the level an apply acts on, the level `instance.apply-outcome` is scoped to (§9), and the level §11.2's topic shape extends to. ⛔ **It is NOT a grantable RBAC level** (**OR-16**): authorization inherits downward through the **five grantable levels**, ending at **Instance**, and a placement-targeted operation authorizes through its **owning Instance** scope plus any separately required **Target** permission. ⚠️ **An earlier revision of this row said authorization *"inherits downward through all six levels"*, which would have implied a placement-level binding** |

---

## 1. Canonical artifacts — and which of them exist

**"Canonical" means: if two documents disagree, this one wins.** The starting-state measurement at commit
`2a0047e`, 2026-09-15 remains provenance only. Current integration status is measured after plan 005 merged and
the planning candidate rebased onto this branch; where this document says "implemented here", that is the current
integration lineage rather than a branch-only claim.

| Concern | Canonical artifact | Exists? |
|---|---|---|
| **REST surface (normative)** | `contracts/openapi/console.yaml` — reviewed under the single-writer rule, with generated+validated Java and TypeScript models | ✅ **contract exists.** It declares 25 operations and check 11 enforces the permission and scope bindings; production handlers do not yet exist |
| **REST surface (as-built input)** | `docs/design/common/contracts/console-rest-surface.md` — 66 operations. **2,372 lines at `51ef2b0`**, the commit that introduced it; the 3,284-line version is `origin/dev/frontend` HEAD (`c27fda1`) | ⚠️ **not on this branch.** [`dev-frontend-intake.md`](../../../tasks/dev-frontend-intake.md) step 4 imports it as a **reference**, explicitly *not* as a normative contract |
| **Event envelope** | [`contracts/events/envelope.schema.json`](../../../../contracts/events/envelope.schema.json) — `$id: sm/events/v1/envelope` | ✅ exists, gated by `run-checks.sh` check 10 |
| **Event catalog** | [`contracts/events/notify-events.schema.json`](../../../../contracts/events/notify-events.schema.json) — the six v1 events | ✅ exists, gated |
| **The eight recorded event-contract gaps** | [`docs/design/backend/BE-D4-AMENDMENT-PROPOSAL.md`](../../backend/BE-D4-AMENDMENT-PROPOSAL.md) — including the **terminal apply outcome** | ⚠️ **written, NOT approved.** Until it is, no new event may be implemented (§9) |
| **Topic taxonomy** | [`contracts/events/asyncapi.yaml`](../../../../contracts/events/asyncapi.yaml) | ✅ exists, gated (channel-prefix check only; no AsyncAPI toolchain in CI) |
| **Event fixtures / negatives** | `contracts/events/fixtures/`, `contracts/events/negative/` | ✅ exist; Java golden tests consume the fixtures byte-for-byte |
| **Browser `NotifyEvent` TypeScript types** | hand-written against the event schemas under `BE-D10`'s mirror-and-lock discipline **today**; generated once the `BE-D4` amendment lands | ⚠️ **buildable now, not built.** `FE-D7` says *"types are generated from the Console OpenAPI contract when it exists; until then, **hand-written types live next to the clients and are replaced, not grown**"* — it permits hand-written types, and it governs the **REST** half. The notify half is the AsyncAPI/event-schema path, separately gated (`eda-walking-skeleton.md` §4.3). The amendment decides whether these become **generated**, not whether they may exist |
| **Live-state client seam** | `LiveStateClient` in the frontend's `shared/mesh` module (`BE-D14` §4.3 prints the interface) | ❌ **no `shared/mesh` module exists**; measured |
| **Auth/session contract** | §4 of this document, over `BE-D15` §5.3 + `FE-D14`/`FE-D24` | ⚠️ specified, **not built**. The as-built doc marks login/identity/logout *"CONTRACT: TBD"* |
| **Permission catalog** | [`D10-G2-APPROVAL-2026-09-15.md`](../../D10-G2-APPROVAL-2026-09-15.md) §1.1 + §1.7 — **38 members, enumerated** | ✅ **ratified and `LOCKED` 2026-09-16**; ❌ not implemented |
| **The console's own permission catalog** | [`frontend/src/shared/authz/catalog.ts`](../../../../frontend/src/shared/authz/catalog.ts) — what `Can`/`usePermissions` consume | ⚠️ **implemented, and it is a *different* 35.** It carries the five `group.*` verbs and **lacks** `mesh.ticket.mint`, `mesh.stream.grant`, `mesh.stream.revoke`, `authz.introspect.self`, `authz.introspect.any`; it also marks `metrics.export` as **not** high-consequence where `architecture.md` §16 does. So five verbs §5 requires **cannot be expressed** in the typed catalog today, and five it *can* express are unauthorizable server-side. A coordinated change, gated on the `group.*` decision — open items 39 and 34 |
| **Endpoint → permission → scope map** | **§5 of this document.** It exists nowhere else — the as-built REST doc carries no per-endpoint permission | ⚠️ **this document is the first one**; rows marked `P` need ratifying with plan 007's OpenAPI |
| **Closed vocabularies** | [`contracts/solution-package.schema.json`](../../../../contracts/solution-package.schema.json) (`UI-D14`) · [`STATE-VOCABULARY.md`](../../frontend/reference/STATE-VOCABULARY.md) · [`contracts/view-models/src/types.ts`](../../../../contracts/view-models/src/types.ts) | ✅ exist |

⚠️ **There is no OpenAPI document in this repository.** Any statement that the console has a canonical REST
contract today is false. What exists is a reviewed *description of what one frontend calls*, on another branch.
Plan 007's first task is the OpenAPI, and until it lands every REST shape in §5 is a **target**, not a contract.

---

## 2. The hybrid model — twelve rules, and none of them is optional

These are the normative rules. Each cites the decision it comes from; nothing here is new.

1. **REST is the authoritative snapshot and backfill path.** Every value the console renders as truth arrived over
   REST (`BE-D1`).
2. **PostgreSQL remains the system of record.** No event, cache or in-memory projection is an authority. The two
   apply-family authorities are `apply_request`/`apply_step_intent`/`apply_attempt` (`BE-D13`) and the config
   tables; a mesh event is never queried to answer "what is true".
3. **DP state changes emit contract-defined events** through the existing
   [`EventPublisher`](../../../../backend/mesh-events/src/main/java/com/solace/sm/events/publisher/EventPublisher.java)
   seam and the `dp-core` mesh bridge. Not a new transport, not a hand-rolled notifier.
4. **Events publish only after the state transaction commits** — `AfterCommitEventPublisher` defers the transport
   call to the boundary's after-commit hook; a rollback discards the notice, because the write happened to nobody
   (`BE-D1`).
5. **Notify events carry identifiers, state versions and change information — never authoritative entity state.**
   ⚠️ **This rule is scoped to the *notify* tier.** The **stream** tier (chart tails) does carry metric values, by
   `BE-D6`'s design, to a session that holds a grant — see §10.1. Reading rule 5 as "no values ever cross the
   mesh" is wrong in a way that matters, because it makes §10.1 look like a violation.
   `I6` makes this executable: no config value, no secret, no metric value, no label value. A fully leaked notify
   subscription reveals that instances exist and change, never what they contain
   (`contracts/events/negative/value-in-notify.json` is that rule as a check).
6. **Notify handlers invalidate query keys and refetch through REST. They never write event payloads into
   authoritative query state** (`BE-D14` §4.3, `FE-D7`/`BE-D7`). §7 is the invalidation table. A handler that
   calls `setQueryData` from an event payload is a defect, not an optimization.
7. **Reconnect, ticket renewal, missed events and sequence gaps all trigger REST backfill from the last
   `stateVersion`.** `onResync(reason)` fires after every (re)connect with `'connected' | 'renewed' |
   'recovered'`; the handler refetches active queries and discards any notice not newer than the state already
   held. **Missed events are healed by reading, never replayed** (`BE-D1`, `BE-D14` §4.2).
8. **Raw chart tails use the designed event-stream path and resume from the REST/backfill watermark.** `openTail`
   appends only points newer than the backfill watermark and re-backfills on every resync; gaps stay
   server-declared (`DM-D16`).
9. **Polling's place depends on the client, and that is a ruling rather than a tolerance** (OR-3, 2026-09-16):

   | Client | Primary mechanism | Events |
   |---|---|---|
   | **CLI / terminal** | ✅ **adaptive REST polling** — legitimately primary. A terminal client has no session to keep warm, no query cache to invalidate and often no mesh reachability; an adaptive interval (tighten while a request is in flight, relax when idle) is the right design, not a degradation | not required |
   | **Browser / console** | ⛔ **events** — *refetch now* notices, with polling as **fallback** | required |

   ⛔ **This does not soften the browser rule.** For the console, **polling is permitted only in three named
   situations, and "normal live operation" is not one of them** (`BE-D6`, which supersedes `DM-D17` for
   liveness):
   - **session-level degraded mode** — the mesh path is unavailable and the session indicator says so (§10);
   - **bounded recovery after a detected event gap** — a resync fires, REST backfills once from `stateVersion`,
     and the poll stops. Not a standing loop; and
   - **a surface whose event contract does not exist yet** — today exactly one: terminal apply outcomes (§9). It
     carries a **surface-level** degraded label while the **session** stays `live`, because the mesh is healthy
     and saying otherwise would misreport it (§10.2).

   ⛔ **Terminal apply outcomes are event-driven too.** The v1 catalog has no terminal-outcome notice (§9), and the
   answer is **an amendment, not a poll**: see [`BE-D4-AMENDMENT-PROPOSAL.md`](../../backend/BE-D4-AMENDMENT-PROPOSAL.md).
   Until it is approved and implemented, that **surface** is labelled degraded (§10.2) while the session stays
   `live`. **It is not "normal live behaviour that happens to poll."**
10. **The UI exposes live versus degraded state.** `LiveStateClient.state` is `'live' | 'degraded' | 'stopped'`
    and drives a visible indicator. A console that silently degrades is the failure this rule exists to prevent.
11. **The browser never publishes to the mesh.** Enforced at the broker (`publishTopicDefaultAction: disallow`
    with **empty** publish exceptions, asserted from SEMP config at provisioning) *and* by the seam exposing no
    publish API. G1 measured that a publish denial is invisible to the client, so no client-side check can
    substitute (`BE-D14` §4.2).
12. **ACL, ticket, stream-grant and session-isolation requirements remain enforced.** Ticket claims are the fixed
    gateway-owned set (`BE-D14` §4.1); `sub` names the session's readable branch; minting, granting and revoking
    are RBAC-gated by `mesh.ticket.mint` / `mesh.stream.grant` / `mesh.stream.revoke` and audited with `jti`.

### 2.1 What "events are not authoritative" costs, stated honestly

The seam is **pre-outbox**, and pretending otherwise would be the worst thing this document could do.

- A crash or broker outage **between commit and publish loses the notice.** The write is committed and durable;
  only the notification is gone.
- That is **freshness loss, not durable-state loss** — REST heals it, because REST reads the committed row. The
  next resync, the next navigation, or the degraded poll picks it up.
- **Drops must remain observable — and the honest scope of that is narrower than it sounds.**
  `JcsmpEventPublisher` increments a counter and logs a warning on every swallowed transport failure, and an R8
  mutation run proved the increment fires. Two measured limits:
  - ⚠️ **Publisher-side only.** Notify events are published **DIRECT (non-persistent)**. A message discarded at
    broker egress — no subscriber, a slow subscriber, a browser mid-reconnect — raises no `JCSMPException`, logs
    no warning and increments nothing. Those are the *ordinary* drops in production, and they are **unobservable
    by design**. The compensating control is resync-driven REST backfill (§8), not a metric.
  - ⚠️ **Not exported.** The counter is an in-process `AtomicLong` reachable only via `droppedCount()`. Its own
    javadoc says it is a plain `AtomicLong` on purpose because *"this module has no metrics dependency and the
    metrics plan must expose it"*. Plan 007 exports it as a **deliberate re-route** of that obligation (plan 007
    does not otherwise choose a metrics stack), or "observable" stays a claim about a getter nobody reads.
- ⚠️ **A dropped *tail* point is a different loss from a dropped notify.** A notify drop costs freshness and REST
  heals it. A tail drop leaves a **hole in a chart that nothing heals until the next resync** — and §10.1 forbids
  the client from inferring one, because gaps are server-declared (`DM-D16`). That asymmetry is why tails are
  raw-tier-and-ends-now only, and why every resync re-backfills rather than resuming.
- **If an event ever becomes authoritative, or ever needs at-least-once delivery, the answer is a transactional
  outbox** — write to an outbox row in the same transaction, drain asynchronously, behind this same
  `EventPublisher` interface. That is the bounded upgrade the seam was built for. **It is not built.** Do not
  describe after-commit publishing as at-least-once.

---

## 3. REST versus EDA — responsibility matrix

| Concern | REST | Events | Rule |
|---|---|---|---|
| Authoritative value of anything rendered as truth | ✅ **sole source** | ❌ never | 2·1, 2·2 |
| First load of a screen | ✅ | — | 2·1 |
| "Something changed, refetch" | — | ✅ **sole source when live** | 2·6 |
| Recovery after disconnect / renewal / missed event | ✅ **backfill by `stateVersion`** | ❌ no replay | 2·7 |
| Ordering and stale-discard | ✅ **supplies the watermark** (§6.1 — every entity response carries `stateVersion`) | ✅ **compares against it**; `stateVersion` only, **never `occurredAtUtc`** | `B/D15` 🔒 |
| Raw chart tail (range ends now) | ✅ backfill + watermark | ✅ tail append | 2·8 |
| Historical / ranged metric query | ✅ | ❌ | `DM-D*` |
| Terminal apply outcome — **target** | ✅ **authoritative read** of `…/apply/observe` | ✅ **event-triggered invalidation** via `instance.apply-outcome` (OR-4; ⚠️ contract LANDED 2026-10-05 at `878b50c`, plan 010 T0.2, on `OR-58`'s gated topic `…/notify/instance/{instanceId}/apply-outcome/{applyId}`) | 2·6 |
| Terminal apply outcome — **today** | ⚠️ **poll `…/apply/observe`, in declared degraded mode** | ❌ no event contract exists yet | 2·9 |
| Mutations (apply, restart, rollback, config save, package load) | ✅ **REST only** | ❌ browser never publishes | 2·11 |
| Liveness indicator | — | ✅ session state | 2·10 |
| **CLI / terminal client state** | ✅ **adaptive polling — primary and correct** | not required | 2·9 (OR-3) |
| Secrets, config values | ✅ (redacted per `I6`) | ❌ **forbidden in every payload** | 2·5 |
| Metric **values** | ✅ (query / export) | ⚠️ **forbidden on the notify tier; carried on the stream tier** — `BE-D6`: the DP publishes normalized points (values + series identity) to a granted tail | 2·5, 2·8 |

**Read the matrix in one sentence:** *events move the console's attention; REST moves its data.*

---

## 4. Authentication and session contract

**Status: specified here, built nowhere.** The as-built console marks login/identity/logout *"CONTRACT: TBD"*, and
the only implementation in the tree *was* `demo-launcher`'s waiver-scoped surface, whose authorization expired
2026-09-14 (**deleted by plan 011 P11-9.1, `OR-112` (4)**; no such surface remains).

| Property | Contract |
|---|---|
| **Credential exchange** | `POST /cp/auth/login` with local credentials. Rate-limited; lockout counter; minimum-length policy (`BE-D15` §5.3, deliberately small and recorded as such) |
| **Session carrier** | An **httpOnly, `SameSite=strict` cookie the server sets.** The SPA never reads, writes or stores it (`FE-D8`, `FE-D14`). Every request uses `credentials: 'same-origin'` |
| **Durability** | A **server-side session record**, not a self-contained cookie — so expiry and revocation are real rather than advisory (approval §1.3) |
| **Expiration** | Idle **and** absolute. `FE-D24`'s idle lock is the console half; `idleTimeoutMinutes` arrives in the boot document |
| **Revocation** | Server-side: deactivating a principal (`status = deactivated`) or a forced logout ends the session, and **drops the in-memory mesh ticket with it** |
| **Forced rotation** | `must_rotate` on the bootstrap principal **blocks every other endpoint** until the credential is changed. Server-enforced, not a UI prompt |
| **CSRF — on login** | ⛔ **Login does NOT carry the post-login CSRF token, and must not require one.** There is no session yet, so there is no session-bound token to send; requiring one makes first login impossible. Login is protected instead by: the `SameSite=Strict` session cookie it *sets*, **strict `Origin`/`Referer` validation**, rate limiting and lockout. A successful login issues **two** cookies — the `httpOnly` session cookie and the separate **script-readable, session-bound** CSRF cookie |
| **CSRF — everywhere after login** | **Required on every cookie-authenticated mutation**, as the header/cookie double-submit pair issued at login. ⚠️ **Measured:** `configureCsrf` is *not* unwired — `frontend/src/app/providers.tsx` wires it **only on the `local-demo` channel**, to the demo launcher's `X-SM-CSRF` header read from a `SM_DEMO_CSRF` cookie, and calls `configureCsrf({})` on every other channel. Plan 007 Task 13 deletes that launcher, so **no production wiring exists** and plan 007 must supply both halves together — until it does, either every mutation fails or none is protected |
| **The CSRF cookie is deliberately script-readable** | The session cookie is `httpOnly`; the CSRF cookie **must not be**, because the double-submit pattern requires the SPA to read it (`document.cookie` in the wiring above). Two cookies, two different properties, on purpose — do not "harden" the CSRF cookie to `httpOnly` or the header can never be set |
| **`401` semantics** | A `401` on any request is a **normal answer**, not a failure: it fires the session-expired listener and routes to `/login` with an "expired" arrival reason |
| **`403` semantics** | Not-authorized surface. Never retried. ⚠️ `404`-vs-`403` discrimination is a **required behaviour** of the authorization service, and a test of it is plan 006's, not an implementation detail |
| **Tenant / organization authority** | **Never client-supplied** (`I5`). There is no client-supplied organization id; `Identity.organization` is a display string. A create request that carries a desired owning organization carries **resource data the server must authorize**, never proof the caller may act there |
| **Correlation ids** | Client sends `X-Correlation-Id`; **the response's id is authoritative** (`FE-D13`). The 14 toast-bearing mutations must receive it back — it is the server/audit id |
| **`superAdmin` flag** | A **badge**. Not a bypass. The overlay is cosmetic; the server authorizes every request (approval §1.5) |
| **Mesh ticket** | Separate, short-lived (≤ 15 min), **memory-only** in a closure inside the mesh layer — never `localStorage`, `sessionStorage` or a cookie. A reload loses it and re-mints through the session (`BE-D14` §4.2) |

### 4.1 Joining the mesh — everything the browser needs, in one place

⛔ **An earlier draft deferred the whole connection story to `BE-D14` §4.3, which made §17's promise false.** It is
here now.

| | |
|---|---|
| **Transport** | One `wss` websocket to the **SM gateway's own origin** (`BE-D7`, `FE-D19`). The SPA never holds a broker host; the gateway proxies to the broker |
| **Client library** | The Solace **WebApp SDK** (`~/Work/Development/RapidPilot/webAppSDK`), confined to the frontend's `shared/mesh` module. No other module imports it (`BE-D7`, the `FE-D16` pattern plus a guard) |
| **Mint a ticket** | `POST /cp/mesh/ticket` → the ticket **and** a server-computed `renewAfter`. Requires `mesh.ticket.mint`. Audited with `jti` |
| **Request a tail grant** | `POST /cp/mesh/grant` with a `DM-D1` query → a `StreamGrant`. Requires `mesh.stream.grant` |
| **Revoke another session's grant** | `DELETE /cp/mesh/grant/{grantId}`. Requires `mesh.stream.revoke` (high-consequence) |
| ⛔ **What the browser subscribes to** | **A server-supplied topic filter, returned by the mint call.** The client **never builds a subscription string**, because §4 forbids it from knowing `{tenant}` — organization is server-derived and `Identity.organization` is display text (`I5`). The mint response carries the notify filter and the session's stream-branch prefix; the client subscribes to what it is given, verbatim |
| **Ticket storage** | Memory only, in a closure inside `shared/mesh`. Never `localStorage`, `sessionStorage` or a cookie |
| **Publishing** | **No publish API exists on the seam**, and the broker denies it (`publishTopicDefaultAction: disallow`, empty publish exceptions). G1 measured that a publish denial is invisible to the client, so the seam's silence is the control |

**The seam, printed** (`BE-D14` §4.3, reproduced so the frontend need not open a design document):

```ts
interface LiveStateClient {
  /** Connect with a ticket supplier; the client owns renewal scheduling (per renewAfter) and reconnects. */
  start(tickets: TicketSupplier): void;
  stop(): void;
  /** live = mesh session up - degraded = polling fallback active (BE-D6/DM-D17) */
  readonly state: 'live' | 'degraded' | 'stopped';
  /** Notify events: change notices only. Handlers invalidate queries; they NEVER write data. */
  onNotify(handler: (n: NotifyEvent) => void): Unsubscribe;
  /** Chart tails: raw-tier, range-ends-now only. Many concurrent tails ride ONE session. */
  openTail(grant: StreamGrant, sink: TailSink): TailHandle;
  /** Fired after every (re)connect, with the reason - the signal to refetch from stateVersion. */
  onResync(handler: (reason: 'connected' | 'renewed' | 'recovered') => void): Unsubscribe;
}
```

Its five referenced types, so none is left undefined: **`TicketSupplier`** — an async function returning
`{ ticket, renewAfter, notifyFilter, streamPrefix }` from the mint endpoint; **`Unsubscribe`** — `() => void`;
**`StreamGrant`** — the grant endpoint's response, opaque to the client but for its id; **`TailSink`** —
`{ onPoints(points), onGap(declaredGap) }`, gaps server-declared (`DM-D16`); **`TailHandle`** —
`{ close(): void }`. Exact field shapes are the OpenAPI's to fix (plan 007 Task 1); the shapes above are what the
seam consumes.

### 4.2 Credential rotation — the flow that clears `must_rotate`

⛔ **Without this endpoint the bootstrap administrator is deadlocked**: `must_rotate` blocks every other endpoint
server-side, and nothing can clear it. An earlier draft of this document specified the block and not the escape.

**`POST /api/cp/auth/credential`**

| Property | Contract |
|---|---|
| **Authentication** | An authenticated **local** session. It is reachable *while* `must_rotate` is set — it is the one mutation that is |
| **Authorization** | ⛔ **`LocalOperation.ROTATE_OWN_CREDENTIAL`, not a catalog permission.** Rotating one's own credential is not a grantable verb: every principal may always do it, and the ratified **38** contain no such verb. A catalog verb here would have to be invented, which the amendment forbids |
| **CSRF** | **Required** — the session-bound token pair. This is a cookie-authenticated mutation like any other; only *login* is exempt (§4) |
| **Request** | The **current** credential and the new one. The current one is verified before anything changes — a session hijack must not be enough to change a credential |
| **Effects, atomically** | Replace `credential_hash` · clear `must_rotate` · **revoke the principal's other sessions** (a rotation is a response to possible compromise; leaving old sessions live defeats it). One transaction: a partial rotation that clears the flag without replacing the hash is the worst outcome |
| **`I6`** | Neither credential is returned, logged, audited, or persisted anywhere outside the password-hash operation. The audit line records *that* a rotation happened, by whom and when — never a value, never a hash |
| **Still available under `must_rotate`** | `POST /cp/auth/logout` and `GET /cp/auth/identity`. Everything else answers the rotation-required refusal |
| **Response** | `204`, and the session stays valid (the *other* sessions are the ones revoked). A fresh CSRF token is issued with it |

**The complete bootstrap flow, which plan 007 must test end to end:** installer seeds the principal with
`must_rotate` → `POST /cp/auth/login` (no CSRF token required; `Origin` validated) → session + CSRF cookies issued
→ any other endpoint answers the rotation-required refusal → `POST /cp/auth/credential` with CSRF and the current
credential → flag cleared, other sessions revoked → the API opens.

**Negative cases that are part of the contract:** rotation without CSRF → refused · rotation with a wrong current
credential → refused, and the lockout counter moves · rotation to the *same* credential → refused · login with a
foreign `Origin` → refused · any non-exempt endpoint while `must_rotate` → rotation-required refusal, **not** `403`
(the principal is authorized; it is the credential that is provisional).

### 4.3 The identity endpoint's authorization — ✅ ruled

✅ **Ruled by the owner, 2026-09-16: `GET /cp/auth/identity` requires *authentication only*.** No verb is needed to
read the **current** principal — its identity or its own effective permissions. `authz.introspect.self` governs the
Admin **access-review** screen instead, and `authz.introspect.any` still governs reading *another* principal's
(§5.2 row 20).

**Why that way**, recorded because the alternative is superficially tidier: requiring the verb means a role created
without it renders an **empty permission overlay for a principal who can nonetheless act**. The console would show
no actions while the server authorized them — a failure mode worse than a slightly coarse verb, and one an operator
cannot diagnose from the UI.

**Three consequences for implementers:**

| | |
|---|---|
| The endpoint's check | authentication, and nothing else. It is `none:authenticated` in the OpenAPI |
| Under `must_rotate` | **reachable** — via `LocalOperation.READ_OWN_IDENTITY`, which is why `MustRotateGate` carries three operations rather than two |
| `permissions[]`'s contents | still only members of the ratified **38** (the amendment's §6 item 1). Reading them needs no verb; *having* them still does |

---

## 5. Authorization — every endpoint, its permission, and its resource scope

**This table is the artifact `REQ-006`'s acceptance clause requires and that nothing else in the repo carries.**

Reading the columns:

- **Verb** — the permission `authorizationService.require(...)` is called with. Only members of the **ratified 38**
  may appear: [`D10-G2-APPROVAL-2026-09-15.md`](../../D10-G2-APPROVAL-2026-09-15.md) §1.1 (rows 1–35) and §1.7
  (rows 36–38: `instance.stop`, `audit.view`, `user.edit`).
- **Who holds it** — §1.8's three built-in roles: **Administrator** (all 38, bootstrap/emergency only), **Viewer**
  (8 read verbs), **Operator** (19). Everything outside Operator's 19 is Administrator-only, and §1.8 records **five**
  operational consequences — including that **an Operator cannot open a live chart tail** (no `mesh.stream.grant`)
  and **cannot stop an instance** (`instance.stop` is Administrator-only).
  ⚠️ *Pointer, 2026-10-05 (plan 011 P11-0.1; the text above is unedited, `I3`):* **superseded in part by `OR-80`** (owner ruling
  M-Q16) — the built-in Operator **also** holds `metrics.export` and `metrics.forceRaw` (21, not 19), and both leave the
  Administrator-only list. `OR-80` records this pointer as not yet written; the `D10` approval's own pointer is at its
  limitation 4.
  ⚠️ *Pointer, answered 2026-10-07 by `OR-121`:* the verb list above is no longer 38 — [`D10-G2-APPROVAL-2026-09-15.md`](../../D10-G2-APPROVAL-2026-09-15.md) §1.9 adds `instance.restart.overrideSafety`, `instance.undeclare` and three more (rows 39–43, the catalog becomes 43 in plan 012 T1), and the Operator also holds `instance.stop` (it was Administrator-only here). Forward pointer only; the text above is unedited (`I3`).
- **Scope** — the resource the decision is made *on*. Bindings are scope-evaluated with **downward inheritance**
  (approval §1.2): a grant at `[org, unit, env]` reaches every instance in that environment and **nothing** in a
  sibling environment. `install` means the decision is made at the install root.
- **Basis** — `A` = fixed by the approved catalog's own semantics. `P` = **proposed here**, ratifies with plan
  007's OpenAPI review. `⛔` = **cannot ship**: it needs a verb the approval did not grant.
- **State** — `impl` = implemented somewhere in the tree · `⛔` = blocked (no approved verb). For planned rows the
  column names **the plan that ships the endpoint**, which is always plan 007 or later, because **plan 006 builds
  no HTTP at all** (*"no controller, no `@RestController`, no filter"*). Where plan 006 supplies the *decision
  logic* behind a row, the cell reads `007 (006 logic)`.

### 5.1 Boot — static, outside `apiBasePath`

| # | Operation | Verb | Scope | Basis | State |
|---|---|---|---|---|---|
| 0 | `GET /config.json` | *none* — unauthenticated static boot document; it is what **supplies** `apiBasePath`, so its own path is fixed | — | A | impl (served from `frontend/public/`) |

### 5.2 Control plane — `/api/cp`

| # | Operation | Verb | Scope | Basis | State |
|---|---|---|---|---|---|
| 1 | `GET /auth/identity` | ✅ ***authentication only*** — owner ruling, 2026-09-16: **no verb is required to read the current principal**, including its own effective permissions. `authz.introspect.self` governs the Admin access-review screen instead. Reachable under `must_rotate` via `LocalOperation.READ_OWN_IDENTITY` | self | A | 007 (006 logic) |
| 2 | `POST /auth/login` | *none* (it establishes the session) + CSRF + lockout | — | A | 007 (006 logic) |
| 3 | `POST /auth/logout` | *authentication only* + CSRF. **Available while `must_rotate` is set** | self | A | 007 (006 logic) |
| 3a | **`POST /auth/credential`** (rotate own credential — §4.2) | ⛔ **`LocalOperation.ROTATE_OWN_CREDENTIAL`, not a catalog verb** + CSRF + current-credential verification. **Available while `must_rotate` is set** — it is what clears it | self | A | 007 (006 logic) |
| 4 | `GET /home/summary` | `solution.view` **and** `instance.view`; results filtered to the caller's scopes | organization ↓ | P | plan 007 |
| 5 | `GET /home/attention` | `health.view` **and** `instance.view`; filtered | organization ↓ | P | plan 007 |
| 6 | `GET /solutions` | `solution.view` | organization ↓ | A | plan 007 |
| 7 | `POST /solutions/load` | `package.load` (high-consequence) | ✅ **organization** — owner ruling, 2026-09-16 | A | plan 007 |
| 8 | `GET /hierarchy/organizations` | *authentication only*; returns every scope **prefix-comparable with a held grant in either direction** — at or below it, **or an ancestor of something held**. ⚠️ Filtering to "only scopes the caller holds a binding in" is the bug `SM-D33` warns about twice: a principal granted at `[org, unit, dev]` would `404` on its own parent unit and could not use this cascade at all | derived | P | 007 (006 logic) |
| 9 | `GET /hierarchy/organizations/{organization}/sub-orgs` | as row 8 — **including the ancestor direction** | organization | P | 007 (006 logic) |
| 10 | `GET …/sub-orgs/{subOrganization}/environments` | as row 8 — **including the ancestor direction** | unit | P | 007 (006 logic) |
| 11 | `GET /targets` | `target.view` | organization ↓ | A | plan 007 |
| 12 | `GET /admin/permission-catalog` | `role.view` | install | P | ⚠️ **no plan yet** — 007 (006 logic) supplies the decision logic; the Admin surface is explicitly **out of** plan 007's scope (the delivery sequence's W3 ships no principal or role-binding endpoint), so these await an Admin plan |
| 13 | `GET /admin/roles` | `role.view` | install | A | ⚠️ **no plan yet** — 007 (006 logic) supplies the decision logic; the Admin surface is explicitly **out of** plan 007's scope (the delivery sequence's W3 ships no principal or role-binding endpoint), so these await an Admin plan ⚠️ **Answered 2026-10-09** (forward pointer; the cell and the scope column are unedited, `I3`): built by plan 013 T3.2 as `listRoles`, **evaluated at the organization, not at install** ([`console.yaml`](../../../../contracts/openapi/console.yaml); `OR-125` (6) confirmed it as built). This design reads this row's `install` as organization: a principal belongs to exactly one organization, so an organization-bound Administrator administers its own organization's users and the install-bound bootstrap administrator its own (D-REG §4, R17; the T3.1 results, W3D-1, W3D-2). Usernames are unique per organization (`OR-125` (4)), so a login names the organization (optional on a one-organization install, `OR-131` Q3). |
| 14 | `POST /admin/roles` | `role.edit` | install | A | ⛔ **v1: built-in roles only.** Not a vocabulary gap — `sm_control.role` is `SELECT`-only to the CP runtime role by DCL, so custom roles are a later migration (approval §3 item 4) |
| 15 | `GET /admin/principals` | `user.view` | install ↓ | A | ⚠️ **no plan yet** — 007 (006 logic) supplies the decision logic; the Admin surface is explicitly **out of** plan 007's scope (the delivery sequence's W3 ships no principal or role-binding endpoint), so these await an Admin plan |
| 16 | `GET /admin/scope-tree` | `role.assign`; the tree is pruned to the caller's own scopes | derived | P | ⚠️ **no plan yet** — 007 (006 logic) supplies the decision logic; the Admin surface is explicitly **out of** plan 007's scope (the delivery sequence's W3 ships no principal or role-binding endpoint), so these await an Admin plan |
| 17 | `GET /admin/assignments` | `role.view` | install ↓ | P | ⚠️ **no plan yet** — 007 (006 logic) supplies the decision logic; the Admin surface is explicitly **out of** plan 007's scope (the delivery sequence's W3 ships no principal or role-binding endpoint), so these await an Admin plan ⚠️ **Answered 2026-10-09** (forward pointer; the cell is unedited): built by plan 013 T3.2 as `listAssignments`, `role.view` evaluated at the organization (see row 13's pointer). |
| 18 | `POST /admin/assignments` | `role.assign` — ⚠️ **and the caller must hold it at the scope being granted at**, or a unit admin escalates to the organization | **the granted scope** | A | ⚠️ **no plan yet** — 007 (006 logic) supplies the decision logic; the Admin surface is explicitly **out of** plan 007's scope (the delivery sequence's W3 ships no principal or role-binding endpoint), so these await an Admin plan ⚠️ **Answered 2026-10-09** (forward pointer; the cell is unedited): built by plan 013 T3.2 as `grantAssignment`; `role.assign` is evaluated at the granted scope (`x-sm-scope: named`), and the Administrator role is refused `422`. |
| 19 | `DELETE /admin/assignments/{assignmentId}` | `role.assign`, at the assignment's own scope | the assignment's scope | A | ⚠️ **no plan yet** — 007 (006 logic) supplies the decision logic; the Admin surface is explicitly **out of** plan 007's scope (the delivery sequence's W3 ships no principal or role-binding endpoint), so these await an Admin plan ⚠️ **Answered 2026-10-09** (forward pointer; the cell is unedited): built by plan 013 T3.2 as `revokeAssignment`; an Administrator binding is never revoked over the API (`409`). |
| 20 | `GET /admin/effective-permissions` | `authz.introspect.any` — or `authz.introspect.self` when the subject **is** the caller | subject's scopes | A | ⚠️ **no plan yet** — 007 (006 logic) supplies the decision logic; the Admin surface is explicitly **out of** plan 007's scope (the delivery sequence's W3 ships no principal or role-binding endpoint), so these await an Admin plan |
| 21 | `GET /admin/users` | `user.view` | install ↓ | A | ⚠️ **no plan yet** — 007 (006 logic) supplies the decision logic; the Admin surface is explicitly **out of** plan 007's scope (the delivery sequence's W3 ships no principal or role-binding endpoint), so these await an Admin plan ⚠️ **Answered 2026-10-09** (forward pointer; the cell and the scope column are unedited, `I3`): built by plan 013 T3.2 as `listUsers`, **evaluated at the organization, not at install** ([`console.yaml`](../../../../contracts/openapi/console.yaml); `OR-125` (6) confirmed it as built). This design reads this row's `install ↓` as organization: a principal belongs to exactly one organization, so an organization-bound Administrator administers its own organization's users and the install-bound bootstrap administrator its own (D-REG §4, R17; the T3.1 results, W3D-1, W3D-2). Usernames are unique per organization (`OR-125` (4)), so a login names the organization (optional on a one-organization install, `OR-131` Q3). |
| 22 | `POST /admin/users` | `user.create` | install ↓ | A | ⚠️ **no plan yet** — 007 (006 logic) supplies the decision logic; the Admin surface is explicitly **out of** plan 007's scope (the delivery sequence's W3 ships no principal or role-binding endpoint), so these await an Admin plan ⚠️ **Answered 2026-10-09** (forward pointer; the cell and the scope column are unedited, `I3`): built by plan 013 T3.2 as `createUser`, **evaluated at the organization, not at install** ([`console.yaml`](../../../../contracts/openapi/console.yaml); `OR-125` (6) confirmed it as built). This design reads this row's `install ↓` as organization: a principal belongs to exactly one organization, so an organization-bound Administrator administers its own organization's users and the install-bound bootstrap administrator its own (D-REG §4, R17; the T3.1 results, W3D-1, W3D-2). Usernames are unique per organization (`OR-125` (4)), so a login names the organization (optional on a one-organization install, `OR-131` Q3). The user is created `local` with a provisional credential and must rotate it at first sign-in. |
| 23 | `PATCH /admin/users/{id}` | ✅ **`user.edit`** for profile/login fields (HIGH) **and `user.deactivate`** for a status change (HIGH) — two verbs, deliberately separate, so an operator who may correct an email cannot end an account | install ↓ | A | ⚠️ **no plan yet** — the Admin surface is out of plan 007's scope. Both approved 2026-09-16; open item 39f closed ⚠️ **Answered 2026-10-09** (forward pointer; the cell and the scope column are unedited, `I3`): built by plan 013 T3.2 as two operations, `deactivateUser` (`user.deactivate`) and `resetUserCredential` (`user.edit`), **evaluated at the organization, not at install** ([`console.yaml`](../../../../contracts/openapi/console.yaml); `OR-125` (6) confirmed it as built). This design reads this row's `install ↓` as organization: a principal belongs to exactly one organization, so an organization-bound Administrator administers its own organization's users and the install-bound bootstrap administrator its own (D-REG §4, R17; the T3.1 results, W3D-1, W3D-2). Usernames are unique per organization (`OR-125` (4)), so a login names the organization (optional on a one-organization install, `OR-131` Q3). The contract maps one operation to one verb, so the single `PATCH` of this row became two routes. |
| 24–33 | `GET/POST/PATCH /admin/groups…`, `…/deactivate`, `…/reactivate`, `…/members…`, `…/assignments` (**10 operations**) | would need `group.view` · `group.create` · `group.edit` · `group.deactivate` · `group.member.manage` | — | ⛔ | ⛔ **BLOCKED on this baseline — none of the five `group.*` verbs is in the approved catalog** (approval §3 item 3, §6; open item **39c**). ⚠️ **Flagged, not resolved (`I4`):** `origin/dev/frontend` asserts a **`D18` 🔒** that grants exactly these five, and its own as-built REST document says *"All 35 operations are `grantable`, ratified as `D18` 🔒 (2026-09-04)"*. This baseline does not corroborate that lock (open item **34**), so the stricter reading controls and these ten operations return `501`. ⚠️ `POST …/{id}/reactivate` (op 29) has **no verb even in the console's own catalog** — `catalog.ts` carries `group.deactivate` only — so a sixth verb surfaces whenever the `group.*` question is answered |

### 5.3 Data plane — `/api/dp`

| # | Operation | Verb | Scope | Basis | State |
|---|---|---|---|---|---|
| 34 | `GET /solutions/{solutionId}/instances` | `instance.view` | solution × caller's scopes | A | plan 007 |
| 35 | `POST /solutions/{solutionId}/instances` (declare) | `instance.create`; **plus `instance.bindTarget` and `target.use`** when the request binds a Target — the two-sided check `D10` 🔒 already required | environment (+ the Target's `ownership_scope`) | A | plan 007 |
| 36 | `GET /instances/{instanceId}` | `instance.view` | instance | A | plan 007 |
| 37 | `DELETE /instances/{instanceId}` (undeclare) | ⚠️ **no verb exists.** `instance.create` is not a delete; the catalog has no `instance.delete` / `instance.undeclare` | instance | ⛔ | ⛔ **BLOCKED — routed**: approval §5 item 3, open item **39d** ⚠️ **Answered 2026-10-07** (forward pointer; the cell is unedited): `instance.undeclare` is catalog row 39 ([`D10-G2-APPROVAL-2026-09-15.md`](../../D10-G2-APPROVAL-2026-09-15.md) §1.9, `OR-121`, `OR-122`), `DELETE /dp/instances/{instanceId}?workload=leave-running` — built by plan 012 T5; not served until then |
| 38 | `GET /instances/{instanceId}/designer` | `config.view` | instance | A | plan 007 |
| 39 | `POST /instances/{instanceId}/config` (save/stage) | `config.edit` | instance | A | plan 007 |
| 40 | `GET /instances/{instanceId}/config/history` | `config.view` — ⚠️ `I6`: history must be redacted server-side | instance | A | plan 007 |
| 41 | `POST /instances/{instanceId}/config/secrets/{fieldKey}` | `config.edit` — ⚠️ **never returns a resolved secret value** (`I6`); stores a `secretRef` | instance | A | plan 007 |
| 42 | `POST /instances/{instanceId}/config/accept-runtime` | `config.edit` **and** `config.overrideWarning` — accepting observed runtime drift as intended is an override, not an edit | instance | P | plan 007 |
| 43 | `POST /instances/{instanceId}/config/restore-runtime` | `config.apply` — it writes to the host | instance | P | plan 007 |
| 44 | `POST /instances/{instanceId}/apply` | `config.apply`; **plus `instance.restart` whenever the chosen strategy restarts a replica**. ⚠️ `restart-all` and `rolling-no-validation` are **not selectable** — no strategy-assertion verb exists (approval §3 item 2; open item 39b). `scheduled` is out of release. ⚠️ **And `rolling-health-gated` is not selectable for Cache**, the v1 integration target: open item 15 measured that `active_standby` makes it **unsafe, not merely unavailable** — a standby reports `health: UP` while processing nothing, so the gate passes a replica that took no traffic. It is admissible only for an MI whose package declares an observable leader/active signal. **So v1's effective answer for Cache is `manual` alone** ⚠️ **Answered 2026-10-07 for the strategy assertion** (forward pointer; the cell is unedited): `restart-all` and `rolling-no-validation` become selectable with `instance.restart.overrideSafety` (§1.9 row 40) beside the verbs above, served by plan 012 T2; `scheduled` is built by plan 012 T7 (`OR-121` `OPS-Q9` Sch-2) | instance | A | plan 007 |
| 45 | `GET /instances/{instanceId}/apply/observe` | `config.view` | instance | A | plan 007 — **and it becomes a first-class caller**, see §9 |
| 46 | `POST /instances/{instanceId}/apply/rollback` | `config.rollback` (+ `instance.restart` if it restarts) ⚠️ **2026-10-07** (forward pointer): plus `instance.restart.overrideSafety` for `restart-all`/`rolling-no-validation` (§1.9 row 40, plan 012 T2) | instance | A | plan 007 |
| 47 | `POST /instances/{instanceId}/actions/{actionKey}` | ⚠️ **package-declared actions have no verb.** `config.apply` is the nearest, but an action is not necessarily a config apply | instance | ⛔ | ⛔ **BLOCKED — routed**: approval §5 item 3, open item **39e** |
| 48 | `GET /instances/{instanceId}/health` | `health.view` | instance | A | plan 007 |
| 49 | `GET /instances/{instanceId}/config-ladder` | `config.view` | instance | P | plan 007 |
| 50 | `GET /instances/{instanceId}/log-levels/{component}` | `logs.read` | instance | A | plan 007 |
| 51 | `POST /instances/{instanceId}/log-levels/{component}` | `logs.setLevel` (high-consequence, audited — `UI-D10`) | instance | A | plan 007 |
| 52 | `GET /instances/{instanceId}/logs/{component}` | `logs.read` | instance | A | plan 007 |
| 52a | **`POST /instances/{instanceId}/stop`** — ✅ **authorizable and OWNED** | **`instance.stop`** (HIGH, audited, idempotent; **Administrator-only** per §1.8) | ⚠️ **Authorized at the owning INSTANCE scope.** The *operation* targets an instance **or** one placement — scope-granular, as the engine executes it — but ⛔ **placement is not a grantable level** (**OR-16**), so a placement-targeted stop checks the instance's `ScopePath`, plus any required Target permission | A | ✅ **[plan 007 Task 6b](../../../plans/backend/007-production-api-and-event-driven-walking-skeleton.md), assigned by ruling OR-18.** ⚠️ **An earlier revision of this row said *"no plan yet"*, which was true until OR-18.** Body: `{placementId}` **optional — absent means instance-wide** (`PlacementId.INSTANCE_WIDE`, the nil-UUID sentinel); ⛔ **no `generation`, no `strategy`** — plan 005's `ApplyCommand` rejects both on a stop. **Instance-wide means every LIVE placement of the instance** (read from the placement registry, `BE-D46`; plan 008 S3), under one `applyId`; a retired placement is not addressed, and two placements whose containers share a name are both stopped because the apply journal is placement-aware (`BE-D44`; plan 008 S2). *Described here as the plan's end state; it is true as of plan 008 S2 and S3 (landed 2026-10-08; their Docker-gated tests are written and not yet run, see plan 008's Results).* ⚠️ **Answered 2026-10-07 by `OR-121`** ([`D10-G2-APPROVAL-2026-09-15.md`](../../D10-G2-APPROVAL-2026-09-15.md) §1.9): the built-in **Operator also holds `instance.stop`** (plan 012 T1); forward pointer only, the text above is unedited. |
| 52b | **`POST /instances/{instanceId}/placements`** (`addPlacement`) | **`instance.bindTarget` at the instance, and `target.use` at the target the body names** — `D10`'s two-sided check (`OR-16`; ruled in `OR-121`, plan 008 OQ-8.3s (i): an instance-scoped grant suffices there and does not reach a sibling instance). Not counted against the instance quota (OQ-8.8 (a)) | instance (+ the Target's `ownership_scope`) | A | ✅ **plan 008 S3** (`BE-D47`). Writes no host and deploys nothing (`OR-29`, `I1`). The probe and the metrics definitions come from the instance's DECLARED package version, never the body. Refused `409` for an instance whose package declares push (`solace_event`) metrics (OQ-8.9 (a)) and while an instance-wide request is in flight. The new placement starts unconverged: the ladder reads `rendered` and `instance.config-state` is announced. The actor is recorded as `placement.created_by` (`OR-125` (1), `sm_data` V026). Proved in process by `AddPlacementEndpointIT` and `MultiPlacementEndToEndIT` (written, not yet run: Docker was unavailable); the live gate is owed |
| 52c | **`DELETE /instances/{instanceId}/placements/{placementId}?workload=leave-running`** (`retirePlacement`) | **`instance.bindTarget` at the instance** — the verb that adds a placement also removes one; `target.use` is not asked (removal touches no target) | instance | A | ✅ **plan 008 S3** (`BE-D53`, ruled in `OR-121` under plan 012 `OPS-Q12` R-1). ⛔ Never touches the host: the container keeps running, unmanaged by SM, and the response says `workload: left-running`; the query parameter is **required** (`400` otherwise). Refused `409` for the instance's last live placement and while work naming it is in flight. A retired placement is not addressed by a later instance-wide stop (`BE-D46`). The actor is recorded as `placement.retired_by` (`OR-125` (1), `sm_data` V026). Proved in process by `AddPlacementEndpointIT`, `RetirePlacementIT` and `MultiPlacementEndToEndIT` (written, not yet run) |

### 5.4 Console composition — `/api/console/v1`

✅ **The module that serves this prefix is settled: `console-api`** (ruling **OR-10**, 2026-09-16 — open item 21
**closed**; see §13.3). It depends on CP and DP **service interfaces only**, owns no database and performs no
cross-schema SQL. ⚠️ The *surface* is still unbuilt and belongs to
[plan 009](../../../plans/backend/009-console-api-and-monitoring-replacement.md).

⚠️ *Pointer, 2026-10-05 (plan 011 P11-0.1; the text above is unedited, `I3`):* **superseded in part by `OR-78`** (owner ruling
M-Q11) — the `/api/console/v1` metrics routes are hosted by **`console-gateway`**, `OR-10`'s composition module, not a new
`console-api` module, and the replacement plan is [plan 011](../../../plans/backend/011-monitoring-backend.md) (a **draft, not
approved**), which supersedes plan 009 in part. Plan 009's Tasks 1 and 2 are dropped.

| # | Operation | Verb | Scope | Basis | State |
|---|---|---|---|---|---|
| 53 | `GET /capabilities` | *authentication only* | install | P | plan 007+ |
| 54 | `GET /entity-types` | `metrics.query` | organization ↓ | P | plan 007+ |
| 55 | `GET /entity-types/{entityType}/instances` | `metrics.query` **and** `instance.view` | organization ↓ | P | plan 007+ |
| 56 | `GET /metrics/catalog` | `metrics.query` | organization ↓ | P | plan 007+ |
| 57 | `GET /labels/{labelKey}/values` | `metrics.query` | organization ↓ | P | plan 007+ |
| 58 | `POST /query/range` | `metrics.query`; **plus `metrics.forceRaw`** when the request forces the raw tier | organization ↓ | A | plan 007+ |
| 59 | `POST /query/instant` | `metrics.query` | organization ↓ | A | plan 007+ |
| 60 | `GET /export/range` | `metrics.export` (high-consequence — an exfiltration channel, open item 28) | organization ↓ | A | plan 007+ |
| 61 | `POST /metrics/catalog/{name}/type` | `metric.declare` | organization | A | plan 007+ |
| 62 | `GET /dashboards/{dashboardId}` | `dashboard.view` | dashboard's scope | A | plan 007+ |
| 63 | `POST /dashboards/{dashboardId}/widgets` | `dashboard.edit` | dashboard's scope | A | plan 007+ |
| 64 | `PUT /dashboards/{dashboardId}/layout` | `dashboard.edit` | dashboard's scope | A | plan 007+ |
| 65 | `PATCH /dashboards/{dashboardId}/widgets/{widgetId}` | `dashboard.edit` | dashboard's scope | A | plan 007+ |
| 66 | `POST /dashboards/{dashboardId}/reset` | `dashboard.reset` | dashboard's scope | A | plan 007+ |

### 5.5 Operations the approved catalog implies but the as-built surface does not have

The three mesh verbs are in the approved catalog and have **no endpoint** in the 66. Plan 007 adds them; their
shapes are `BE-D14` §4.1's to fix.

| Operation | Verb | Notes |
|---|---|---|
| Mint a session mesh ticket | `mesh.ticket.mint` | Returns the ticket **and a server-computed `renewAfter`** — the client schedules against that value, never derives policy from `exp` (`BE-D14` §4.2) |
| Request a chart-tail stream grant | `mesh.stream.grant` | Requires a passing `DM-D1` query; accumulates onto the session ticket's `grant_id` set — **no second broker session** (`BE-D14` §4.5) |
| Revoke another session's stream grant | `mesh.stream.revoke` | High-consequence, operator-on-operator. **Effect, both bounds:** the DP stops publishing that series at its next publish decision (effectively immediate); the *session's* mesh access as a whole is TTL-bounded at ≤ 15 min |
| Create a Target | `target.create` | ⚠️ **Catalog member #24 with no endpoint anywhere in the 66** — §5.2 row 11 is `GET /targets` only. Either the Targets screen is read-only in v1 or the operation is missing. Routed ⚠️ **Answered 2026-10-07 to 2026-10-09** (forward pointer; the cell is unedited): `target.create` now has endpoints — `POST /dp/host-key-scans` (`scanHostKeys`) and `POST /cp/targets` (`registerTarget`), built by plan 013 T1.4, with `getTargetConnection`, `editTargetConnection`, `rotateTargetCredential`, `testTargetConnection` and `retireTarget` (T1.5; `target.edit` and `target.use`). The Targets screen is not read-only in v1. |
| Read the unioned audit log | ✅ **`audit.view`** — STANDARD, **scope-filtered and server-redacted** | `BE-D15` §5.3's `/api/console/v1/audit`, composing both planes. ✅ Verb approved 2026-09-16; ⚠️ **Administrator-only** per §1.8, so audit review is an administrative act in v1. Redaction happens **server-side before serialising** (`I6`), never in the UI |

### 5.6 Five rules that bind every row above

1. **One enforcement point.** `authorizationService.require(principal, verb, resource)`. No controller-local role
   check, no role-encoding annotation, no second path, no production super-admin bypass.
2. **Scope comes from the resource, never the request.** The caller may name a resource; the *authority* is derived
   from the session and the binding tree (`I5`).
3. **A `403` is a decision, not an error to retry.** Mutations are never retried and never optimistic (`FE-D20`).
4. **`404` vs `403` is discriminated deliberately**, and a cross-organization resource is refused **before**
   bindings are consulted.
5. **Blocked rows must return `501`, not `403`.** A blocked operation is not an authorization failure — it is an
   operation that does not exist yet, and the client explicitly excludes `501` from retry. Returning `403` would
   tell an operator they lack a permission that nobody can be granted.

---

## 6. Events — the v1 catalog, exactly as the contract defines it

⚠️ **Update 2026-10-05:** the seventh event's contract landed at `878b50c` (plan 010 T0.2): `instance.apply-outcome`, topic `sm/v1/{tenant}/notify/instance/{instanceId}/apply-outcome/{applyId}` per `OR-58`, payload `{applyId, state}`, eight outcome states; the "NOT landed" wording in the rest of this section and in §9 is superseded, and a handler may now be written (REST stays the outcome).

Six events today. ⚠️ **A seventh — `instance.apply-outcome` — is approved (OR-4) and its contract has NOT landed;
until it does the catalog on the wire is these six** (§9). **The catalog is closed**: adding one means a design row, a schema branch, a fixture, a manifest entry,
a topic rule and (for a new constraint) a negative — a reviewed single-writer change, never a drive-by.

**Envelope fields** (`additionalProperties: false`): `eventId` · `occurredAtUtc` · `producer` · `tenant` ·
`entity` · `stateVersion` · `event` · `payload`. ⚠️ **`event` is a CLOSED enum** and `producer` is closed to
`['cp-core','dp-core']` — so **adding an event means extending `envelope.schema.json`'s `event` enum**, not merely
adding a `oneOf` branch (§16.1's artifact list).

⚠️ **`entity.kind` is a `const` per branch, and a handler cannot discriminate branches without it** — which is why
it is a column below rather than a footnote. A producer that emits the wrong `kind` for its `event` is rejected by
the gate.

| `event` | `entity.kind` (`const`) | `entity` identity | `payload` | Topic | Scope rule |
|---|---|---|---|---|---|
| `instance.health` | `placement` | `kind`, `instanceId`, **`placementId` required** | `state` (SCREAMING health rollup + the four `NOTFOUND` sub-states as **distinct** keys) | `sm/v1/{tenant}/notify/instance/{instanceId}/health` | **placement-scoped** — a dual-bound instance's two sites never overwrite each other |
| `instance.rollout` | `placement` | `kind`, `instanceId`, **`placementId` required** | `applyId`, `replicaId`, `step` ∈ `staged \| restart-requested \| restart-observed` | `sm/v1/{tenant}/notify/instance/{instanceId}/rollout/{applyId}` | **placement-scoped** |
| `instance.config-state` | `instance` | `kind`, `instanceId`; **`placementId` forbidden** | `reached` | `sm/v1/{tenant}/notify/instance/{instanceId}/config-state` | deliberately **coarse instance-scoped** invalidation |
| `instance.version` | `instance` | `kind`, `instanceId`; **`placementId` forbidden** | `state` (`VersionState` from `contracts/view-models`) | `sm/v1/{tenant}/notify/instance/{instanceId}/version` | coarse instance-scoped |
| `metrics.quarantine` | `instance` | `kind`, `instanceId` — ⚠️ **`placementId` is neither required nor forbidden here**: unlike config-state and version, this branch carries no `not: {required: [placementId]}`, so the gate would accept one. Treat it as instance-scoped and never emit a `placementId`; the asymmetry rides the same amendment | `count` | `sm/v1/{tenant}/notify/instance/{instanceId}/quarantine` | instance-scoped. ⚠️ **Known gap 1:** the topic is not in `BE-D4`'s decision text — deriver, manifest and AsyncAPI carry it; the text needs the amendment |
| `catalog.package` | `solution` | `kind`, `solution`; **`instanceId` and `placementId` both forbidden** | `solution`, `packageVersion` | `sm/v1/{tenant}/notify/catalog/package` | solution-scoped. ⚠️ **Known gap 4:** `packageVersion` is declared `{}` — any JSON type validates in Python while Java rejects a null-valued required key. **Do not "fix" either side**; the amendment settles it |

### 6.1 `stateVersion` is the only ordering authority — and REST must carry it

⛔ **Normative, and the thing an earlier draft of this document left out entirely.** Every REST resource whose
entity emits notify events **must return that entity's current `stateVersion`**, or §2 rule 7, §6.1, §7 and §8 are
unimplementable — an event says "version 41" and the client has nothing to compare it against.

| Rule | Statement |
|---|---|
| **Field name** | `stateVersion`, a JSON integer, at the **top level** of a single-entity response |
| **Collections** | **per item**, on each element — never one version for the collection. A collection's elements are separate entities with separate monotonic counters |
| **Which endpoints** | every one whose entity appears in §6's table: instance detail and list, designer, health, config-ladder, apply observe, and the solutions list (for `catalog.package`) |
| **Who defines it** | the OpenAPI (plan 007 Task 1); this rule is what its schemas must satisfy |
| **IEEE-754** | the `Long.MAX_VALUE` boundary is a routed watch item for whoever generates the TypeScript types ([`backend-consumption.md`](backend-consumption.md) §3) |

`Identity` is authentication state, not an entity in §6's notify table. Its login and self-read responses have no
`stateVersion`; requiring one would invent an ordering source the CP does not publish.

#### The watermark's key — not the query key

⛔ **The held version is keyed by `(event, entity identity)`, and that key is *not* the TanStack Query key.**
Getting this wrong produces a silent, permanent staleness bug rather than a visible one:

| Event | Watermark key |
|---|---|
| `instance.health`, `instance.rollout` | **`placementId`** — these are placement-scoped |
| `instance.config-state`, `instance.version` | `instanceId` |
| `catalog.package` | `solution` |
| **`instance.apply-outcome`** (approved; ⚠️ contract LANDED 2026-10-05 at `878b50c`) | **`placementId`** — placement-scoped |
| `metrics.quarantine` | `instanceId` |

**Why it matters, concretely — and ⚠️ note this describes the PRE-OR-6 address.** On today's gated taxonomy both
placements of a dual-bound instance publish `instance.health` on the *same* topic
(`sm/v1/{tenant}/notify/instance/{instanceId}/health` — no placement level in the address), and §7 tells the
handler to invalidate `['governance', instanceId, 'health']`, which is keyed by instance. ⚠️ **Once OR-6's taxonomy
lands (§11.2) placement-scoped facts gain their own `/placement/{placementId}/` level**, so the two placements stop
sharing a topic — but **the watermark key does not change**: it stays `placementId`, because the key is about
*entity identity*, not about how many addresses the facts arrive on. A client that
kept one watermark per *query key* would discard placement B's version 5 forever behind placement A's version 100
— exactly the overwrite that placement scoping exists to prevent. So: **N placement watermarks fan in to one
instance query.** Keep the versions per placement; invalidate per instance.


- Monotonic **per entity**, supplied by the producer, carried in the envelope.
- **Nothing sorts or discards by `occurredAtUtc`** (`B/D15` 🔒). That field is display and diagnostics only.
- A consumer holding state at version *v* **discards** any notice with `stateVersion ≤ v` and refetches on any
  notice with `stateVersion > v`.
- `stateVersion` is also the **backfill watermark**: after a resync, refetch and discard anything not newer.
- ⚠️ Routed watch item: the IEEE-754 `Long.MAX_VALUE` boundary lands on whoever generates the TypeScript types
  ([`backend-consumption.md`](backend-consumption.md) §3).

### 6.2 Identity scoping is a correctness rule, not a convention

A producer that emits `instance.rollout` **without** `placementId` is rejected by the gate — and if it were not,
two placements of one instance would overwrite each other in every live console. A producer that emits
`instance.config-state` **with** `placementId` is likewise rejected: a placement-scoped fact cannot honestly move
an instance-scoped ladder (`eda-walking-skeleton.md` §3.5's containment, §8 row 13).

---

## 7. Query invalidation — the table a notify handler implements

**Rule 2·6 in operational form.** A handler does exactly this: check `stateVersion`, then invalidate. It never
writes payload data into the cache.

| Event | Invalidate these query keys | Never |
|---|---|---|
| `instance.health` | `['governance', instanceId, 'health']` · `['instances', 'detail', instanceId]` · `['home', 'attention']` | write `payload.state` into the health query |
| `instance.config-state` | `['governance', instanceId, 'config-ladder']` · `['config', instanceId, 'designer']` | write `payload.reached` as the ladder |
| `instance.version` | `['instances', 'detail', instanceId]` · `['instances', 'list', solutionId]` (when known) | write `payload.state` as the observed version |
| `instance.rollout` | `['apply', instanceId, 'observe']` · `['apply', instanceId, 'outcome']` · `['config', instanceId, 'designer']` | treat `step: restart-observed` as "the apply succeeded" — **it is not a terminal outcome** (§9) |
| `catalog.package` | `['solutions', 'list']` | render `payload.packageVersion` as a typed version (Known gap 4) |
| **`instance.apply-outcome`** — ⚠️ **approved (OR-4); contract LANDED 2026-10-05 at `878b50c` (plan 010 T0.2), so a handler may now be written** | `['apply', instanceId, 'observe']` · `['apply', instanceId, 'outcome']` · `['config', instanceId, 'designer']` | render `payload.state` as the outcome. **REST is the outcome**; the notice only says *look again* |
| `metrics.quarantine` | ⚠️ **no per-instance key exists** — `queryKeys.metrics.catalog()` takes no `instanceId`. Invalidate the **`['metrics', 'catalog']` prefix** until plan 007 adds `metrics.catalogForInstance(instanceId)` | render `payload.count` as the catalog |

⚠️ **Every other key in this table resolves against `frontend/src/shared/api/queryKeys.ts` as written. The
quarantine row is the one that does not** — a real gap, not a typo.

Two structural notes the frontend needs:

- **`instanceId` → `solutionId` is not in the event.** A handler that wants to invalidate an instance *list* must
  resolve the solution from cached state or invalidate the list prefix. This is deliberate: the payload carries
  identifiers, not a graph.
- **Coarse is correct.** `instance.config-state` and `instance.version` are instance-scoped on purpose. Over-invalidation
  costs one REST read; under-invalidation renders a stale console.

---

## 8. Reconnect, renewal, missed events and backfill

```
                 ┌── onResync('connected') ── first connect
start(tickets) ──┼── onResync('renewed')   ── planned re-ticket (SDK replaces the session)
                 └── onResync('recovered') ── after expiry / disconnect / throttled-tab wake-up
                                   │
                                   ▼
             refetch active queries  →  discard notices ≤ held stateVersion
                                   │
                                   ▼
                  chart tails re-backfill from the watermark
```

| Situation | Behaviour |
|---|---|
| First connect | `onResync('connected')`; normal query load already happened over REST |
| Ticket renewal | The mint response carries a **server-computed `renewAfter`**; the client schedules against it. Renewal is a **planned reconnect** — the SDK replaces the session, in-flight messages are lost, charts re-backfill |
| Backgrounded tab misses the renewal window | Named, expected limit: throttled timers can miss it and the broker disconnects at `exp` **with no grace and no usable reason**. The wake-up lands in `onResync('recovered')` → fresh ticket → reconnect → REST backfill |
| Any disconnect | Bounded connect retries (`connectRetries: 0` plus app-level bounded retry) **with the degraded banner**. The SDK would otherwise retry a rejected login forever and tell the user nothing |
| Sequence gap in notices | There is no replay. The gap is healed by refetching from `stateVersion` |
| Notices arrive **out of order** | The final state is the **highest** `stateVersion`, not the last received; a notice at or below the held version is discarded without a refetch (§6.1). This is why ordering may never use `occurredAtUtc` |
| `AUTH_FAILED` / `401` from the broker | **One undifferentiated shape covering five causes** (expired · unknown key · bad signature · wrong audience · unreachable JWKS). The client must **not** render a cause it cannot know — it resyncs |
| `SUBSCRIBE_ACL_DENIED` | Actionable and distinguishable; surface it |
| Broker unreachable at all | → degraded mode (§10) |

⚠️ **Missed events are healed by reading, never by replay.** Any design that asks the broker for history is
outside this contract.

---

## 9. Terminal apply outcomes — an event contract that does not exist yet

**The v1 event catalog has no terminal-apply-outcome event.** `instance.rollout`'s `step` vocabulary is
`staged | restart-requested | restart-observed` — a *progress* ladder, not an outcome. Meanwhile `BE-D13`'s request
states include `APPLIED`, `STAGED`, `CONFIG_DIVERGED`, `APPLY_INDETERMINATE`, `FAILED_NO_EFFECT`, `STOPPED` and
`STOP_FAILED`. None has a wire notice.

⛔ **The answer is an amendment, not a permanent poll.**
[`BE-D4-AMENDMENT-PROPOSAL.md`](../../backend/BE-D4-AMENDMENT-PROPOSAL.md) proposes it (with the other seven
recorded gaps) and is **NOT approved**. Consequences, in order:

| | Target behaviour, once the amendment lands | Behaviour today |
|---|---|---|
| **How a completion reaches the console** | The DP publishes the terminal notice after commit; the handler **invalidates** the observation query; the console **refetches over REST** | The apply surface **polls** `…/apply/observe` |
| **What the indicator says** | session *live*, surface *live* | ⚠️ session ***live***, apply surface ***polled — awaiting event contract*** (§10.2). The session is genuinely live; it is the **surface** that is degraded, and collapsing the two either misreports the mesh or hides the degradation |
| **What the payload may contain** | ✅ **Ruled 2026-09-16 — a new `instance.apply-outcome` notice**, placement-scoped: `entity` carries `kind`, `instanceId` and **required `placementId`**; `payload` carries `applyId` and the terminal `state` key (copied from `BE-D13`'s request states); the envelope carries `stateVersion`. ⛔ **No error details** — no message, no remote error, no `ErrorClass` string, because a classifier can embed a host or container identifier (`I6`). A consumer that needs *why* reads the journal over REST | — |
| **What is authoritative** | **REST.** The event is a change notice; the outcome is read from the journal | REST |

Four rules that hold either way:

1. `POST …/apply` returns **`202 Accepted` with an `applyId`** and never the outcome — the engine is durable and
   asynchronous (§9.2).
2. **`restart-observed` is not success.** A handler that lands it and renders "applied" asserts an outcome the
   contract does not carry.
3. **No event payload becomes authoritative UI state** — that is rule 2·6, and it applies to the terminal notice
   exactly as to the other six.
4. **No new event is implemented before the `BE-D4` amendment is approved** — ⚠️ **and ruling the *shape* is not
   approving the amendment.** The schema branch, fixture, manifest entry, topic rule and negative are one reviewed
   single-writer change to `contracts/` that has not happened. Implementing ahead of it would put a hand-rolled
   event type on the wire before its contract, which is exactly what single-writer exists to prevent.

⚠️ **Acceptance consequence, stated plainly:** the event-driven apply walking skeleton **cannot be declared
complete** until the terminal event contract is approved and implemented. Plan 007 carries that as a prerequisite,
not as a footnote.

### 9.2 `202 Accepted` must be genuinely asynchronous

**Measured:** plan 005's `ApplyExecutor.execute(ApplyCommand)` performs admission **and** the remote SSH/Docker
work in one synchronous call and returns an `ApplyOutcome`. Wrapping a `202` around that method would hold the HTTP
request open for the entire remote operation — a `202` that lies.

So the contract is a **durable dispatcher**, and PostgreSQL is the queue:

| Rule | Statement |
|---|---|
| **Submission** | authorizes, validates, and persists the admission state (`REQUESTED`) — then **returns `202` + `applyId` before any `RuntimeAdapter` call** |
| **Execution** | a **bounded in-process worker** claims durable work from the apply journal and runs `ApplyExecutor → RuntimeAdapter`. One path, unchanged |
| **The queue** | **the apply journal itself.** PostgreSQL stays the only durable queue and the only truth. ⛔ No Kafka, no workflow engine, no in-memory-only queue |
| **Claiming** | concurrency-safe and crash-recoverable: two workers must never execute one request, and a claim lost to a crash must be reclaimable |
| **Recovery** | startup enumerates **every tenant** with non-terminal work — no hard-coded `TenantId` — and reuses plan 005's `resume` semantics. **Not a second state machine** |
| **Isolation** | one failed or poisoned request must not stop the others |
| **Location** | the worker is owned by the **production composition module** (§13.3), not by the HTTP layer and not by `dp-core`'s engine package |

**What the frontend sees, and why it matters here:** `202` means *accepted and durable*, not *done*. The apply
screen's state machine therefore starts at "accepted" and reaches a terminal state through §9's event (target) or
its degraded poll (today) — never from the submission response.

### 9.1 Error and indeterminate-outcome shapes

**One error body for every non-2xx**, and the UI switches on `code`, **never** on `message`:

```json
{ "code": "GENERATION_CONFLICT", "message": "Someone else saved a newer configuration.", "current": "gen-42" }
```

- `code` and `message` are read only when they are top-level **strings**; the whole object also becomes
  `ApiError.details`, so extra fields survive.
- A **non-JSON or empty** error body must not break the client. An empty `500` is safe, just uninformative.
- An **unknown** `code` renders the server's `message` verbatim; an unknown code with no message renders a
  generic string.

| Status | Client behaviour (binding on the server) |
|---|---|
| `200`/`201` | Parsed as JSON; **unknown fields ignored, never stripped** |
| `204` | No parse attempted |
| `400` | Inline validation failure; not retried; dialog stays open |
| `401` | Session-expired listener → `/login`; not retried; a **normal** answer |
| `403` | Not-authorized; not retried |
| `404` | Route-level not-found; error surface |
| `409` | Conflict → the conflict dialog; not retried; carries the current generation in `details` |
| `422` | Business refusal, rendered from the typed `code`; **never** retried |
| `5xx` | Retried — **queries only**, ≤ 3 attempts, 500 ms → 1 s → 2 s → 4 s (cap 5 s) |
| `501` | **Excluded from retry.** This is the status a §5-blocked operation returns |

**Indeterminate outcomes are outcome states, not HTTP errors.** `APPLY_INDETERMINATE`, `CONFIG_DIVERGED` and the
whole `VersionState` set — `OK`, `VERSION_DRIFT`, `VERSION_UNKNOWN`, `VERSION_UNVERIFIED`, from
`contracts/view-models/src/types.ts`; an earlier draft omitted `VERSION_UNVERIFIED` — arrive as typed fields on a
`200` — as an apply outcome `kind` or as
`ObservedState.versionState` — never as an error `code`. A server that returns `500 CONFIG_DIVERGED` has turned a
legitimate state into a fault.

⚠️ **A contract change plan 007 must make deliberately.** The as-built `POST …/apply` returns `200` with
`kind: "INDETERMINATE"` **always**, because open item 1 (no generation ordering, no idempotency key, no crash-safe
resume for `dp-remote`) was open when it was written. Open item 1's design now exists and is implemented
(`BE-D12`/`BE-D13`, plans 004–005), so `APPLY_INDETERMINATE` stops being the default answer and becomes one
terminal state among several. The endpoint therefore changes shape — `202 + applyId` — and
`CONFIRMED_BY_OPERATOR` remains **client-only**: a server must never send it.

---

## 10. Live versus degraded — what the operator sees

`LiveStateClient.state` is `'live' | 'degraded' | 'stopped'` and **must** drive a visible indicator.

| State | Meaning | Data path | Indicator |
|---|---|---|---|
| `live` | Mesh session up, ticket valid, subscriptions active | REST snapshots + event-driven invalidation | "Live" |
| `degraded` | Mesh unavailable (broker down, proxy down, bounded retries exhausted, ticket unobtainable) | REST **polling** at the `BE-D6` fallback interval | "Degraded — polling" **with the reason class**, never a silent fallback |
| `stopped` | Client not started, or the session ended | REST on navigation only | no live claim |

Rules:

- **Degradation is announced, never silent.** The banner is part of the contract.
- **Degraded is not broken.** Every authoritative value is still correct — it is *older*. Say "polling", not
  "error".
- **Recovery is automatic and announced** — `onResync('recovered')` + backfill, then the indicator returns to
  live.
- **The apply screen's poll (§9) does not make the *session* degraded.** It is a surface-level exception inside a
  live session — §10.2 is how the contract expresses that.

### 10.2 Surface-level liveness — a second, smaller indicator

⛔ **`LiveStateClient.state` is session-level and has no surface member, so a surface cannot be labelled through
it.** An earlier draft asked implementers to "distinguish the two" with a three-member vocabulary that cannot
express the distinction. The contract therefore adds a **separate, per-surface** signal, owned by the feature and
not by the mesh client:

```ts
type SurfaceLiveness = { surface: string; liveness: 'live' | 'polled'; reason?: string };
```

| Rule | Statement |
|---|---|
| **Independent of the session** | A surface may be `polled` while the session is `live`. The converse is not meaningful: if the session is `degraded`, every surface is polled |
| **Only a missing event contract may set it** | Not slowness, not a preference. Today exactly one surface qualifies — terminal apply outcomes — with `reason: 'awaiting-terminal-event-contract'` |
| **It must be visible** | Same obligation as the session indicator: a surface that polls says so. The copy names the surface, not the mechanism (*"Apply outcome: checking"*, not *"polling"*) |
| **It disappears, it does not get reinterpreted** | When the `BE-D4` amendment lands, the surface's `liveness` becomes `live` and this signal has no remaining user. It is not a general-purpose per-widget status field |

### 10.1 Chart tails

| Property | Contract |
|---|---|
| Tier | **Raw only**, and **range-ends-now only** (`BE-D6`). A historical range is a REST query, never a tail |
| Session | Many concurrent tails ride **one** broker session. `sub` names the session's branch; the DP publishes each granted series to `sm/v1/{tenant}/stream/{sub}/{seriesKey}` (`BE-D14` §4.5) |
| Grant | Per chart, per session, via `mesh.stream.grant` with a passing `DM-D1` query. A new grant refreshes the ticket's `grant_id` set — **no ACL change, no second session** |
| Start | **REST backfill first**, which establishes the watermark; then append |
| Append rule | Only points **newer than the watermark**. On every resync, **re-backfill** |
| Gaps | **Server-declared** (`DM-D16`). The client never infers a gap from missing points |
| Isolation | Session-to-session isolation is broker-enforced; **within** a session, only granted series are published to the branch — gateway/DP-enforced. The entitlement line is the gateway; the broker is the second line |
| ⚠️ Taxonomy rider | `BE-D4` names the substituted level `{grantId}`; under `BE-D14` §4.5 it carries the session's `sub`. A **level rename**, routed into the `BE-D4` amendment (§16.1) |

---

## 11. Isolation — one Message VPN per Organization, and the topic shape inside it

✅ **Ruled 2026-09-16 (OR-5/OR-6).** This replaces the organization-wide notify posture that `BE-D14` §4.4
proposed; ⛔ **organization-wide notify visibility is not acceptable.**

### 11.1 The isolation boundary

| | Ruling |
|---|---|
| **Default boundary** | **One Message VPN per Organization.** An organization is the unit of broker isolation, not a topic prefix inside a shared VPN |
| **Solace Cloud** | Strict isolation means **one broker service per Organization**, ⛔ **unless an explicit shared-service profile is approved.** A shared service is a decision, never a default |
| **Inside the VPN** | **Sub-organization and Environment isolation is enforced through topics and ACLs** — which is what §11.2's shape makes expressible |
| **What this dissolves** | Most of open item 32. Organizations do not share a VPN, so "multi-tenant composition of one substitution profile" is no longer the question — ⚠️ but the *lower-scope* ACL behaviour inside the VPN is now the question, and it is **unmeasured** (§11.3) |

### 11.2 The canonical topic shape

```
sm/v1/{organizationId}/{subOrganizationId}/{environmentId}/notify/
    solution/{solutionId}/instance/{instanceId}/{aspect}
```

**Placement facts extend it:** `…/instance/{instanceId}/placement/{placementId}/{aspect}`.

Three rules that are as load-bearing as the shape:

1. **Every level is an immutable id**, not a name (§0). A renamed environment must not change a topic.
2. **Organization-scoped facts may terminate earlier** — a fact that is genuinely about the organization stops at
   the organization level.
3. ⛔ **Never invent fake lower scopes to pad a topic.** Publishing an organization-scoped fact at
   `…/{subOrgId}/{envId}/…` with placeholder segments would make an ACL that grants one environment leak a fact
   that belongs to all of them. If a fact has no environment, its topic has no environment level.

⚠️ **This amends `BE-D4`'s taxonomy, and the `contracts/events/` change has NOT been made.** Today's gated taxonomy
is `sm/v1/<tenant>/notify/instance/<instanceId>/<aspect>` — **one** id level where the ruling has **three**.

✅ **It has an owner: [`BE-D4-AMENDMENT-PROPOSAL.md`](../../backend/BE-D4-AMENDMENT-PROPOSAL.md) gap 9**, whose
§3.1 lists all eight artifacts it moves. Two of those are closed enums that a shorter list would miss — the
envelope's **`event` enum** and AsyncAPI's **`aspect` enum** — and ⚠️ **there are TWO derivers**, the Java
`NotifyTopics` and the Python one in `validate-events.py`, which must agree byte-for-byte. The Python one's
path-traversal guard currently checks **one** id segment (*"a tenant of `acme/../evil`"*); under OR-6 it must cover
**three**.

⛔ **Until gap 9 lands, no producer may emit the new shape**, and no document may describe it as the one on the
wire.

### 11.3 ⛔ The broker ACL spike — matrix written, NOT executed

> *(Dated pointer, 2026-10-05, added by the plan 011 integrator hand-off: the spike has since run, in round 4 on 2026-09-30. Its result is [`spike-scope-acl-result.md`](../../backend/spike-scope-acl-result.md), which itself says "Nothing here declares item 32, 33 or 35 closed, and `BE-D5` is not ratified". The text below is the matrix as written before the run.)*

**What is decided** is the architecture above. **What is unmeasured** is whether a Solace ACL can actually enforce
sub-organization and environment confinement on the §11.2 shape for a subscriber whose identity carries only one
substitutable level. G1 measured the single-level case; this is two levels deeper.

⛔ **This spike must not be executed while the demo environment is active.** `integration/dp-remote-demo` has
uncommitted work and may be running; a broker spike that provisions VPNs, client usernames and ACL profiles is
shared-state work of exactly the kind that breaks a live demo. **Run it when the demo is down**, on a broker
service that belongs to the spike.

**Fixture, including the provisioning OR-5 actually rules** — an earlier draft named the hierarchy and not the
broker topology, so the spike could have been run inside **one** VPN and measured the opposite of the ruled shape:

| | |
|---|---|
| **Organizations** | `orgA`, `orgB` |
| ⛔ **Brokers** | **two Message VPNs — one per organization** (on Solace Cloud, **two broker services**). This is the ruled topology and running the matrix in a single VPN invalidates it |
| **Sub-organizations** | `su1`, `su2` in each organization |
| **Environments** | `dev`, `prod` in each sub-organization — **8 leaf environments**, one instance per leaf |
| **Subscriber identity** | a client username / JWT `sub` bound to **`orgA`'s VPN**, for a principal granted at `[orgA, su1, dev]` — the narrowest interesting scope, and the one downward inheritance makes legitimate |

**Subscriber under test:** a principal granted at `[orgA, su1, dev]` — the narrowest interesting scope, and the
one the ratified amendment's downward-inheritance model makes legitimate.

| # | Subscription the client attempts | Expected | Why |
|---|---|---|---|
| 1 | `sm/v1/orgA/su1/dev/notify/>` | ✅ **allow** | exactly its granted scope |
| 2 | `sm/v1/orgA/su1/dev/notify/solution/*/instance/*/health` | ✅ **allow** | a narrower filter inside its scope |
| 3 | `sm/v1/orgA/su1/dev/notify/solution/*/instance/*/placement/*/health` | ✅ **allow** | placement facts beneath its scope (downward inheritance) |
| 4 | `sm/v1/orgA/su1/prod/notify/>` | ⛔ **refuse** | **sibling environment** — the leg item 35 exists for |
| 5 | `sm/v1/orgA/su2/dev/notify/>` | ⛔ **refuse** | **sibling sub-organization** |
| 6 | `sm/v1/orgA/su2/prod/notify/>` | ⛔ **refuse** | sibling on both levels |
| 7 | `sm/v1/orgA/*/dev/notify/>` | ⛔ **refuse** | a wildcard at the sub-organization level would cross `su1`/`su2` |
| 8 | `sm/v1/orgA/su1/*/notify/>` | ⛔ **refuse** | a wildcard at the environment level would cross `dev`/`prod` |
| 9 | `sm/v1/orgA/>` | ⛔ **refuse** | organization-wide — **the posture this ruling replaces** |
| 10 | `sm/v1/>` | ⛔ **refuse** | everything |
| 11a | `sm/v1/orgB/su1/dev/notify/>` — **does the subscribe succeed?** | ⚠️ **either answer is acceptable** | A VPN boundary does not refuse a *subscription*; it makes the topic address a different namespace. An earlier draft demanded "refuse" here and called an ACL refusal "a finding", which gave the leg no measurable pass condition. Record what happens; do not expect a refusal |
| 11b | …and **does any `orgB` message arrive?** | ⛔ **nothing delivered** | **This is the leg with a pass condition.** "Subscribe allowed, nothing delivered" is the expected **VPN-layer** result. Anything delivered is a cross-organization leak and a stop-the-line finding |
| 12 | Publish anything, anywhere | ⛔ **refuse** | The browser never publishes (rule 2·11). ⚠️ G1 measured that a publish denial is **invisible to the client**, so this leg must be asserted from **SEMP config**, not from client behaviour |
| **13** | **An organization-terminating topic** — `catalog.package`'s `sm/v1/orgA/notify/catalog/package` (§11.2 rule 2: organization-scoped facts stop at the organization level) | ⚠️ ***UNDECIDED — this leg has no expected value yet*** | ⛔ **See §11.4. This is a design gap, not a measurement gap**, and the matrix must not be run as if it had an answer |
| **14** | **Stream tier:** two sessions in one organization, each with a grant, each attempting the other's `sm/v1/orgA/stream/{sub}/…` branch | ⛔ **each refused the other's branch** | The tier `BE-D5`'s substitution mechanism actually lives on — and the one §11.1 does **not** dissolve: one VPN per organization still has a single `default` client username shared by every session in it, so this leg needs the client-username / `sub` provisioning spelled out before it can run |

**Two things the run must record, not just observe:** (a) how many **broker writes per grant** the shape costs —
G1's zero-writes result was for one substituted level and does not transfer; and (b) whether legs 7 and 8 refuse
at *subscribe* time with `SUBSCRIBE_ACL_DENIED` or silently deliver nothing, because a silent partial subscription
is indistinguishable from a quiet system and would be the worst outcome.

⚠️ **Until this matrix runs and passes, live notify may not be enabled for any shared or customer install**, and
the EDA acceptance criterion cannot be met.

⚠️ **What a pass would and would not settle, stated precisely.** Legs 1–12 are the **notify** tier, so passing them
closes **open item 35** (environment-level isolation) and nothing more. Leg 14 is the **stream** tier — where
`BE-D5`'s substituted `$client-username` mechanism lives and where open item 32's costed routes apply — so
**`BE-D5` cannot ratify on legs 1–12 alone.** ⚠️ And §11.1's claim that OR-5 "dissolves most of open item 32" is
about *organizations not sharing a VPN*; it does **not** dissolve the mechanism gap, because one VPN still has one
`default` client username shared by every session in that organization.

### 11.4 ⛔ Open design gap: how does an organization-scoped fact reach a lower-scoped subscriber?

**Found by review, 2026-09-16. Not answered here, because answering it would be inventing broker semantics.**

`catalog.package` is an organization-scoped fact, and §11.2 rule 2 says such facts **terminate early** —
`sm/v1/{organizationId}/notify/catalog/package`. But the matrix's allowed filters for a principal granted at
`[orgA, su1, dev]` are all rooted at `sm/v1/orgA/su1/dev/notify/…`, and legs 9 and 10 **refuse** every filter that
could reach an organization-terminating address.

⛔ **So as ruled, an environment-scoped principal can never receive `catalog.package`** — while §7 requires exactly
that handler, to invalidate `['solutions', 'list']`. The consequence is the one rule 2·9 and OR-3 exist to forbid:
**the solutions list silently loses event-driven invalidation for every below-organization principal and reverts to
polling.**

**Three candidate answers, priced — none chosen:**

| | Approach | Cost |
|---|---|---|
| (a) | **A second, organization-level allow exception** on every subscriber's profile, alongside its scoped one | Simplest, and it re-opens a narrow organization-wide read — acceptable only because `I6` keeps values off notify, but it is exactly the posture OR-5 replaced, at one level |
| (b) | **Publish organization facts down to every environment address** | ⛔ Forbidden by §11.2 rule 3 as written (*"never invent fake lower scopes"*), and it fans out one fact N times |
| (c) | **Move `catalog.package` invalidation to REST** — the solutions list refetches on navigation and on the degraded poll | Honest and cheap, but it means one surface is not event-driven, which needs saying out loud rather than discovering |

⛔ **Consequence for the matrix: leg 13 has no expected value until this is ruled**, and the spike must not be run
as though it did. **Owner question 8** in the handover carries it.

---

## 12. Fixture mode versus live mode

The console today runs against MSW test doubles (`frontend/src/shared/testdoubles/`). That is legitimate and must
stay — for tests, for design work, and for a frontend engineer with no backend running.

| | Fixture mode | Live mode |
|---|---|---|
| Selected by | A **build/dev-time** flag, evaluated at module load | The absence of that flag |
| REST | MSW handlers | `cpClient` / `dpClient` / `consoleClient`, all same-origin paths |
| Events | No mesh session; `state` reports `stopped` or a simulated `live` | Real ticket, real broker |
| Auth | Simulated identity | Real session cookie + CSRF |
| Permitted in a deployed artifact | **No** | Yes |

### 12.1 ⛔ A live request must never fall back to a fixture

**This is a prohibition, not a preference.**

- A failed live request produces an **error state** (or the degraded banner). It **never** resolves with fixture
  data.
- There is **no runtime switch** from live to fixture. The mode is decided once, at load, and cannot change
  because a request failed.
- Fixture handlers must be **absent from the production bundle**, not merely unreachable — a tree-shaken-out
  module cannot be revived by a runtime condition.
- **Enforced, not trusted:** plan 007 adds a frontend guard (the `check-guards.sh` idiom — each guard covered by a
  self-test that plants a violation and checks it is reported) asserting that no production entry point imports
  `shared/testdoubles/**`, plus a bundle assertion that no fixture module is present.

**Why this is stated so bluntly.** A fixture fallback makes a broken backend look like a working console. Every
downstream claim — "the apply succeeded", "health is green" — becomes unfalsifiable, and the operator is shown a
fiction with no indicator. Degraded mode exists precisely so that the honest answer has somewhere to go.

---

## 13. `dp-remote` versus Agent Gateway — responsibility matrix

Two execution paths, **one set of durable semantics**. They are siblings behind the same seam; **the Agent Gateway
is not in the `dp-remote` path**, and neither is a fallback for the other.

| Concern | `dp-remote` | `host-local` via Agent Gateway |
|---|---|---|
| Who acts on the host | **The DP itself**, holding scoped runtime credentials (SSH · Docker API · K8s API) | **The agent**, on the host, outbound-only |
| Connection direction | DP → host (outbound from the DP) | agent → gateway (outbound from the customer host) |
| Credentials stored by SM | Yes — scoped host credentials (`SM-D11a`, `D2`'s guarantee narrows here) | **No host credentials.** mTLS identity + a short DP-renewed lease |
| Seam | `RuntimeAdapter` implementations: `DockerAdapter`, `SshDockerRuntimeAdapter` | **A third `RuntimeAdapter` implementation** — `AgentDispatchAdapter` (`agent-gateway/design.md` §3.5) |
| Synchrony | Operations return synchronously | Operations return **`IN_FLIGHT`**; the engine gains one wait state and the ack arrives via `CommandAckPort` |
| Durable state and **all** `sm_data` writes | `dp-core` | **`dp-core` — unchanged.** The gateway holds **no datasource and no DB role** (`AGW-D2`) |
| Gateway ↔ DP contract | n/a | **Typed semantic ports in `dp-api`** (`AGW-D6`), **nine** of them, of which **seven — the implementation-ready ones — ship an abstract JUnit contract-test kit** a future remote adapter must pass; `ActualStatePort` and `AgentAuditPort` have none (§13.2). In-memory callbacks are never the sole contract |
| Apply journal, admission fences, generation monotonicity, idempotency keys, bounded retry, resume | `BE-D13` | **`BE-D13`, identical.** The one addition is the resume rule for a live non-terminal `agent_command` (resumes as *awaiting completion*, not driven to `APPLY_INDETERMINATE`) |
| Identity vocabulary | `SM-D32`–`SM-D34`: `organization` external, `tenant_id` internal | **the same** |
| Actual-state model | Adapter `status(replica)` reads the host | `status` is **never a command** — it reads `dp-core`'s canonical per-replica actual-state projection ⚠️ which **does not exist yet** (`AGW-20`) |
| Event contracts | `contracts/events/` | **the same** — one catalog, one envelope, one taxonomy |
| Console-facing authorization | the ratified 38 verbs | **the same human RBAC.** Agent authorization is identity + lease + one target and is **deliberately not** a second human RBAC (`AGW-D13`) |

⚠️ **One warning about the word "gateway."** This document uses it for **two** modules. In §2 rule 12, §4, §5.5 and
§10.1 it means **`console-gateway`** — the HTTP/session/ticket boundary the browser talks to, which is one marker
class today. In this section and §13.2 it means the **Agent Gateway** (`agent-gateway`), which does not exist as a
module at all. Where it matters below, the module name is written out.

**Four statements to keep straight, because getting any of them wrong corrupts the architecture:**

1. **The Agent Gateway is designed, not built.** Measured on this branch: `docs/design/backend/agent-gateway/` exists in
   full; `backend/` contains **no** `agent-gateway` module — the reactor is `cp-api`, `dp-api`, `mesh-events`,
   `cp-core`, `dp-core`, `console-gateway`, `demo-launcher`, `arch-tests`, and `console-gateway` holds exactly one
   marker class. *(`demo-launcher` was deleted by plan 011 P11-9.1, `OR-112` (4); the list is the reactor as measured then.)*
2. **It is never inserted into the `dp-remote` apply path.** A `dp-remote` apply goes
   `ApplyExecutor → RuntimeAdapter → DockerAdapter | SshDockerRuntimeAdapter`. No gateway, no port, no agent.
3. **Both paths *must* converge on the same durable DP semantics** — and two of the five have not converged yet, so
   this is an obligation, not a description. **Converged today:** the journal and its fences (`BE-D13` is the same
   for both) and the event contracts (one `contracts/events/` catalog). **Not yet converged:** the **actual-state
   model** — `dp-remote` reads the host through `status(replica)` while the agent path's canonical per-replica
   projection **does not exist** (`AGW-20`) — and the **identity vocabulary**, where open item 36 records that the
   as-built surface still uses *three spellings for two concepts*. Convergence is the design's whole point; plan
   008 must not widen the gap, and cannot close it alone.
4. **The gateway may never reach `sm_data`.** No DP repository import, no JPA entity, no table access, no
   datasource, no DB role — enforced (once built) by `agwUsesDpApiOnly`, `agwHoldsNoDatasource`,
   `onlyAgwTouchesGrpc` and a pom dependency allow-list, each with a planted-violation proof. Until those tests
   fire, this is a proposal, not a guarantee (`L19`).

### 13.3 Where the production application lives, who composes reads, and who publishes what

✅ **`console-api` is approved (OR-10, 2026-09-16) — open item 21 is closed.** A **thin** module in the existing
backend deployable:

| Rule | Statement |
|---|---|
| **Depends on** | CP and DP **service interfaces** only — `cp-api` and `dp-api`. ⛔ **Never** their data-access modules, which is what keeps `I7` intact and satisfies `UI-D13`'s *"two reads assembled, never one query"* |
| **Owns** | **no database.** No table, no migration, no datasource, no DB role |
| ⛔ **Never does** | **cross-schema SQL.** There is no query that touches `sm_control` and `sm_data` together, in this module or anywhere |
| **Data ownership it composes across** | **CP owns** package, hierarchy, config and auth data. **DP owns** apply, runtime, health and metrics data |
| **Broker consumers** | persist into the **owning plane** — a consumer never writes the other plane's schema. ⛔ **Broker notices only trigger refetch**; a notice is never the write |
| **Reads** | composed **concurrently** across CP and DP, not serially — the composition exists to avoid the fan-out latency `UI-D13` warns about |
| **Returns** | `stateVersion` (§6.1) **plus per-surface availability/degraded information** (§10.2) — so a partially-degraded console can say which surface is stale rather than degrading whole |

⚠️ **`architecture.md` §3.1 names only two plane modules.** `console-api` is the third, and that text needs it
added — flagged here, not edited from here.

⚠️ **`console-api` is not the Spring Boot application class.** That is the separate composition module below; the
two are different jobs and the rules that forbid one forbid the other differently.

#### Where the application class lives

Two module facts that the plans must obey and that three ArchUnit rules already enforce. **Measured on this
baseline, from `backend/arch-tests/.../PlaneBoundaryTest.java`.**

**(a) The Spring Boot application class may NOT live in `console-gateway`.** Two existing rules forbid it:

| Rule | Why it forbids it |
|---|---|
| `gatewayUsesApisOnly` | `com.solace.sm.gateway..` may depend on `cp.api`/`dp.api` **only**. An application class must wire `cp-core` and `dp-core` implementations, which this refuses |
| `gatewayNeverImportsEvents` | `com.solace.sm.gateway..` may not depend on `com.solace.sm.events..` at all — so the gateway cannot construct or inject the `EventPublisher` either |

So the production application is a **separate composition module** (a launcher), which:

- depends on `console-gateway`, `cp-core` and `dp-core`, and owns the Spring Boot `main`;
- wires implementations to the `cp-api`/`dp-api` interfaces;
- owns **startup sequencing** and the **durable apply worker** (§9.2);
- owns **no database table, no migration and no business logic**;
- ⚠️ **declares no `DataSource`, `JdbcTemplate`, Flyway or PostgreSQL type** — `jdbcIsConfinedToApplyPersistence`
  confines all six of those package roots to `com.solace.sm.dp.apply.persistence..`. ⛔ **But the answer is
  `@Import`, NOT auto-configuration** — the opposite of what an earlier draft of this section said, and the
  difference is the whole privilege topology. `ApplyPersistenceConfiguration`'s own javadoc: *"**Not
  auto-configured, and not `spring.datasource.*`** … Boot's `DataSourceAutoConfiguration` would give the
  application ONE datasource, and the whole privilege topology of §3.1 — obligations (k), (l), (o), (s), (t) —
  rests on the runtime connection being a role that cannot migrate and cannot `SET ROLE sm_dp_owner`."* So the
  launcher `@Import`s `ApplyPersistenceConfiguration`, `ApplyEngineConfiguration` and `MeshBridgeConfiguration`
  (each of which already declares its own beans inside an allowed package) and **excludes
  `DataSourceAutoConfiguration`**. `@Import`ing those classes is ArchUnit-safe: they are not themselves members of
  the six roots;
- ⛔ **and it does not sequence migrations.** Migration is `SmDataMigrationMain`, **a separate one-shot process the
  install runbook runs before the application starts** — that split is what stops the application process holding
  the schema-owner credential for its whole life. What the launcher does instead is *verify*, through
  `smDataSchemaReadiness`, refusing to serve a schema the migration step has not reached;
- ⛔ **and it may not construct `ApplyExecutor` itself.** That constructor takes `RuntimeAdapter` and
  `RenderedConfigSource` — both `com.solace.sm.dp.apply.runtime` types — so a `@Bean` method naming them in the
  launcher would trip the one-apply-path rule (ArchUnit reads `@Bean` parameter and return types). `dp-core`
  publishes the `ApplyExecutor` and `RuntimeAdapter` beans from **inside** `com.solace.sm.dp.apply.engine`, which
  `ApplyEngineConfiguration`'s javadoc already anticipates (*"The future application class `@Import`s both,
  deliberately, exactly as it imports `MeshBridgeConfiguration`"*), and the launcher reaches them only by
  `@Import` plus injection of engine-package types;
- does not weaken `gatewayUsesApisOnly` or `I7` — being outside `com.solace.sm.gateway..`, `com.solace.sm.cp..` and
  `com.solace.sm.dp..`, it is the one place both planes may legally be named together, which is what a composition
  root is for.

⚠️ **`demo-launcher` is temporary and must not become the production composition root.** *(Deleted by plan 011 P11-9.1, `OR-112` (4); kept as history.)* The reactor's own comment
says so — *"Local, time-boxed demo composition. It is not the console-gateway architecture boundary."*

**(b) `catalog.package` belongs to `cp-core`, not to the DP's apply emitter.** Measured:
[`backend/design.md`](../../backend/design.md) §3's catalog row reads **`catalog.package` (`cp-core`)**, and
`contracts/events/envelope.schema.json` closes `producer` to `['cp-core', 'dp-core']` — so a DP-side emitter would
have to either mislabel its producer or claim the CP's id.

| Emitter | Module | Emits | Producer id |
|---|---|---|---|
| `ApplyEventEmitter` | `dp-core` | `instance.rollout`, `instance.config-state`, `instance.health` (and later `instance.version`, `metrics.quarantine`) | `dp-core` |
| `CatalogEventEmitter` | `cp-core` | `catalog.package` | `cp-core` |

Both depend **only** on the plane-neutral `EventPublisher` contract in `mesh-events`; the composition module injects
the one concrete publisher into both, so there is still **one broker session owner** (`onlyMeshbridgeTouchesJcsmp`
confines the SDK to `com.solace.sm.dp.meshbridge..`). **`cp-core` never imports `dp-core`** — it receives the
publisher as an interface.

⚠️ **Permitted by the plane rules — but it reverses a written constraint, so it is a recorded decision, not a POM edit.**
`PlaneBoundaryTest`'s own comment above `eventsNeverImportCp`/`eventsNeverImportDp` reads: *"neutrality is exactly
what makes it safe for **BOTH planes** to consume, which Task 9 onward will want."* And `EventPublisher`'s javadoc:
*"It lives in `mesh-events`, not in a plane module. The seam is plane-neutral by construction — **both planes
publish through it**."* What is required is a `mesh-events` dependency in `cp-core`'s POM.

⛔ **And here is the constraint an earlier draft of this section missed while claiming to have measured that POM.**
`backend/cp-core/pom.xml`'s own `<description>` reads: *"Control-plane implementation, owns `sm_control` (backend
design §2). **Depends on `cp-api` only — never on `dp-api`, `dp-core` or `mesh-events` (`I7`)**."* That is a
written, `I7`-citing design statement in the build file of the very module being changed. The two quotes above
establish that the **shared module** is safe for both planes to consume; they do **not** establish that `cp-core`
specifically was left free to consume it.

So per `I3`/`I4` this is a **decision**, not a dependency edit: record it — ⛔ **allocating the next free id from the merged
`DECISIONS.md`** after plan 005 merges (ruling **OR-11**; no id is reserved in advance) — stating that `cp-core` depends on `mesh-events`, that `I7` is
unaffected because the seam is plane-neutral and the injected bean is typed `EventPublisher`, and **edit the POM's
`<description>` in the same change** — otherwise the next reader measures the POM and reaches the opposite
conclusion. After-commit semantics apply to the CP emitter identically.

⚠️ One compositional note, since it is not an `I7` breach but is worth knowing: the concrete publisher **and** the
transaction-boundary implementation are both owned by `MeshBridgeConfiguration` in `dp-core`, whose bean is typed
`EventPublisher` (the interface) — so the CP acquires no `dp-core` type, but the CP's publish identity is
configured by a DP-named class. That is a naming awkwardness to live with, not a boundary to fix.

### 13.1 Reconciliation: later backend decisions versus the documented ports

The Agent Gateway design was written 2026-09-08; four backend decisions landed after it. Measured status:

| Decision | Reconciled in `agent-gateway/`? |
|---|---|
| `BE-D21` (seam signature: `TargetReplica`, caller-owned `RenderedConfig`) | ✅ **yes** — §3.5 carries a dated impact note saying an `AgentDispatchAdapter` implements the `BE-D21` signature, not the one `BE-D12` §2.1 prints |
| `BE-D22` (the sixth operation is **two**: `assessRollback` + `restore`) | ❌ **no** — §3.5 still opens *"The **six** operations map as follows"* and still shows one `rollbackOrMarkIndeterminate` row. The seam on this branch has **seven**, so reconciling `BE-D22` means fixing a count **and** a row |
| `BE-D23` (v1 engine is single-placement; `apply_step_intent` identity is placement-blind) | ⚠️ **recorded, not edited in place** — its identity statement is superseded by `BE-D44` (plan 008, `sm_data` V021) and its one-placement limit by the registry-driven stop (`BE-D45`, `BE-D46`); the consequences for the gateway are routed to the `AGW-D*` owners in [`RECONCILIATION-008.md`](../../backend/agent-gateway/RECONCILIATION-008.md) (plan 008 S4, 2026-10-08), which resolves none of them |
| `BE-D24` (a config location names a **writer access mode**; not always a local `Path`) | ❌ **no** — relevant to `materializeConfig` on the agent path |

⚠️ **Recorded, not fixed.** The `AGW-D*` family is PROPOSED and ratifies **after** `BE-D*`; editing its meaning
from here would violate `I3`/`I4`. Plan 008 carries the reconciliation as a design step, and `AGW-D14`'s
"extraction is adapters only" clause is what must survive it.

### 13.2 Agent Gateway blockers that stay open

`AGW-17`…`AGW-21` are five distinct gates, and only two of them gate those two ports — the other three gate
`AGW-D` **ratifications**. The design's own `Blocks` values are reproduced below rather than paraphrased, because
an earlier draft of this table weakened them. **None is closed by anything in this document or by G2.**

| # | Blocker | Blocks |
|---|---|---|
| `AGW-17` | Reported replica identities (`replica_ref`/`incarnation`) are grammar-checked, capped and registered from trusted reports but **not verified** against the runtime that owns them | **ratification of `AGW-D16`, and of `AGW-D13`'s `I6` clause — which `AGW-17` gates alone.** (§12 limit 14 is what closing it would *close*, not what it blocks) |
| `AGW-18` | CP-side idempotency of lease issuance under the DP's `request_id`, or a CP status lookup by it — the only recovery that does not consume the agent's single-use renewal nonce | **`AGW-D15` ratification — "a blocker, not a nicety"** — and crash recovery between the CP answer and ledger completion |
| `AGW-19` | No periodic actual-state report cadence exists, so `last_seen_at` is refreshed only by an `ActualStateReport` — and the registry's retirement (returning a cap slot for a vanished replica) depends on it | **`AGW-D16`'s retirement property, conditional on it — and the lease/registry sketch's §5.0 "must not run" until it is settled.** With it, the stable cap's release |
| `AGW-20` | **Four things the family supplies none of** — read them in `design.md` §13, not from a paraphrase: a durable **per-item request identity** `(agent_id, item_id)` with `agent_id` DP-resolved and never taken from the wire; a stored **canonical fingerprint** *"identical to the list in §3.2 and not a paraphrase of it"*; a stored original result plus fencing-before-replay precedence; and freshness ordering | `ActualStatePort` (its contract is **PROVISIONAL**), `status(replica)` on the agent path, §8 row 6, **the convergence gates, and the agent implementation plan** |
| `AGW-21` | The DP agent-security audit persistence contract — §7.7 requires durable records, §3.2 pointed at `apply_request`, which is the wrong table | `AgentAuditPort` |

Plus, from §13 of that design and untouched by G2: `AGW-1` (lease audience under a replicated gateway, tied to open
item 34) · `AGW-2` (**the `.proto` and wire state machine do not exist** — open item 6) · `AGW-10` (operator verbs
about agents — routed to the `D10` amendment and **not granted** by the 2026-09-15 approval, so the fleet screens
stay unauthorizable) · `AGW-11`/`AGW-12`/`AGW-13`.

**Therefore:** no claim that the `host-local` path works end to end is available, and none may be made. Its
extraction criterion is explicit — conformance exists only for the **seven implementation-ready ports**;
`ActualStatePort` and `AgentAuditPort` have no durability kit to pass.

---

## 14. Implementation status — measured, not planned

Starting state measured on `integration/dp-remote-demo` at `2a0047e`, 2026-09-15. Current statements below are
refreshed after plan 005 merged into the integration lineage.

**Measured 2026-10-07 (plan 010 T12.1): the production API, the production composition module and the apply
events are built and proven in a booted launcher.** The rows marked **Starting state** below are kept as the
record of what was true on 2026-09-15; the **Measured** rows beside them supersede them. Evidence: the
launcher-level ITs named per operation in [`contracts/openapi/ENDPOINT-INVENTORY.md`](../../../../contracts/openapi/ENDPOINT-INVENTORY.md)
(each boots the real `SolutionManagerApplication` over PostgreSQL), `DemoParityChecklistIT` (plan 007 Task 13a's
parity checklist as a test), and the live run in
[`010-first-e2e-walkthrough.md`](../../../plans/backend/evidence/010-first-e2e-walkthrough.md) (T6.6, run 3,
2026-10-06: `SkeletonWalkthroughIT` 9 run, 0 failures, 1 skipped; `MeshResilienceIT` 7 run, 0 failures, 0 skipped).

### 14.1 REST

| Surface | Status |
|---|---|
| **Measured** — the production `/api` surface | ✅ **All 46 operations in `console.yaml` have an `@ConsoleOperation` handler in the production launcher** (`MetricsOperationClassificationTest`, `OperationCoverageValidatorTest`; `ProductionAuthenticationIT` pins the exact set in the booted context). Plan 010's operations are each proved by a launcher-level production-boot IT (inventory, "Proved by" column). `exportRange` is wired and tested but **not counted served** until plan 011 P11-8.1's 105 M-row stream is recorded. The overlapping demo capabilities (auth, solutions, instances, designer, config, apply, restart, health, ladder, logs) each have an authorized production route: `DemoParityChecklistIT`. No production endpoint falls back to the demo |
| **Starting state** — the 66 operations | **52 planned · 12 with no approved verb · 1 blocked by DCL · 1 newly authorizable = 66.** ⚠️ **These four numbers are a partition and an earlier revision's did not add up:** it read *"52 · 10 · 1 · 3"* and then listed *"the **13** that no approved verb authorizes"* including **row 23**, which the same sentence had just called authorizable — row 23 was counted on both sides at once. ✅ The 2026-09-16 extension (ruling **OR-9**) approved `user.edit`, `audit.view` and `instance.stop`; of those, **only `user.edit`'s row 23 is among the 66** — `audit.view`'s read is §5.5 and `POST …/stop` is not one of the 66 — so **1**, not 3, is newly authorizable here. ⛔ **The 12 that no approved verb authorizes: §5 rows 37 (`instance.undeclare`) and 47 (package actions), plus the ten collapsed `/cp/admin/groups*` rows** — all *proposals* in open item 39 letters (c), (d) and (e), not approvals, so each answers `501`. None of the 66 exists as a production endpoint (`backend/console-gateway` holds one marker class). ⚠️ **Row 14 (`POST /admin/roles`) is the odd one out** — its verb `role.edit` **is** catalog member #26; it is blocked because `sm_control.role` is `SELECT`-only to the runtime role by DCL (v1 ships built-in roles only), which is a different kind of block and a different fix. ✅ **The unnumbered `POST …/stop` row is no longer verbless** (`instance.stop` is catalog row 36); it remains outside the 66 |
| `demo-launcher`'s demo surface | ✅ **Measured: still not a production authority, and now fenced** — production code and composition cannot reach `com.solace.sm.demo..` (plan 007 Task 12's rules); `demo_control` remained for plan 011 (**both deleted by plan 011 P11-9.1, `OR-112` (4)**). **Starting state:** **implemented, and not a production authority.** Cookie login, one fixed demo role, four hard-coded permission strings (`solution.load`, `instance.declare`, `config.apply`, `instance.restart` — two of which are in **neither** catalog), `demo_control` schema, in-memory status/metric/session stores. Its waiver **expired 2026-09-14**. Plan 007 removes it as an authority |
| Frontend clients | **implemented** — `cpClient` / `dpClient` / `consoleClient` over one same-origin factory with an absolute-URL guard; repositories per feature; TanStack Query keys |
| CSRF | ✅ **Measured: the production launcher enforces `X-SM-CSRF` with `__Host-SM_CSRF` on every unsafe method** (`ProductionAuthenticationIT`); the frontend's production wiring is plan 007's release gate, not measured here. **Starting state:** ⚠️ **wired, but only to the demo.** `providers.tsx` configures `X-SM-CSRF`/`SM_DEMO_CSRF` when `channel === 'local-demo'` and clears the hook otherwise. **No production wiring**, and plan 007 Task 3's `CsrfFilter` must reconcile with — or deliberately replace — that header/cookie pair |

### 14.2 Events

| Piece | Status |
|---|---|
| Envelope, six notify schemas, AsyncAPI, fixtures, negatives, check 10 | ✅ **implemented and gated** |
| Java bindings — `EventEnvelope`, `EntityRef`, `NotifyTopics`, `NotifyEventShapes`, `EventJson` | ✅ **implemented** |
| `EventPublisher` seam + `AfterCommitEventPublisher` + `JcsmpEventPublisher` + `ReconnectingTransport` | ✅ **implemented** |
| **Production composition module / Spring Boot application class** | ✅ **Measured: `backend/launcher` (`SolutionManagerApplication`) exists and boots in every launcher IT.** **Starting state:** ❌ **did not exist.** ⚠️ And it **cannot** be `console-gateway`: `gatewayUsesApisOnly` and `gatewayNeverImportsEvents` both forbid it (§13.3) |
| **`CatalogEventEmitter` in `cp-core`** | ❌ does not exist, and `cp-core` has **no `mesh-events` dependency** (measured: `cp-api` + `spring-boot-starter` only). The design permits adding one (§13.3) |
| **Any production publish site** | ✅ **Measured: the apply engine publishes through `ApplyEventEmitter` after commit** — `instance.apply-outcome` received on a local broker in the live walkthrough (T6.6 run 3) and by `ApplyEventsProductionBootIT`; the catalog event by `CatalogEventIT`. With no `sm.mesh.*` configured the application boots with a counted dropping publisher, never a refusal (`MeshDisabledBootIT`). Still not built: a terminal notice for an instance-wide stop (stops are REST-observed, OR-4) and the browser half. **Starting state:** ❌ **NONE.** Measured: the only classes referencing `EventPublisher` in `dp-core`/`cp-core` are the mesh-bridge's own wiring (`MeshBridgeConfiguration`, `ReconnectingTransport`, `JcsmpEventPublisher`). **The apply engine publishes no events.** Rule 2·3 is unimplemented, and this is plan 007's core deliverable |
| Drop counter exported as a metric | ❌ **no** — in-process `AtomicLong` only (§2.1) |
| Generated TypeScript `NotifyEvent` types | ❌ **blocked on the `BE-D4` amendment** |
| `LiveStateClient` / `shared/mesh` | ❌ **does not exist** |
| Browser mesh session, tickets, grants | ❌ **not built.** The G1 spike measured the broker mechanism; no product code joins the mesh |

### 14.3 Apply engine

| Piece | Status |
|---|---|
| `RuntimeAdapter` seam — **eight** operations (`replicas`, `prepare`, `materializeConfig`, `startOrUpdate`, `stop`, `status`, `assessRollback`, `restore`) | ✅ implemented |
| `DockerAdapter`, `SshDockerRuntimeAdapter` | ✅ implemented |
| Apply persistence — three tables, admission fences, DDL constraints, replay harness | ✅ implemented (plans 004–005) |
| Health gate, rolling strategies, resume planner, stop | ✅ implemented on this integration lineage by the merged plan 005 |
| An authenticated production API in front of it | ✅ **Measured: served** — `submitApply`, `observeApply`, `rollbackApply`, `restoreRuntimeConfig`, `stopInstance` (inventory rows, each with its proving IT), driven end to end against a real SSH-Docker Cache instance in T6.6 run 3 (evidence file above). **Starting state:** ❌ none |

---

## 15. Demo learnings — what production inherits, and what it must not

**Carry forward** (each is a real property the demo proved, to be re-implemented behind the production seams):

| Learning | Where it lands |
|---|---|
| SSH host-key pinning and **bounded** credential materialization | `SM-D11a`; inside `RuntimeAdapter`, never above it |
| Batched SSH status/config/metric requests | adapter-internal; one round trip per observation set |
| Explicit restart semantics — a restart is a **commanded** verb, never an implicit side effect | `config.apply` + `instance.restart` as separate verbs (approval §1.1) |
| Package-driven rendering and validation | `contracts/solution-package.schema.json`, `project()` |
| Secret retention **without** returning resolved values | `I6`; endpoint 41 |
| Timestamp-based log pagination, load-older, follow mode | endpoint 52 |
| Durable metrics and dashboards | `SM-D8`/`SM-D26`/`SM-D30`/`SM-D31`; plan 007+ |
| **Restart-observed versus effective-verified** — two different claims | `instance.rollout` step vocabulary vs `SM-D22`'s ladder; §9 rule 4 |
| Asynchronous browser apply behaviour | `202 + applyId` and §9 |

**Do not carry forward** — each of these is a production defect, not a shortcut:

| Anti-pattern | Why it cannot ship |
|---|---|
| Demo-only sessions as production authentication | §4 is the contract; the demo's is not it |
| Hard-coded permissions | The catalog is code-owned and closed; two of the demo's four strings name verbs that do not exist |
| Direct controller → runtime apply | The engine is the only apply path: `ApplyExecutor → RuntimeAdapter`. A controller that calls an adapter bypasses admission, the journal, the fences and the audit |
| `demo_control` as a production authority | `sm_data`/`sm_control` are the schemas; `demo_control` was scaffolding (deleted by plan 011 P11-9.1, `OR-112` (4)) |
| In-memory production metrics/status/session state | Sessions are durable (§4); status is the actual-state projection; metrics are PostgreSQL |
| **Polling as the normal live-console architecture** | Rule 9. Polling is the degraded mode plus the one named §9 exception |

---

## 16. Routed blockers — what this document cannot fix

### 16.1 The `BE-D4` amendment (G3's residue) — eight items, one amendment

| # | Item | Effect while open |
|---|---|---|
| 1 | `metrics.quarantine` topic absent from `BE-D4`'s decision text | text/gate mismatch; the gate is right |
| 2 | The rollout `step` vocabulary has **no canonical home** — of the three, only `restart-observed` has one | a closed enum invented by an implementation plan |
| 3 | `instance.rollout`'s catalog row does not list `placementId`, which the schema requires | a producer written from the row alone emits a rejected event |
| 4 | `catalog.package`'s `packageVersion` is untyped (`{}`) | **Python and Java deliberately disagree.** Do not "fix" either side |
| 5 | The stream level: `BE-D4` names it `{grantId}`; `BE-D14` §4.5 carries the session's `sub` | a level rename |
| 6 | **No terminal-apply-outcome notice** | §9 — the apply screen polls |
| 7 | Wire-visible rollback/verify progress | the console cannot show rollback progress live |
| 8 | Per-binding config-state ladder refinement + a referential guard for the nil-UUID placement sentinel | §6.2's containment stands as the v1 rule |

⚠️ **What this amendment gates is whether the browser's `NotifyEvent` types are *generated*, not whether they may
exist.** `FE-D7` explicitly permits hand-written types until the contract exists (*"replaced, not grown"*), and it
governs the REST half in any case. So the mesh path is fully buildable today against the schemas as they stand —
under `BE-D10`'s mirror-and-lock discipline — and **this is not a licence to poll**. An earlier draft of §1 said
these types were "not settlable yet"; that was wrong, and it was the one place this document came close to
deferring the browser half of EDA.

### 16.2 Authorization vocabulary gaps — three of nine closed 2026-09-16, six still open

⚠️ **Counted and lettered as open item 39 letters them** ([`DECISIONS.md`](../../DECISIONS.md) item 39, whose
lettering is the `D10` amendment §5 item 3's and must not shift). An earlier revision of this section said "seven
still open" and then listed **eight** bullets, double-counting two items that item 39 folds into a single letter.

✅ **Closed** (ruling **OR-9**, catalog rows 36–38): **(a)** `instance.stop` · **(g)** the unioned audit read
`audit.view` · **(f)** non-status user edits `user.edit`. ✅ Also closed alongside them: §4.3's
`authz.introspect.self` question (identity needs authentication only, **OR-15**) and `user.deactivate`'s
consequence class (**HIGH**, **OR-13**) — neither was one of the nine.

⛔ **Still open — six letters, each needing one owner answer:** **(b)** high-consequence **strategy-assertion**
verbs, ⚠️ **including `instance.restart.overrideSafety`**, which item 39 folds into (b) as the same question from
the other side · **(c)** the five **`group.*`** verbs (as `group.status.edit`, not `group.deactivate`) ·
**(d)** **`instance.undeclare`** (endpoint 37) · **(e)** **package actions** (endpoint 47) · **(h)** `AGW-10`'s
**agent verbs** · **(i)** **`instance.version.redeclare`** ⚠️ **and archive/restore**, which item 39 folds into
(i) as the same "identify the resource first" question.

**Every one of these makes an operation return `501`, not `403`** (§5.6 rule 5).

⚠️ **Answered 2026-10-07 (forward pointer; the lists above are unedited, `I3`):** letters **(b)** (strategy assertion, `instance.restart.overrideSafety`) and **(d)** (`instance.undeclare`) are **closed** by `OR-121`, confirmed by name in `OR-122`, and written as catalog rows 40 and 39 of [`D10-G2-APPROVAL-2026-09-15.md`](../../D10-G2-APPROVAL-2026-09-15.md) §1.9; the same section adds `target.edit`, `hierarchy.edit` and `entitlement.set` (rows 41–43, plan 013) and gives the Operator `instance.stop`. **Four letters stay open: (c), (e), (h), (i).** The catalog the code mirrors becomes **43** in plan 012 T1; until that commit lands the code is 38 and the two operations still answer `501`.

### 16.3 Other live blockers

| Item | Effect |
|---|---|
| ⛔ Open items **32**/**33** (`BE-D5`) — **HARD GATE on mesh-backed live delivery** | The broker ACL cannot be provisioned and the install runbook's ACL section cannot be written. ⚠️ **`BE-D14` §4.4's two postures now have different statuses:** *tenant-wide notify visibility* (proposal 33) is ⛔ **WITHDRAWN 2026-09-16** (OR-5/OR-6 — one Message VPN per Organization, with sub-organization and environment isolation through topics and ACLs), while *v1 single-tenant* (proposal 32) survives as an **unratified proposal**, re-framed rather than withdrawn. **No mesh ticket may be minted for a real deployment until these close** |
| ⛔ Open item **35** — **HARD GATE on mesh-backed live delivery** | **A confidentiality gap, not a tidiness one.** The 2026-09-15 authorization approved sub-organization-scoped bindings, so principals scoped *below* the organization now exist **by decision**. Under organization-wide notify visibility such a browser can subscribe to notify events for an environment **its REST scope forbids it to read**: REST enforces environment scope, the broker does not. `I6` keeps *values* off the notify tier, so what leaks is **which instances exist and when they change** — which is exactly what an environment boundary is meant to hide. **The EDA acceptance criterion cannot be met until the subscription shape enforces the approved scope model** |
| Open item **34** | 🔴 **Two registers disagree about a `LOCKED` catalog.** `origin/dev/frontend` records `D18`/`D19`/`D20` as LOCKED, and `D18` is *a different 35-verb catalog* — it grants the five `group.*` verbs this baseline withholds. It **blocks register intake** from that branch and it is why §5.2's group rows are flagged rather than simply absent |
| Open item **39** (⚠️ **40 is CLOSED**) | **Both new 2026-09-15.** 39 — ⚠️ **NARROWED 2026-09-16:** nine authorization surfaces with no verb, of which **three are now approved and six remain** (§16.2). ~~40 — who may amend a `LOCKED` parent decision~~ ✅ **CLOSED 2026-09-16 by ruling OR-1: the project owner IS the final architecture authority**, so no separate countersign was owed and the proposal paper's *"only the architect group"* clause is superseded. ⚠️ An earlier revision of this row still listed 40 as open |
| ~~Open item **21**~~ | ✅ **CLOSED 2026-09-16** (OR-10): `console-api` is approved — §13.3. ⚠️ Residual docs task: `architecture.md` §3.1 still names only two plane modules and must name it |
| Open item **36** | The identity-vocabulary rename is one coordinated release; its contracts leg is gated on G3. The as-built surface uses **three spellings for two concepts** |
| Open item **37** | `BE-D12` names two different decisions on two branches — gates any register intake |
| Open item **15** | `rolling-health-gated` is **unsafe for Cache**, not merely unavailable (`active_standby` + leader state exposed only in a log line). §7.2 needs an `INACTIVE_REPLICA` state |
| ⚠️ `BE-D` id space | `decisions.md` now reaches **`BE-D26`** on this integration lineage. ⛔ **No document reserves an id**: allocate the next free id from the merged `DECISIONS.md` only when a new decision is actually made (ruling **OR-11**). Open item 37 is what happens otherwise |

---

## 17. How to use this document

**Frontend engineer.** §4 for the session, §5 for what each call needs, §7 for what to invalidate, §8 for
recovery, §9 for apply and errors, §10 for the indicator, §12 for fixtures. You should not need to open a backend
class; if you do, that is a defect in this document — report it.

**Backend engineer.** §5 is the endpoint contract's authorization column. §2 rules 3–5 are the publish
obligations. §6 is the shape you must satisfy. §13 is the honest starting point.

**Reviewer.** §14 is measured, not planned; §16 is what remains. Any claim that the console is event-driven
**today** contradicts §14.2.
