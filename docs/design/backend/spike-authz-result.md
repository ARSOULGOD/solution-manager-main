# G1 spike result — mesh subscribe authorization (`BE-D5`)

**Broker version tested:** `soltr_10.25.0.177` (image `solace-pubsub-enterprise:10.25.0.177`, linux/arm64), SEMP **2.48** · **Date:** 2026-08-27

> **What this document is.** [`BE-D5`](decisions.md) deliberately left one question UNSETTLED: **what mechanism authorizes a browser's subscriptions
> on the customer's event mesh.** Gate G1 ([design §13](design.md)) blocks ratification of
> `BE-D5`/`BE-D6` and every ticket/stream task until a live broker answers it. This is that
> answer. It is written to stand alone; the harness, the raw transcripts and the
> prove-it-fires plants live in
> [`backend/spikes/mesh-authz/README.md`](../../../backend/spikes/mesh-authz/README.md).
>
> Everything below was **measured against a running broker** or read out of the broker's own
> served SEMP spec / the SDK's own source (`I2`). Anything inferred is labelled inferred.
> Requirement: [`REQ-012`](../../requirements/REQ-012-event-driven-live-console.md).
> Plan: [`001-mesh-foundations-and-gates.md`](../../plans/backend/001-mesh-foundations-and-gates.md), tasks 1–5.
> Evidence base: **30 tests across 5 files, green from a cold start** (last section).

---

## Verdicts

| Candidate | Works? | Per-grant cost | Notes |
|---|---|---|---|
| **(a) substitution-variable ACL** | **PASS, measured SINGLE-TENANT** | **Zero broker writes per grant — within one tenant.** One ACL profile, one subscribe exception, bound once to the VPN's `default` client username — measured, not inferred | ACL profile `acl-<x>` with `subscribeTopicDefaultAction: disallow`, `publishTopicDefaultAction: disallow` and **no** publish exceptions; one subscribe exception `sm/v1/acme/stream/$client-username/>` (syntax `smf`); OAuth profile `usernameClaimName: "sub"`; `clientUsernames/default → that profile`. **⚠️ Two gaps, both blocking, both below:** it cannot reach the **notify tier**, and **only the username level substitutes — the tenant level is a literal**, while a VPN has exactly one `default` client username to hang one profile on |
| **(b) authorization groups** | Mechanism **PASSES**; as a per-grant mechanism it **FAILS** | **3+ SEMP writes per grant**, and an unbounded set of broker objects at steady state | A group is a configured broker object (`/msgVpns/{v}/authorizationGroups`) that only *names* an ACL profile, so a grant-scoped group needs its own profile plus its exceptions plus the group. Also needs a **dedicated OAuth profile** on the customer's broker. Fails **closed at login**, so a config mistake locks users out rather than degrading |
| **(c) tenant-coarse + unguessable grant-ids** | **PASS** | **None** (one profile + client-username pair per tenant × scope, created at onboarding) | Enforces exactly two boundaries: the tenant edge and subscribe-only. Exposure measured at **admission and at delivery**: any in-tenant ticket enumerates every live grant-id in the tenant and receives their payloads from a single wildcard subscription |

**Candidates (a) and (b) are mutually exclusive, not layered.** Measured: a pre-created
client-username object **beats** a valid, matching `groups` claim. Same OAuth profile, same
`groups: ["g-stream-alpha"]`, `sub: "grant-111"` (which has a client-username object) →
the session was authorized by `acl-subst`, *not* the group's `acl-alpha`, and could subscribe
the substituted branch while being **refused** the group's branch. The resolution order this
broker implements is:

> **client-username object → authorization group → login refused.**

So on any subject with a client-username object the groups claim is inert, and on any subject
without one candidate (a)'s `default` fallthrough is unreachable under (b). You get one or the
other per subject, decided by whether an object happens to exist. Nothing composes.
*(Boundary: what was measured is that an existing client-username object with an explicitly
bound ACL profile wins. Whether the broker skips the group lookup or consults and overrides it
is not distinguished, and neither is the case where the object leaves `aclProfileName` at its
own default.)*

---

## Chosen mechanism

**Candidate (a) — the substitution-variable ACL — for per-grant stream isolation, measured
single-tenant, with TWO design questions that must be closed before `BE-D5` ratifies.**

The argument, from the measured facts and nothing else:

1. **It is the only candidate that isolates per grant at the broker without paying per grant.**
   `$client-username` substitutes the **authentication** username — the token's own `sub` —
   and does so even when authorization fell through to the VPN's `default` client username.
   That was measured in isolation (Task 2) and then measured **in the composition the
   recommendation actually uses** (Task 5): bind `default` to a substitution-only profile,
   connect a subject with no client-username object, and it gets a branch named after itself,
   is refused another grant's branch, is refused the branch named after its *authorization*
   username, and is refused the cross-tenant wildcard. Adding a grant costs **no SEMP call, no
   client-username object, no exception**. Revoking one costs stopping minting that `sub`.
   **Measured within one tenant** — see the multi-tenant constraint below, which is the second
   of the two gaps and is not priced by this measurement.

2. **Candidate (b) cannot do it at any acceptable price.** Per grant it is `POST aclProfiles` +
   one `POST …/subscribeTopicExceptions` per topic scope + `POST authorizationGroups` — three or
   more writes, against the customer's production event mesh, for an object set that grows
   without bound with live chart grants. `BE-D6`'s raw-tier grants are per-chart, per-session
   and short-lived; this is the opposite shape. There is no substitution equivalent for groups,
   so there is no "one group, many grants" fallback.

3. **Candidate (c) does not isolate at all, and its own premise does not survive measurement.**
   The claim was that an in-tenant attacker would have to *guess* a grant-id. It does not have
   to: `sm/v1/<tenant>/stream/>` is admitted **and delivers**. A single wildcard subscription
   recovered two grant-ids it had never been told — one belonging to a different identity, one
   never issued to anybody — together with both payloads. Unguessability is a secrecy property
   and buys nothing once the namespace above it is readable.

4. **Cost of choosing (a), stated.** Two conditions, both on the gateway rather than the broker:
   - **The gateway fully owns the `sub` claim.** The branch a browser can read *is* its token's
     `sub`, so `sub` is the authorization decision and must never carry user-supplied text.
     `BE-D5`'s post-RBAC ticket already mints it, so this constrains the minter, not the design.
   - **`sub` must not contain `/`.** Solace's docs, verbatim: *"you must ensure that client
     usernames or MQTT client IDs do not contain the '/' character … Note that the event broker
     does not enforce this restriction."* — [Granting Clients Access](https://docs.solace.com/Security/Granting-Clients-Access.htm#Using),
     the same page that defines the `$client-username` substitution grammar. Unenforced by the broker, so the gateway must enforce
     it; the failure mode is a silent authorization gap, not an error. Grant-ids are opaque, so
     this is cheap — but it must be an explicit, tested rule.

### ⚠️ Gap 1 of 2 — only the USERNAME level substitutes, so one profile serves ONE tenant

**Measured.** The exception the spike ran is `sm/v1/`**`acme`**`/stream/$client-username/>`. The
tenant level is a **literal**; nothing substitutes it. Under `default → acl-subst`, the same
session that was allowed its own branch `sm/v1/acme/stream/grant-777/s1` was **refused**
`sm/v1/globex/stream/grant-777/s1` — its *own* substituted username under a different tenant —
with `AuthorizationError` / `SUBSCRIBE_ACL_DENIED`.

That is good news for `I5` (the tenant edge is broker-enforced, by the literal) and bad news for
the zero-write claim, because of a structural fact: **a Message VPN has exactly one `default`
client username.** One fallthrough, one profile, one tenant literal.

And the design puts **multiple tenants inside that one VPN**. Two sources, quoted rather than
paraphrased:

- [`BE-D2`](decisions.md): *"Install provisions **one dedicated Message VPN** for SM
  (client-usernames, ACL profiles, the OAuth profile of `BE-D5`)"* — one VPN per SM install, not
  one per tenant.
- [design §4](design.md), Topic taxonomy: *"**Tenant is a topic level**, so broker ACLs can scope
  subscriptions by prefix — the mesh-side half of `I5`. The authoritative tenant is still the
  server-derived claim (`BE-D5`); the topic level exists so the ACL can enforce it without
  parsing payloads."* Restated in [design §11](design.md) (Security analysis) as the required
  negative test *"tenant A's token cannot subscribe `sm/v1/<tenantB>/…`"*, with the fixture in
  design §14.

So several tenants share one VPN and the ACL is the thing that must separate them — which is
exactly the job the single `default` fallthrough profile cannot do for more than one tenant at a
time. Task 4 provisioned `acme` *and* `globex` on this very VPN, so the shape is not
hypothetical. The zero-broker-write result is therefore **measured single-tenant and does not
compose across tenants as written.** Three routes out, each with a price this document does not get to
skip:

| Route | What it costs |
|---|---|
| **1. Wildcard the tenant level** — `sm/v1/*/stream/$client-username/>` | The broker stops enforcing the tenant edge entirely; `I5` collapses onto grant-id uniqueness, i.e. onto exactly the "unguessable ids" premise that candidate (c) was rejected for. Directly at odds with residual exposure #1 below. **Untested** — the spike never provisioned a tenant wildcard, so even its syntax is unverified |
| **2. Fold the tenant into `sub`** — mint `sub: "<tenant>~<grantId>"` and use `sm/v1/stream/$client-username/>` | A `BE-D4` topic-taxonomy change: the tenant stops being a topic level. Keeps zero writes and keeps a single profile, but the tenant edge becomes a property of the gateway's `sub` construction rather than of the broker's exception — a smaller version of the same trust shift as route 1, and it re-opens the `/`-in-`sub` rule as a correctness-critical constraint on the separator |
| **3. One profile + client-username object per tenant** | Per-tenant, not per-grant, so the object count stays small and long-lived. **But this is candidate (c)'s cost model**, and the measured precedence order means a client-username object **beats** the `default` fallthrough — so subjects would have to be provisioned per tenant, and the zero-write property is gone for anything that rides a provisioned username. Whether a per-tenant username can host per-grant substitution for many subjects at once was **not measured** |

**This document does not pick a route, because the facts do not force one.** Route 1 trades away
the only boundary the broker currently enforces; route 2 changes a contract; route 3 gives back
the property the mechanism was chosen for. **Routed to the architect group alongside the notify
options below** — the two decisions interact, since both are about what the fallthrough profile's
exception set may contain, and both must be settled before `BE-D5` ratifies.

### ⚠️ Gap 2 of 2 — the notify tier is not reachable, and the taxonomy is why

**A stream-only exception set is not shippable.** Under the zero-write recommendation the
VPN's `default` client username serves **every** browser subject, so that profile is the only
profile a legitimate console ever gets. Measured in the same run as the isolation result: with
`sm/v1/<tenant>/stream/$client-username/>` as the only exception, the session was **refused**
`sm/v1/<tenant>/notify/instance/i-1/health` — `AuthorizationError` / `SUBSCRIBE_ACL_DENIED`.
`BE-D6`'s hybrid model has browsers reading thin-notify events *and* raw-tier tails, so the
fallback profile must also carry the console's non-stream subscriptions.

Those additional exceptions **must themselves be substitution-scoped**, because a blanket
`sm/v1/<tenant>/notify/>` exception hands every subject every tenant-wide notify event and the
isolation claim leaks straight back out. **And design §4's notify topics as written today —
`sm/v1/<tenant>/notify/instance/<instanceId>/…`, `sm/v1/<tenant>/notify/catalog/package` — have
no per-subject level to substitute at.** Closing this means one of:

| Option | What it costs |
|---|---|
| Accept tenant-wide notify visibility for every in-tenant console session, isolating only the stream tier | The notify tier gets candidate (c)'s posture. Defensible only if "tenant" is the real confidentiality boundary for notify events — which is an `I5`-scoped product decision, not a broker one. Notify payloads carry identifiers only (`I6`), which materially limits what leaks |
| Add a subject-scoped level to the notify topics, e.g. `sm/v1/<tenant>/notify/<subjectId>/…`, and fan out per subscriber | A topic-taxonomy change to `BE-D4` and a real fan-out cost on the publisher: one publish per interested subject instead of one per event |
| Keep the console's notify subscription off the mesh entirely (poll `/api/console/v1/*` for state, mesh for stream tails only) | Gives up `BE-D6`'s thin-notify liveness, which is most of `REQ-012` |

**Recommendation for gap 2: the first, explicitly and in writing, for v1** — it is the only one
that does not change a contract or drop a requirement, and `I6` already forbids notify payloads
from carrying values. But it is the architect group's call and it must be *made*, not defaulted
into. **No recommendation is offered for gap 1** (above): every route there gives something up
that this document was written to protect, so picking one silently would be the opposite of what
a spike result is for.

**Together, gaps 1 and 2 are what stands between this result and a complete `BE-D5`.** They are
one conversation, not two: both ask what the single fallthrough profile's exception set may
contain.

### Residual exposures under the chosen mechanism

Stated plainly, because the broker enforces less than the phrase "per-grant isolation" suggests:

1. **The broker enforces isolation *between* subjects; never *who is entitled to be a subject*.**
   Anyone who can mint a token the OAuth profile trusts can name any `sub` and read that branch.
   The entire entitlement decision lives in the gateway's post-RBAC ticket minting. The broker
   is a second line, not the line.
2. **The notify tier is not isolated** until gap 2 is closed — see its table.
3. **Cross-tenant isolation under the chosen mechanism is enforced by a topic LITERAL, not by
   the substitution** (gap 1). It holds for the single-tenant profile that was measured; it is
   **unmeasured** for every multi-tenant route, and under route 1 it would not exist at all.
4. **A ticket is a bearer credential for its branch for its whole lifetime.** There is no
   revocation short of key rotation (§Rotation) or waiting out `exp`. Ticket TTL *is* the
   revocation window; `BE-D5`'s ~15 min proposal should be ratified with that framing
   (design §16 question 3).
5. **An unknown subject fails safe by construction** — it gets branches named after itself,
   which nobody publishes to, and reads nothing. That is better than remembering to provision,
   and is why `default` should point at the substitution profile rather than be disabled.

### What the rejected candidates are still good for

- **(b) authorization groups — for coarse, long-lived scope separation, and it is the only
  candidate that can do it from the ticket.** Design §14's second fixture is *"a notify-scope
  ticket must not reach a stream topic."* Under (a) and (c) the exception set is a property of
  the ACL *profile*, so scope separation costs a second profile (and, under (c), a second
  client username) per tenant × scope. Under (b) the groups claim selects the profile **per
  token**, so the same `sub` can present a notify ticket or a stream ticket and get different
  exception sets with no per-identity broker object. Scopes are few and long-lived — exactly the
  shape (b) suits. *(Measured for (c); **inferred** for (b) — no test minted a
  `g-<tenant>-notify` / `g-<tenant>-stream` pair. If scope separation from the ticket is wanted,
  measure it before building on it.)*
- **(c) tenant-coarse — as the deliberate posture wherever the tenant edge genuinely is the
  boundary**, and as the shape of the notify tier under this document's recommendation. Its
  tenant isolation is real and was measured in both directions with in-tenant positive controls;
  it is only *per-grant* isolation that it does not attempt.

---

## JWKS rotation & key persistence

`BE-D5` requires overlapping rotation and a signing key that survives a gateway restart. Both
were measured, and the shape of the result is not what the decision text assumes.

**Refresh latency measured: adoption of a new key is demand-driven, ≤ ~60 s.**
The broker does **not** poll the JWKS endpoint while idle (12 minutes idle, zero fetches). What
makes it re-read is a **login presenting a `kid` it does not hold** — measured at **50 ms**
(rotation at `16:49:08.088`, JWKS fetch at `16:49:08.138`, token accepted on the first attempt)
— rate-limited to roughly **once per 60 s**: a rotation 23 ms after a fetch was refused for
55 s, and the next fetch landed at exactly **+60.313 s**. The committed test measures this
every run; the cold-start run recorded **60 292 ms**.

**`endpointJwksRefreshInterval` is a red herring for adoption, and the trap for revocation.**
Verbatim from the broker's served spec: *"The number of seconds between JWKS endpoint requests.
… The default value is `86400`."* Twenty-four hours, with a hard floor the broker enforces:
`{"endpointJwksRefreshInterval":10}` → `400` / *"The provided number is smaller than the minimum
value 60."* It governs the **background** refresh only.

**Force-refresh operation: ABSENT as a SEMP action; two config changes do it.**
The served SEMP 2.48 **action** spec offers nothing on OAuth profiles but `clearStats` — no
`refreshJwks`, no `refreshKeys`. (The broker *does* model this pattern where it wants to:
`/certAuthorities/{certAuthorityName}/refreshCrl` exists. The absence for JWKS is a real gap.)
Measured with no login traffic at all, quiesced past the 60 s floor first:

| Lever | Result |
|---|---|
| `PATCH endpointJwks` to the **same** value | no fetch in 30 s — an unchanged value is not a change |
| `PATCH endpointJwks` to a **different** value | immediate re-read |
| `PATCH enabled:false` then `enabled:true` | immediate re-read, at the cost of a window in which every OAuth login on that profile is refused |

**Required overlap window: at least one full ticket TTL after you stop signing with a key —
and rotation is NOT revocation.** The two directions are asymmetric:

- *Adoption* is fast because new tickets carry the new `kid` and that is itself the stimulus.
- *Retirement* is not. Removing a key from the published JWKS does **not** revoke it: the broker
  serves from cache, and a token signed with an evicted key still **succeeds** — so it generates
  no unknown-`kid` stimulus and would never prompt the re-read that would kill it. **Measured
  with absolute timestamps, presenting ONLY the evicted key** so nothing else could trigger a
  re-read; the issuer's JWKS fetch counter is shown alongside, and it does not move:

```console
2026-08-27T17:34:51.509Z  minted oldToken kid=spike-5 ttl=60m -> connected
2026-08-27T17:34:51.558Z  rotate #1 -> {"kid":"spike-6","kids":["spike-5","spike-6"]}
2026-08-27T17:34:51.583Z  rotate #2 -> {"kid":"spike-7","kids":["spike-6","spike-7"]}
2026-08-27T17:34:51.605Z  published JWKS now = spike-6,spike-7; oldToken kid spike-5 present? false
2026-08-27T17:34:51.622Z  +  0s  evicted-key token -> connected   jwksFetchCount=1
2026-08-27T17:35:21.832Z  + 30s  evicted-key token -> connected   jwksFetchCount=1
2026-08-27T17:35:52.009Z  + 60s  evicted-key token -> connected   jwksFetchCount=1
2026-08-27T17:36:22.199Z  + 91s  evicted-key token -> connected   jwksFetchCount=1
2026-08-27T17:36:52.373Z  +121s  evicted-key token -> connected   jwksFetchCount=1
2026-08-27T17:37:22.578Z  +151s  evicted-key token -> connected   jwksFetchCount=1
…  [25 further poll rows elided; the run polled every 5s, 31 rows in all,
    every one of them identical in form: "connected   jwksFetchCount=1"]
2026-08-27T17:37:27.580Z  --- now present ONE token on the CURRENT kid (the stimulus) ---
2026-08-27T17:37:27.586Z  fresh kid=spike-7 -> connected          jwksFetchCount=2
2026-08-27T17:37:27.656Z  +  0s  evicted-key token -> refused(AuthenticationError/AUTH_FAILED)   jwksFetchCount=2
2026-08-27T17:37:27.657Z  DIED 0s after the stimulus appeared
2026-08-27T17:37:27.657Z  final: jwksFetches=[{"at":"2026-08-27T17:22:31.762Z","kids":["spike-4","spike-5"]},
                                              {"at":"2026-08-27T17:37:27.617Z","kids":["spike-6","spike-7"]}]
```

  **151 seconds — 2 min 31 s — of continuous retries on a key that was no longer published**,
  with the fetch counter pinned at 1 throughout (the broker's last read was 15 minutes earlier,
  at `17:22:31.762Z`). It died **70 ms** after a single login on the current `kid` appeared, at
  which point the broker re-read (`17:37:27.617Z`) and the counter moved to 2. So what ends an
  evicted key's life is *other* traffic, not the eviction. That transcript is an uncommitted
  measurement (see the spike README's Task 5 deviation 8); the committed test drives the same
  thing with the stimulus deliberately present and recorded **60 538 ms** on the cold-start run,
  because there the ~60 s fetch floor was the binding constraint rather than the absence of
  traffic.

So the install runbook rule is: **keep a retired key published for at least one full ticket TTL
plus one refresh floor** (≥ ~16 min at `BE-D5`'s 15-minute ticket), and treat "rotate the key"
as **not** an emergency revocation primitive. If a key must be killed *now*, the only lever is a
config change on the OAuth profile (above), and even then live sessions are unaffected — only
new logins are. Emergency revocation of *sessions* is a different problem this spike did not
solve.

The refusal an evicted key produces is **`401` / `AuthenticationError` / `AUTH_FAILED` /
"Unauthorized"** — byte for byte the same shape as an expired ticket. A client cannot tell
"your signing key was rotated out" from "your ticket expired".

**Issuer-restart persistence verified: YES.** The issuer was killed and relaunched; the active
`kid` and the full published set were identical afterwards (`spike-3`, `["spike-2","spike-3"]`),
and a ticket minted **before** the restart still connected. Nothing was re-fetched to make that
work — the broker's cached key was unchanged because an identical JWK set was republished. The
test runs *after* the rotation test on purpose, so what survives is the rotated state rather
than the first-boot one. `BE-D5`'s *"a gateway restart must never invalidate every live ticket"*
holds, provided the gateway persists its key the way the spike issuer persists `keys.json`.

---

## Renewal

**`updateAccessToken` behaviour observed: it is a full reconnect, and the SDK does not tell the
application it happened.**

First, a correction to how `BE-D5`/`FE-D19` should describe the API: **`SolaceClient` exposes no
public `updateAccessToken`.** The method exists only on the protocol adapter
(`src/protocols/smf/smf-adapter.ts:733`) and is reached through the `onTokenExpired` callback
configured at connect (`src/client.ts:183-207`). Frontend code will call
`refreshBeforeExpirySeconds` + `onTokenExpired`, not a renew method. The adapter's implementation,
read from source, is literally: store the subscriptions → `session.disconnect()` → `connect()`
with the new token → re-`subscribe()` each one.

Measured on a live subscribed session with a publisher running throughout at a 10 ms cadence:

| Observation | Measured |
|---|---|
| The broker session is **replaced** | the pre-renewal `clientName` is gone from the SEMP monitor; a different one serves the same client |
| Events the application sees | `auth:tokenRefreshed` × 1. **Zero** `disconnected`, **zero** `reconnected` — the SDK emits `disconnected` only for a user-requested disconnect |
| Subscriptions | restored automatically by the adapter; traffic resumes |
| Messages published during the reconnect | **lost.** 2 lost on each of three consecutive runs, 1 on the cold-start run, with the longest arrival gap (~30–40 ms) on the seam. At a 50 ms cadence, zero were lost — the outage is real but short |

**Chart-seam consequence confirmed: re-backfill on renewal.** Direct messaging has no replay, so
the restored subscription brings the *subscription* back and never the *messages*. A
data-bearing `BE-D6` tail must re-backfill across every renewal, on the console's own policy —
there is neither a signal to react to nor a replay to recover from. The deterministic fact
underneath, which no sampling rate can hide, is the session replacement.

**And the renewal deadline is hard.** `disconnectOnTokenExpirationEnabled` defaults to `true`
and is `true` on the tested VPN — verbatim: *"Enable or disable the disconnection of clients
when their tokens expire."* Measured: a live, subscribed session on a 30 s ticket was
disconnected by the broker **~31 s** after connect. Miss `exp` and the mesh session is gone
mid-chart. Combined with the observability findings below, **the gateway must re-ticket
proactively, on its own clock, well before `exp`** — reactive re-ticketing is not possible,
because the broker's refusal carries no reason.

### Observability — what a console can and cannot see (four measured facts)

These are `BE-D5`/`FE-D19` design consequences, not test-harness trivia.

1. **A publish denial is INVISIBLE to the client.** A browser publish to a topic its ACL denies
   **resolves successfully** — for direct *and* for guaranteed delivery. Nothing throws, nothing
   is emitted, the session stays up. **The guard must therefore be broker-enforced, and no
   product code may treat "publish returned" as "publish authorized."** The spike proves the
   denial from the broker's side instead: a witness subscriber receives nothing, and the
   per-client `publishTopicAclRxDiscardedMsgCount` — specifically the publish-*topic-ACL*
   discard counter — increases by exactly the number of denied publishes. Every browser-facing
   ACL profile in the spike keeps `publishTopicDefaultAction: disallow` with an **empty**
   `publishTopicExceptions` collection, asserted against SEMP config rather than by attempting a
   publish, precisely because the client cannot observe it.
2. **A subscribe denial IS visible** — `AuthorizationError` / `ErrorCode.SUBSCRIBE_ACL_DENIED`,
   message *"Subscribe denied: Subscription ACL Denied"*. So the asymmetry a console must live
   with is: **subscribe failures are actionable, publish failures are silent.**
3. **By default the SDK retries a rejected login FOREVER.** A broker `403` is retried about once
   a second indefinitely: `connect()` never settles and the caller sees a hang, not an error.
   With `connectRetries: 0` it becomes a clean rejection in ~10–90 ms carrying the broker's own
   text. **Product consequence: the console must bound its connect retries**, or an authorization
   failure at the mesh is indistinguishable from the mesh being down and the user is told
   nothing.
4. **There are FOUR distinct broker refusal shapes, and one of them is a trap.**

| # | Trigger | Shape |
|---|---|---|
| 1 | token carries no `groups` claim, or `groups: []` | `403` / `LOGIN_FAILURE (13)` — *"No Authorization Groups were retrieved from the Authorizing Server"* |
| 2 | `groups` names a group that does not match, **and ≥1 group is enabled** | `403` / `LOGIN_FAILURE (13)` — *"No matching configured Authorization Group was found (or all such matching groups are disabled)"* |
| 3 | `groups` names a group that does not match, and the VPN has **zero enabled groups** | `403` / `LOGIN_FAILURE (13)` — *"No Authorization Groups in enabled state were configured on the appliance"* |
| 4 | expired ticket, unknown signing key, bad signature, wrong audience, unreachable JWKS | **`401`** / `AuthenticationError` / `AUTH_FAILED` — *"Unauthorized"*, **and nothing more** |

⚠️ **Row 2's parenthetical lies about row 3.** Its text advertises *"or all such matching groups
are disabled"*, but when the VPN has no enabled authorization group at all the broker
short-circuits before the match and emits row 3's different string. Measured, with a control.
Anything that matches on refusal text — an operator runbook, a log alert, a client-side error
mapper — must handle all three strings and must not trust row 2's parenthetical.

Rows 1–3 surface as `ConnectionError` / `CONNECTION_FAILED` carrying the broker's explanatory
text; row 4 surfaces as `AuthenticationError` / `AUTH_FAILED`. **So a client can tell an
authentication problem from an authorization-group problem by error class — but it can never
tell *why* within row 4.** Expired, rotated-out, bad signature and unreachable JWKS are
indistinguishable. There is no truthful "your session expired" message renderable from the mesh
error alone, and no usable reactive signal to drive re-ticketing off. Measured timing: refused
at +2 s, +6 s and +31 s past a 1 s-lifetime token, in 23–76 ms each, while a 30 s token used
immediately connects — so the broker allows **no meaningful clock-skew grace**, under a second,
and a gateway must not rely on one.

---

## Reverse proxy

**nginx timeouts required: a floor of ~10 s, measured. Nothing exotic is needed.**

Task 1 assumed *"SMF keepalives are minutes apart, so nginx's default 60s would kill idle
sessions"* and set `proxy_read_timeout`/`proxy_send_timeout` to 3600 s. **The assumption was
wrong.** Measured from the broker's own per-client byte counters on an idle, subscribed session:
keepalives every **3.0 s in both directions** (12 bytes client→broker, 20 bytes broker→client).
That matches solclientjs's own defaults read from its source — `keepAliveIntervalInMsecs`
returns `3000` when unset, `keepAliveIntervalsLimit` returns `3`, so the client gives up after
~9 s of silence.

Confirmed end to end: with the proxy timeouts lowered to **10 s** (verified inside the
container) an idle subscribed session survived **60 s** with no disconnect, at the broker and at
the SDK. So **nginx's own 60 s default would have been fine**, with a 20× margin over the
keepalive.

The spike keeps 3600 s, and the reason is now labelled as a judgement rather than a measurement:
a **browser** throttles timers in a backgrounded tab, so a real console's keepalives may stretch
far past 3 s — a Node client cannot exercise that. **Recommendation for the install runbook:**
no less than 10 s (the measured floor), and a generous value such as 3600 s in packaging, with
the background-tab behaviour verified in a real browser before the number is treated as settled.

**Anything SMF-over-ws needed beyond `Upgrade` headers: no.** The working configuration is
exactly `proxy_http_version 1.1`, `proxy_set_header Upgrade $http_upgrade`,
`proxy_set_header Connection "upgrade"`, and the timeouts. `proxy_pass http://solace:8008;`
with **no trailing URI** forwards the original `/mesh` path unchanged and the broker's web
transport accepts the upgrade on it — solclientjs logs
`Creating transport session wss://localhost:9443/mesh` followed by `UP_NOTICE`. **No path
rewrite is needed, so the production `wss://<origin>/mesh` shape of design §5 works as
designed** — verified across baseline traffic, rotation, renewal-reconnect and
expiry-disconnect. TLS was terminated at nginx from the first test, so none of these findings
are plaintext-only artefacts.

This also answers design §16 question 5 for the spike's shape: nginx terminated TLS and proxied
the websocket, and nothing about SMF required the gateway to proxy it in Spring. The
Spring-vs-nginx choice remains a packaging decision, but it is not forced by the protocol.

---

## Broker version floor

**Features actually used, and the minimum version carrying them** — design §16 question 1.

| Feature used | Resource / property | Floor |
|---|---|---|
| OAuth resource-server client authentication | `/msgVpns/{v}/authenticationOauthProfiles` with `oauthRole: resource-server`, `endpointJwks`, `usernameClaimName`, `resourceServerRequiredIssuer` / `…RequiredAudience` + their `…ValidateEnabled` flags | present at SEMP 2.48; **requires the Solace ENTERPRISE image** — see below |
| ACL profiles + topic exceptions, incl. the `$client-username` substitution | `/msgVpns/{v}/aclProfiles`, `…/subscribeTopicExceptions`, `…/publishTopicExceptions` | present at SEMP 2.48 |
| Client usernames as the authorization binding | `/msgVpns/{v}/clientUsernames` | present at SEMP 2.48 |
| ACL-denial evidence | monitor `/msgVpns/{v}/clients` → `publishTopicAclRxDiscardedMsgCount`, `clientUsername`, `originalClientUsername`, `aclProfileName` | present at SEMP 2.48 |
| Session disconnect at token expiry | `disconnectOnTokenExpirationEnabled` | present at SEMP 2.48, default `true` |
| JWKS refresh interval | `endpointJwksRefreshInterval` (default `86400`, minimum `60`) | present at SEMP 2.48 |
| **Candidate (b) only** — authorization groups from token claims | `/msgVpns/{v}/authorizationGroups`, `authorizationGroupsClaimName`, `authorizationGroupsClaimStringFormat` | **SEMP 2.32** — `authorizationGroupsClaimStringFormat` is marked *"Available since 2.32"* in the broker's own served spec |

Two constraints that are **deployment** facts, not spike artefacts:

1. **OAuth client authentication requires the Solace *Enterprise* image.** The Standard image has
   no working `authenticationOauthProfiles` at runtime; the webAppSDK repo's own OAuth
   integration test skips itself on Standard for the same reason. `BE-D5` rides on a broker
   feature the customer must actually have. This belongs in the install prerequisites, and it is
   a real question to put to a customer whose mesh runs Standard.
2. **`authenticationOauthProfiles.oauthProfileName` may not contain hyphens** —
   `^[A-Za-z0-9_]*$`, enforced by SEMP. Cosmetic, but it will bite the first person who writes
   `sm-mesh-oauth` in a runbook. ACL profile names, client usernames and VPN names accept
   hyphens.

**Recommended floor to write into the install runbook: SEMP 2.32 / Enterprise edition**, which
covers every candidate including (b). Everything the *chosen* mechanism needs is older than
that; 2.32 is quoted because it is the floor for the scope-separation option this document
leaves open. **Verified only at 2.48** — the 2.32 figure is the broker's own "available since"
annotation, not a tested version, and should be confirmed against Solace's version matrix before
it is published as a supported minimum.

---

## What Gate G1 closes, and what it does not

**Closes.** The mechanism question `BE-D5` left UNSETTLED is answered with a measured choice,
a measured cost, and measured residual exposures. Rotation, key persistence, renewal, the
refusal taxonomy, the reverse-proxy shape and the version floor are all measured.

Design §14 names **six** required security fixtures; **all six were exercised** against a real
broker and are ready to lift into plan 002's CI.
**Their attribution matters and is stated, because the two `I5`/scope fixtures were measured
under candidate (c)'s per-tenant client usernames, not under the chosen mechanism:**

| design §14 fixture (verbatim) | Measured under | Where |
|---|---|---|
| *tenant A token vs tenant B topic (deny)* | **candidate (c)** — `tenant-acme` / `tenant-globex` client-username objects | `tests/03`, both directions, each with an in-tenant positive control |
| *notify-scope token vs stream topic (deny)* | **candidate (c)** — `acl-acme-notify` + `tenant-acme-notify`, a second profile/username pair | `tests/03` |
| *expired ticket (deny)* | candidate-independent (OAuth token validation) | `tests/03`, with a still-valid control on the same minting path |
| *stream grant expires with token* | candidate-independent | `tests/04` — a live, subscribed session on a 30 s ticket is disconnected by the broker at ~31 s (`disconnectOnTokenExpirationEnabled`), so the stream subscription dies with the ticket |
| *old token after key rotation (deny once refresh window passes)* | candidate-independent | `tests/04` rotation, eviction leg — 60 538 ms on the cold-start run |
| *new token before broker JWKS refresh (documented behaviour, tested)* | candidate-independent | `tests/04` rotation, adoption leg — refused until the broker re-reads, then accepted |

Three further measurements beyond §14's list, worth lifting for the same reasons:

| Extra | Measured under |
|---|---|
| `I5` at **delivery** on a live receiving session (not only at subscription admission) | **candidate (c)** |
| Browser identities subscribe-only, asserted from SEMP **config** because a publish denial never reaches the client | all profiles, including `acl-subst` |
| Cross-tenant deny **under the chosen mechanism** | the substituted-branch probe in the composition test — a session on `acl-subst` refused `sm/v1/globex/stream/<its own sub>/s1`. **Enforced by the topic LITERAL, not by the substitution** (gap 1) |

So plan 002 must not lift the `I5` and scope-separation fixtures as if they validated
candidate (a): they validate a per-tenant-client-username arrangement, which is candidate (c)'s
shape. Under the chosen mechanism the tenant edge has exactly **one** measurement behind it —
the last row above — and it is unmeasured for every multi-tenant route in gap 1.

**Does not close.**

- **Gap 1 — how candidate (a) spans MULTIPLE TENANTS on one VPN.** A VPN has one `default`
  client username, the tenant level of the exception is a literal, and `BE-D2` provisions **one
  dedicated VPN per install** while design §4/§11 make **tenant a topic level inside it** that
  the ACL must enforce. Three routes are priced in §Chosen mechanism; none is recommended,
  none is measured, and the zero-broker-write result is **single-tenant only** until one is
  chosen. `BE-D5` cannot ratify candidate (a) without it.
- **Gap 2 — the notify-tier exception shape** (§Chosen mechanism). Three options costed, first
  one recommended, architect group's call. Also blocks `BE-D5`.
- **Scope separation from the ticket**, if wanted, is candidate (b)'s territory and is
  **inferred, not measured**.
- **Emergency session revocation.** Rotation does not do it; ticket TTL is the only lever.
- **`disconnectOnTokenExpirationEnabled` interaction with renewal timing** under real network
  jitter — measured only on a loopback broker.
- **Gate G2 is untouched.** Every auth-bearing task still waits on open item 24 and the `D10`
  amendment; nothing in this document authorizes building login, tickets or RBAC checks.

**Evidence base.** `backend/spikes/mesh-authz/` — 5 test files, **30 tests, green from a
cold start in 3 min 30 s** with the broker volume, the TLS certs and the issuer's private key
all deleted first, rebuilt by the committed scripts alone. Every negative asserts the specific
refusal shape rather than a bare rejection, every session asserts its effective
`clientUsername`/`aclProfileName` from the SEMP monitor before any subscribe outcome is
believed, and each mechanism carries a prove-it-fires plant showing the test goes red for the
intended reason. The harness is explicitly throwaway: no Spring, no reuse into shipping code.
