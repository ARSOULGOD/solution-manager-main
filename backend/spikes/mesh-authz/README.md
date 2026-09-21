# Gate-G1 spike — broker authorization for browser mesh access

**This is a throwaway harness. It is explicitly non-production: no Spring, no reuse into
shipping code, nothing here is a scaffold for anything.** Its durable output is the written
G1 result (Task 5 of [`docs/plans/backend/001-mesh-foundations-and-gates.md`](../../../docs/plans/backend/001-mesh-foundations-and-gates.md))
plus the findings recorded in this file.

The spike exists to settle `BE-D5`'s deliberately-UNSETTLED question: **what mechanism
authorizes a browser's subscriptions on the customer's event mesh**, given that the browser
joins over the Solace WebApp SDK through a gateway-proxied `wss` endpoint. Task 1 (this
commit) builds the environment and proves the baseline; Tasks 2–4 run the three candidate
mechanisms against it; Task 5 writes the result.

Requirement: [`REQ-012`](../../../docs/requirements/REQ-012-event-driven-live-console.md).
Spec: [`docs/design/backend/design.md`](../../../docs/design/backend/design.md).

---

## What Task 1 built

| File | What it is |
|---|---|
| `docker-compose.yml` | Solace broker + nginx. Adapted from the webAppSDK repo's compose file. |
| `setup-certs.sh` | Self-signed CA + `server.crt`/`server.key` for nginx. Adapted from the webAppSDK repo's cert script. |
| `nginx.conf` | TLS-terminating websocket reverse proxy: `wss://localhost:9443/mesh` → `http://solace:8008`. |
| `issuer/issuer.mjs` | Local RS256 JWT issuer on `:8090` — stands in for the console-gateway's ticket minter (`BE-D5`). Keys persist to `issuer/keys.json`. |
| `provision.sh` | All broker configuration, via SEMPv2. Idempotent. |
| `tests/00-connect.test.mjs` | The G1 baseline: browser subscribes over OAuth+TLS, the internal identity publishes, and the browser is proven unable to publish. |
| `run-tests.sh` | What `npm test` runs — certs, provision, issuer, vitest, in sequence. |

## What Task 2 added

| File | What it is |
|---|---|
| `provision.sh` sections `[7/10]`/`[8/10]` | Candidate (a): ACL profile `acl-subst`, client usernames `grant-111`/`grant-222`, a grammar control, and the unmatched-subject probe exception. |
| `tests/01-substitution.test.mjs` | Candidate (a)'s six assertions. Picked up automatically — `run-tests.sh` runs `npx vitest run --no-file-parallelism` with no file list. |

## What Task 3 added

| File | What it is |
|---|---|
| `provision.sh` sections `[9/10]`/`[10/10]` | Candidate (b): ACL profile `acl-alpha`, authorization group `g-stream-alpha`, and a **second** OAuth profile `sm_spike_oauth_b` carrying the groups-claim mapping. `sm_spike_oauth` is left exactly as Task 1 wrote it — see "Coexistence cost" below for why that is forced, not stylistic. |
| `issuer/issuer.mjs` | `POST /token` now signs the **whole request body** into the JWT payload, so any candidate can mint any claim (`groups`, here) without the issuer growing a field per candidate. `iss`/`sub`/`aud`/`iat`/`exp` are still stamped by the issuer and override the body; `scopes` still defaults to `[]` so Tasks 1–2's token shape is unchanged. |
| `tests/02-authzgroup.test.mjs` | Candidate (b)'s seven assertions. |

## What Task 4 added

| File | What it is |
|---|---|
| `provision.sh` sections `[11/14]`–`[13/14]` | Candidate (c): ACL profiles `acl-acme` and `acl-globex` (each covering its tenant's whole notify **and** stream namespace) with client usernames `tenant-acme`/`tenant-globex`, plus `acl-acme-notify` + `tenant-acme-notify` — the second profile candidate (c) needs before notify-vs-stream scope separation is expressible at all. The section labels were renumbered `/10` → `/13` (and later → `/14`); nothing else in sections 1–10 changed. |
| `provision.sh` tail | One more evidence block: every client username and the ACL profile it binds. Under (c) that binding **is** the authorization decision, so it is printed rather than assumed. |
| `issuer/issuer.mjs` | `POST /token` honours an `expiresIn` **minting directive** (default `'15m'`, unchanged). It is stripped from the payload rather than signed as a claim, so a body that omits it produces byte-identical claims to Tasks 1–3. Needed by the expired-ticket fixture. |
| `tests/03-coarse-and-i5.test.mjs` | Candidate (c)'s exposure measurement plus **three of design §14's six** security fixtures — cross-tenant deny, notify-scope-vs-stream, expired ticket (§14's remaining three are token-lifecycle and live in `tests/04`) — and two extras beyond §14's list (`I5` at delivery, subscribe-only from SEMP config). Nine assertions. |

## What Task 5 added

| File | What it is |
|---|---|
| `issuer/issuer.mjs` | `POST /rotate` (overlap window as a mechanism) and `GET /stats` (a timestamped log of every JWKS fetch — the instrument behind every rotation number below). |
| `nginx.conf` | Comment only: Task 1's "keepalives are minutes apart" premise is replaced by the measured 3.0 s cadence. The values are unchanged. |
| `tests/04-rotation-renewal.test.mjs` | Six assertions — the deferred composition measurement, the force-refresh absence, rotation in both directions, key persistence across an issuer restart, renewal, and the expiry disconnect. |

Full findings in ["Task 5 — composition, JWKS rotation, key persistence, renewal"](#task-5--composition-jwks-rotation-key-persistence-renewal)
below; the written G1 result is
[`docs/design/backend/spike-authz-result.md`](../../../docs/design/backend/spike-authz-result.md).

### Identities

Two classes, with deliberately opposite publish postures (design §3 — *only backend modules
publish notify events*):

| Identity | Auth | ACL profile | Publish | Subscribe |
|---|---|---|---|---|
| `sm-internal` | basic (`sm-internal`/`sm-internal`) | `acl-internal` | `sm/v1/>` | `sm/v1/>` |
| browser subjects | OAuth (spike issuer) | `acl-browser-baseline` | **default disallow, no exceptions — ever** | `sm/v1/>` |

`sm-internal` is the only identity in the spike that may publish. It stands in for
`mesh-bridge`/the backend, and Task 10's integration test reuses it. Its password is a
throwaway that exists only in this harness; it is the one credential committed on purpose.

Every browser-facing ACL profile that Tasks 2–4 add must keep publish default-disallow with
**no** publish exceptions.

---

## Running it

```bash
./setup-certs.sh          # certs must exist BEFORE compose up — nginx bind-mounts ./certs
docker compose up -d      # broker takes ~1-2 min the first time
until curl -sf -u admin:admin http://localhost:8080/SEMP/v2/config/about/api; do sleep 5; done
npm install
npm install --prefix issuer   # the issuer is a separate package (jose); the root install misses it
npm test                      # provisions, starts the issuer, runs the tests
```

`npm test` installs `issuer/`'s dependencies itself if they are missing, so the second
`npm install` is belt-and-braces rather than required.

`npm test` does everything except `docker compose up`. **Leave the stack running** — Tasks 2–5
reuse it. `provision.sh` is idempotent, so re-running is safe.

Ports: `8080` SEMP · `8008` broker SMF-websocket (plaintext, reached only through nginx) ·
`9443` nginx `wss` · `8090` the issuer. The broker's own REST-TLS 9443 mapping from the source
compose file is **removed** — host 9443 belongs to nginx, because `wss://<origin>/mesh` is the
production path in design §5 and the spike tests TLS from the start (R6 finding 6).

---

## Broker version

```
SolOS Version: soltr_10.25.0.177        (image solace-pubsub-enterprise:10.25.0.177, linux/arm64)
GET /SEMP/v2/config/about/api  ->  {"platform": "VMR", "sempVersion": "2.48"}
```

**This answers design §16 question 1's floor for the features actually used.** Every SEMP
resource the spike touches is present at SEMP 2.48. Two version notes matter:

- **OAuth client authentication requires the Solace *Enterprise* image.** The Standard image
  has no working `authenticationOauthProfiles` at runtime. The webAppSDK repo's own OAuth
  integration test skips itself on Standard for exactly this reason. This is a real
  deployment constraint on `BE-D5`, not a spike artifact.
- `authorizationGroupsClaimStringFormat` is marked *"Available since 2.32"* in the served
  spec. Task 3's candidate (b) — authorization groups from token claims — will want that, so
  the floor for candidate (b) is at least SEMP 2.32. **Task 3 confirms it**: candidate (b) uses
  exactly that property plus `authorizationGroupsClaimName` and the `authorizationGroups`
  resource, all present here, so **2.32 is candidate (b)'s floor**.

---

## SEMP verification evidence (`I2`)

Every resource path and property name in `provision.sh` was read out of the broker's **own
served spec**, never from memory.

```console
$ curl -s -u admin:admin http://localhost:8080/SEMP/v2/config/spec | python3 -c "
import json,sys; spec=json.load(sys.stdin)
print([p for p in spec['paths'] if 'oauth' in p.lower() or 'aclProfile' in p][:20])"
['/msgVpns/{msgVpnName}/aclProfiles', '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}',
 '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}/clientConnectExceptions',
 '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}/clientConnectExceptions/{clientConnectExceptionAddress}',
 '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}/publishExceptions',
 '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}/publishExceptions/{topicSyntax},{publishExceptionTopic}',
 '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}/publishTopicExceptions',
 '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}/publishTopicExceptions/{publishTopicExceptionSyntax},{publishTopicException}',
 '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}/subscribeExceptions',
 '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}/subscribeExceptions/{topicSyntax},{subscribeExceptionTopic}',
 '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}/subscribeShareNameExceptions',
 '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}/subscribeShareNameExceptions/{subscribeShareNameExceptionSyntax},{subscribeShareNameException}',
 '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}/subscribeTopicExceptions',
 '/msgVpns/{msgVpnName}/aclProfiles/{aclProfileName}/subscribeTopicExceptions/{subscribeTopicExceptionSyntax},{subscribeTopicException}',
 '/msgVpns/{msgVpnName}/authenticationOauthProfiles',
 '/msgVpns/{msgVpnName}/authenticationOauthProfiles/{oauthProfileName}',
 '/msgVpns/{msgVpnName}/authenticationOauthProfiles/{oauthProfileName}/clientRequiredClaims',
 '/msgVpns/{msgVpnName}/authenticationOauthProfiles/{oauthProfileName}/clientRequiredClaims/{clientRequiredClaimName}',
 '/msgVpns/{msgVpnName}/authenticationOauthProfiles/{oauthProfileName}/resourceServerRequiredClaims',
 '/msgVpns/{msgVpnName}/authenticationOauthProfiles/{oauthProfileName}/resourceServerRequiredClaims/{resourceServerRequiredClaimName}']
```

Resources confirmed and used, with the property sets read from the same spec:

| Purpose | Path | Key properties |
|---|---|---|
| Message VPN | `/msgVpns` | `authenticationBasicEnabled`, `authenticationBasicType`, `authenticationOauthEnabled`, `authenticationOauthDefaultProfileName`, `serviceWebPlainTextEnabled`, `maxMsgSpoolUsage` |
| OAuth profile | `/msgVpns/{v}/authenticationOauthProfiles` | `oauthRole` (`resource-server`), `issuer`, `endpointJwks`, `usernameClaimName`, `authorizationGroupsClaimName`, `resourceServerRequiredIssuer`/`resourceServerRequiredAudience` + their `…ValidateEnabled` flags |
| ACL profile | `/msgVpns/{v}/aclProfiles` | `publishTopicDefaultAction`, `subscribeTopicDefaultAction`, `clientConnectDefaultAction` (all `allow`/`disallow`) |
| Topic exceptions | `…/publishTopicExceptions`, `…/subscribeTopicExceptions` | `{publish,subscribe}TopicExceptionSyntax` (`smf`/`mqtt`), `{publish,subscribe}TopicException` |
| Client username | `/msgVpns/{v}/clientUsernames` | `password`, `aclProfileName`, `clientProfileName`, `enabled` |
| Client profile | `/msgVpns/{v}/clientProfiles` | `allowGuaranteedMsgSendEnabled`, `allowGuaranteedMsgReceiveEnabled`, `allowGuaranteedEndpointCreateEnabled` |
| ACL-denial evidence (monitor) | `/SEMP/v2/monitor/msgVpns/{v}/clients` | `publishTopicAclRxDiscardedMsgCount` |

---

## Findings — read these before Tasks 2–5

### 1. A browser publish denial is INVISIBLE to the client. This is the important one.

The Solace WebApp SDK publishes fire-and-forget: `SmfAdapter.publish()` ends at
`session.send(message)` with no ack correlation, and the adapter registers **no**
`REJECTED_MESSAGE_ERROR` handler. Measured behaviour on this broker: a browser publish to a
topic its ACL denies **resolves successfully** — for *direct* and for *guaranteed*
(`publishGuaranteed`) delivery alike. Nothing throws, nothing is emitted, the session stays up.

Consequences:

- The plan's Task 1 sketch, `await expect(c.publish(...)).rejects.toThrow()`, **cannot pass**,
  and making it pass would require weakening what is being proven. The negative test instead
  proves denial from the broker's side (see below).
- **A design consequence, not just a test one:** the console can never detect that its own
  publish was refused, so no product code may treat "publish returned" as "publish
  authorized". The guard has to be broker-enforced — which is exactly the posture design §3
  and `BE-D5` take, and this spike confirms nothing weaker would work.
- Anything that must know a send succeeded needs a different mechanism than the SDK's
  `publish()` return.

The webAppSDK repo reached the same conclusion for direct messages in its own ACL test
(`tests/integration/auth/auth-acl.test.ts`: *"Direct messaging publish is fire-and-forget —
broker silently drops ACL-denied messages without returning an error"*), and skipped the case.
This spike additionally establishes that switching to guaranteed delivery does **not** rescue
it, because the SDK never observes the ack either way.

**How the negative test proves denial instead.** Four assertions, in order:

1. A witness subscriber (`sm-internal`, subscribed to `sm/v1/>`) receives **nothing** the
   browser published.
2. The broker's per-client `publishTopicAclRxDiscardedMsgCount` increases by **exactly 2** —
   that counter is specifically the publish-*topic-ACL* discard counter, so the outcome cannot
   be explained by a lost connection or a dropped message.
3. None of that increase is charged to `sm-internal`.
4. The browser session is still connected afterwards.

Plus a positive control in the same test: the witness *does* receive a legitimately published
message, so "the witness heard nothing" is meaningful rather than a broken subscription.

**Prove-it-fires check (run manually, not committed).** Granting `acl-browser-baseline` a
publish exception on `sm/v1/>` and re-running turns the test red on assertion 1 with the two
browser-published topics listed:

```
AssertionError: expected [ …(2) ] to deeply equal []
+ Array [
+   "sm/v1/acme/notify/instance/i-1/health",
+   "sm/v1/acme/stream/g1/s1",
+ ]
```

Removing the exception returns it to green. The test measures the real thing.

### 2. `authorizationGroupsClaimName` defaults to `"groups"` and will 403 every login

Out of the box the OAuth profile demands an authorization-groups claim in the access token.
A token without one is refused at login with:

```
403 No Authorization Groups were retrieved from the Authorizing Server  (errorSubcode LOGIN_FAILURE 13)
```

`provision.sh` sets it to `""`, which means *fall back to the VPN's internal authorization* —
the client username derived from `sub` selects the ACL profile. Deriving groups **from** the
token is candidate (b), i.e. Task 3's subject; the baseline must not pre-empt it.

**Task 3 refines this in two ways** — see "Candidate (b)". It does *not* 403 every login: a
subject that has its own client-username object authenticates fine, because the client username
outranks the group lookup. And it is a per-**profile** switch, so candidate (b) runs on its own
OAuth profile and this one stays as written.

### 3. An OAuth subject with no matching client username becomes `default`

`usernameClaimName` is `sub`, and the spike mints `sub: "op-1"` — but the broker's monitor
shows the session's `clientUsername` as **`default`**, because no `op-1` client-username
object exists and the VPN's `default` client username is enabled. So:

- The ACL profile bound to `default` governs every un-provisioned browser subject. That is why
  `provision.sh` points `default` at `acl-browser-baseline` (subscribe-only) — it makes
  "unknown browser subject" fail *safe*.
- **Do not filter SEMP client queries by the token's `sub`** — the session will not be there.
  The negative test snapshots per-`clientName` counters across the VPN instead.
- Tasks 2–4 need to decide deliberately whether browser subjects get provisioned client
  usernames or ride the `default` fallback; that choice interacts with all three candidates.

### 4. `oauthProfileName` may not contain hyphens

SEMP rejects it: `'oauthProfileName' validation failed: Can only contain letters, numbers, and
underscores` / `did not match the pattern ^[A-Za-z0-9_]*$`. Hence `sm_spike_oauth`, while every
other object in the spike uses the hyphenated house style. ACL profile names, client usernames
and the VPN name all accept hyphens.

### 5. Access-token `typ` validation is on by default and expects `at+jwt`

`resourceServerValidateTypeEnabled` defaults to `true` with `resourceServerRequiredType`
`"at+jwt"`. The spike issuer signs with only `{alg, kid}`, so `provision.sh` turns type
validation off. Issuer and audience validation stay **on** (`sm-spike-issuer` / `sm-mesh`) —
those are the claims the design actually relies on. A real console-gateway should instead
stamp `typ: "at+jwt"` and leave the check enabled.

### 6. ACL topic exceptions are create/delete only

`…/publishTopicExceptions/{syntax},{topic}` serves `GET` and `DELETE`; a `PATCH` returns
`NOT_SUPPORTED`. The object is keyed entirely by its own content, so "already exists" is the
desired state. `provision.sh`'s `create` helper takes an `immutable` flag for these.

### 7. Do not set `ulimits.nofile` on the broker service

SolOS 10.25 refuses to start behind a lowered limit —
`POST Violation [022]: Hard resource limit nofile 1048576 is required, 42192 detected` — and
the router shuts down after ~10s with no SEMP ever coming up. Docker Desktop's default already
satisfies it. `shm_size: 1g` is the only resource knob the source compose file sets, and the
only one this one sets.

### 8. `proxy_read_timeout` is a spike measurement target, not a settled value

`nginx.conf` sets `proxy_read_timeout`/`proxy_send_timeout` to `3600s`. SMF keepalives are
minutes apart, so nginx's default 60s would kill idle sessions. **The right value is
design §16 question 5 and is not answered by Task 1** — the number here is only large enough
that the baseline is not measuring nginx's patience. Task 5 should measure the actual SMF
keepalive interval and state the minimum safe proxy timeout.

Also unmeasured so far, and worth recording when Tasks 2–5 run: whether the proxy adds
latency on the notify path, and how it behaves on token-renewal reconnects.

### 9. nginx's `/mesh` path passes through to the broker unchanged, and that works

`proxy_pass http://solace:8008;` (no trailing URI) forwards the original `/mesh` path. The
broker's web transport accepts the upgrade on it — solclientjs logs
`Creating transport session wss://localhost:9443/mesh` followed by `UP_NOTICE`. No path
rewrite is needed, so the production `wss://<origin>/mesh` shape works as designed.

---

## Candidate (a) — substitution-variable ACL

### Verdict: **PASS**, and cheaper than `BE-D5` feared — **no per-grant SEMP writes required**

One ACL profile, one topic exception, gives **every** grant-id its own stream branch. Adding a
grant costs **nothing** on the broker: no client-username object, no exception, no SEMP call.
That is the outcome `BE-D5` was hoping for and had no evidence for.

Read **both** "Does the claim→username mapping need pre-created client usernames?" **and
"Position on unmatched subjects"** below before quoting that sentence — the three caveats live in
two different sections. The *mechanism* is measured and the exact fallback *binding* it implies
is an inference this task left for Task 4/5 to re-measure (first section); and the fallback
profile needs more than the stream exception or **the notify tier breaks** (second section, and
gap 2 of the result doc).

The verdict rests on five measured facts, not on the doc:

| # | Fact | How it was proven |
|---|---|---|
| 1 | `grant-111` may subscribe `sm/v1/acme/stream/grant-111/s1` and **not** `…/grant-222/s1` | test 1 |
| 2 | The mirror holds for `grant-222` — same profile, opposite outcome, so the exception is substituted **per session** rather than matching one grant by luck | test 2 |
| 3 | The substitution follows the **authentication** username (the OAuth `sub`), not the authorization one | test 5 — see below |
| 4 | The plan's `${clientUsername}` spelling is **inert**: it is stored and matched as a literal topic level | test 4 |
| 5 | Grants stay subscribe-only — `acl-subst` has publish default-disallow and zero publish exceptions | test 6, asserted against SEMP config |

Every negative asserts `AuthorizationError` / `ErrorCode.SUBSCRIBE_ACL_DENIED`, never a bare
rejection: a timeout or a dropped session would also reject, and accepting that as the answer
is the R6 false-green class.

### Does the claim→username mapping need pre-created client usernames? **No.**

This is the question that decides candidate (a)'s cost, and the answer turned on a distinction
the SEMP 2.48 monitor spec makes between two usernames on the same session:

```
MsgVpnClient.originalClientUsername — "The original value of the client username used for
                                       Client authentication."
MsgVpnClient.clientUsername         — "The client username of the Client used for
                                       authorization."
```

Measured for a token whose `sub` is `grant-999`, for which **no client-username object exists**
(`tests/01-substitution.test.mjs`, printed by the run):

```
unmatched-subject probe: {"session":{"aclProfileName":"acl-browser-baseline",
  "clientName":"solclientjs/unknown-0.0.0-Unknown-0.0.0/2170600679/0005",
  "clientUsername":"default","originalClientUsername":"grant-999"},
  "ownBranch":{"outcome":"allowed"},
  "otherBranch":{"outcome":"acl-denied","name":"AuthorizationError",
                 "code":"SUBSCRIBE_ACL_DENIED","message":"Subscribe denied: Subscription ACL Denied"},
  "defaultBranch":{"outcome":"acl-denied","name":"AuthorizationError",
                   "code":"SUBSCRIBE_ACL_DENIED","message":"Subscribe denied: Subscription ACL Denied"}}
```

Read it carefully — it is the whole finding:

- Authorization fell through to `default` exactly as Task 1 finding 3 says, and the ACL profile
  applied was `default`'s (`acl-browser-baseline`).
- **`$client-username` nevertheless substituted `grant-999`** — the token's own `sub`. The
  session could subscribe `spike/probe/grant-999/x`.
- It could **not** subscribe `spike/probe/default/x`. That control is what rules out "the
  broker substituted the authorization username", which was the expected-and-expensive answer.
- It could **not** subscribe `spike/probe/grant-111/x`. That control is what rules out "the
  exception is simply permissive".

So `grant-111` and `grant-222` exist in `provision.sh` only to *select the ACL profile*, and one
binding should be able to do that job for everyone: point the VPN's `default` client username at
the substitution profile and every subject the gateway ever mints is isolated, with no broker
write per grant.

**Be precise about what is measured and what is inferred, because Task 5 and `BE-D5`
ratification read this file.**

- **Measured — the mechanism.** `$client-username` substitutes the *authentication* username
  (the token's `sub`) and does so even when authorization fell through to `default`. That is the
  fact everything else rests on, and it is nailed down by the probe plus its two controls.
- **Measured — a substitution-only profile works**, reached through a pre-created client username
  (`acl-subst`, tests 1–2).
- **Measured — `default` fallthrough enforces per-`sub` isolation**, on a profile that *also*
  carries a blanket `sm/v1/>` exception (`acl-browser-baseline`, test 5).
- **NOT measured — the two composed.** The recommended shape below binds `default` to a profile
  with **no** blanket exception. No test in this task ran that binding: the probe rode
  `acl-browser-baseline` because that is where `default` already points, and
  `acl-browser-baseline` is not the recommended profile. The composition is a well-supported
  inference from the three measurements above, not an observation.

**Task 4 or Task 5 should close that gap** — `PATCH msgVpns/sm-spike/clientUsernames/default
{"aclProfileName": "acl-subst"}`, then re-run the branch assertions with a `sub` that has no
client-username object. Deliberately not done here: re-measurement is Task 4/5's job, and
re-pointing `default` mid-task would change the profile Task 1's baseline test depends on.

**Two conditions come with it, and they are load-bearing:**

1. **The gateway fully owns the `sub` claim.** The branch a browser can read is exactly its
   token's `sub`. `BE-D5`'s post-RBAC ticket already mints that claim, so this is a constraint
   on the minter, not a new mechanism — but it means `sub` **is** the authorization decision and
   must never carry user-supplied text.
2. **`sub` must not contain `/`.** Solace docs, verbatim: *"you must ensure that client
   usernames or MQTT client IDs do not contain the '/' character … any MQTT client IDs or client
   usernames that contain '/' characters will never be able to match the `$client-id` or
   `$client-username` elements of an ACL rule. Note that the event broker does not enforce this
   restriction."* Unenforced by the broker, so the gateway must enforce it. Grant-ids are
   opaque identifiers, so this is cheap — but it must be an explicit rule, because the failure
   mode is a silent authorization gap, not an error.

### Position on unmatched subjects (required by Task 1 finding 3)

A token whose `sub` is not a provisioned grant username **still authenticates and still
connects**. Nothing about candidate (a) changes that. What it gets is `default`'s ACL profile:

- In this spike that is `acl-browser-baseline`, whose blanket `sm/v1/>` subscribe exception lets
  it read **every tenant's** notify and stream traffic. Safe enough as a Task 1 baseline —
  browsers are subscribe-only — but **not** an isolation posture.
- **Under candidate (a) the fallback must be the substitution profile itself**, i.e. bind
  `default` to a profile that carries no blanket exception, only substitution-scoped ones. Then
  an unknown subject is not a special case at all: it gets branches named after itself, which
  nobody publishes to, and reads nothing. That is fail-safe by construction rather than by
  remembering to provision.

  ⚠️ **`sm/v1/<tenant>/stream/$client-username/>` alone is NOT a sufficient exception set, and
  shipping it verbatim would break the notify tier.** Under the zero-per-grant-write
  recommendation `default` serves **every** browser subject, not only unknown ones — so this
  profile is the only profile a legitimate console ever gets. `BE-D5`'s hybrid design has
  browsers reading thin-notify events as well as raw-tier stream tails; with a stream-only
  exception set they could not subscribe `sm/v1/<tenant>/notify/…` at all. The fallback profile
  must additionally carry whatever non-stream subscriptions the console needs — **and those must
  themselves be substitution-scoped**, because a blanket `sm/v1/<tenant>/notify/>` exception
  hands every subject every tenant-wide notify event and the isolation claim leaks straight back
  out. Working out the substitution-scoped shape of the notify subscriptions is a design
  question for Task 5 / `BE-D5`, not something this task measured, and the notify topics as
  design §4 writes them today (`sm/v1/<tenant>/notify/…`, no grant level) do not obviously admit
  one.
- Disabling the `default` client username instead would refuse the connection outright. Either
  is defensible; the first degrades more gracefully. Neither was measured in this task — see the
  measured/inferred split above.

The residual risk is unchanged and belongs to the gateway: anyone who can mint a token the
broker's OAuth profile trusts can name any `sub` and read that branch. The broker enforces
*isolation between subjects*, never *who is entitled to be a subject*.

### The exact broker config that made it work

Grammar first, because the plan guessed it wrong. **`I2`: it is not in the SEMP spec at all.**
The broker's served SEMP 2.48 spec describes `subscribeTopicException` as *"The topic for the
exception to the default action taken. May include wildcard characters."* — no substitution.
The broker's own CLI help agrees:

```console
$ printf 'enable\nconfigure\nacl-profile acl-browser-baseline message-vpn sm-spike\n\
subscribe-topic\nexceptions ?\n' | <pty> cli -A
   COMMAND:
       exceptions  [smf | mqtt] list <exception-list>
   PARAMETERS:
       list <exception-list>    - [[<topic>[1..250 chars]]*] List of exceptions
```

And `grep -ri "substitution variable"` across the entire broker load returns nothing. The
grammar lives only in Solace's docs
([Managing ACLs](https://docs.solace.com/Security/Managing-Access-Control-Lists.htm),
[Granting Clients Access](https://docs.solace.com/Security/Granting-Clients-Access.htm#Using)),
verbatim:

> `$client-username` —For this variable, the event broker will substitute the client username
> provided by the client or retrieved from the TLS client-certificate during client login.
>
> Substitution variables must appear alone at any level in the topic exception, but they can
> appear multiple times in a single exception. For example, `FRUIT/$client-id/$client-username/APPLES`
> are valid uses of substitution variables. While `FRUIT/$client-id*` is not valid because
> `$client-id` does not stand alone at a level.
>
> The strings `$client-id`, `$client-username`, and `$client-username-hash` have special meaning
> only for ACL exception topics. If they are used in regular topic subscriptions, they are
> treated as literal strings and have no special meaning.

So the syntax is **`$client-username`** — bare `$`, hyphenated, alone at its level. Not
`${clientUsername}`. The config, as the broker stores it:

```console
$ ./provision.sh | tail
Subscribe topic exceptions as the broker stores them:
  acl-subst              smf   sm/v1/acme/stream/$client-username/>
  acl-subst              smf   sm/v1/acme/stream2/${clientUsername}/>
  acl-browser-baseline   smf   sm/v1/>
  acl-browser-baseline   smf   spike/probe/$client-username/>
```

| Object | Setting |
|---|---|
| ACL profile `acl-subst` | `clientConnectDefaultAction: allow`, `publishTopicDefaultAction: disallow` (**no** publish exceptions), `subscribeTopicDefaultAction: disallow` |
| — its subscribe exception | syntax `smf`, topic `sm/v1/acme/stream/$client-username/>` |
| Client usernames `grant-111`, `grant-222` | `aclProfileName: acl-subst`, `enabled: true`, **no password** (OAuth only). Hyphens are fine — finding 4's `^[A-Za-z0-9_]*$` restriction is specific to `oauthProfileName`. |
| OAuth profile `sm_spike_oauth` | unchanged from Task 1: `usernameClaimName: "sub"` is what feeds `$client-username`. |
| `acl-browser-baseline` | Task 1's profile plus one **subscribe** exception, `spike/probe/$client-username/>`, for the unmatched-subject measurement. Outside `sm/v1` so the blanket `sm/v1/>` exception cannot mask the result; publish posture untouched. |

### Prove-it-fires (run manually, not committed)

Deleting the one substitution exception and re-running turns exactly the two branch tests red,
on the positive assertion, for the right reason:

```console
$ curl -s -X DELETE -u admin:admin \
  ".../aclProfiles/acl-subst/subscribeTopicExceptions/smf,sm%2Fv1%2Facme%2Fstream%2F%24client-username%2F%3E"
200
$ npx vitest run tests/01-substitution.test.mjs
   × candidate a: grant-111 can subscribe its own stream branch, not grant-222s
   × the branch follows the session, not the profile: grant-222 is the mirror image
AssertionError: promise rejected "AuthorizationError: Subscribe denied: Sub… { …(5) }" instead of resolving
 Tests  2 failed | 4 passed (6)
```

`./provision.sh` restores it (the `create` helper re-POSTs it) and the suite returns to green.

### Finding: a subscribe denial IS visible to the SDK — unlike a publish denial

Task 1 finding 1 warned that a publish denial never surfaces. Subscribe is the opposite, and it
was verified in the SDK source before being relied on:
`SmfAdapter.subscribeToBroker()` (`src/protocols/smf/smf-adapter.ts:830-897`) calls
`session.subscribe(dest, true /* requestConfirmation */, topic /* correlationKey */, 10000)` and
registers a `SUBSCRIPTION_ERROR` listener that rejects with `AuthorizationError` /
`ErrorCode.SUBSCRIBE_ACL_DENIED` for broker subcodes 15/16/17/125. Measured message:
`"Subscribe denied: Subscription ACL Denied"`.

So the asymmetry a console must live with is: **subscribe failures are actionable, publish
failures are silent.** Task 5 should state it that way — it is a UI-behaviour fact, not only a
test-harness one.

---

## Candidate (b) — authorization groups from token claims

### Verdict: the mechanism **PASSES**; as `BE-D5`'s per-grant authorization mechanism it **FAILS**

Both halves are measured, and both matter.

**It works.** A token carrying `groups: ["g-stream-alpha"]` binds to the broker authorization
group of that name, and the session is authorized by **that group's ACL profile** —
`acl-alpha`, whose single subscribe exception is `sm/v1/acme/stream/alpha/>`. The subject
`op-2` has no client-username object, so without the claim it would have fallen through to the
VPN's `default` and got `acl-browser-baseline`. It did not:

```
candidate (b) session: {"aclProfileName":"acl-alpha","clientUsername":"op-2",
                        "originalClientUsername":"op-2",
                        "clientName":"solclientjs/unknown-0.0.0-Unknown-0.0.0/0743853796/0001"}
```

`sm/v1/acme/stream/alpha/s1` resolves; `sm/v1/acme/stream/beta/s1` and
`sm/v1/acme/notify/instance/i-1/health` are both refused with
`AuthorizationError` / `SUBSCRIBE_ACL_DENIED`.

**It is the wrong shape for per-grant scoping.** Four measured costs, below, and the decisive
one is the first.

### 1. The per-grant cost: a SEMP write per grant, and it is worse than one write

**An authorization group is a broker object.** `/msgVpns/{v}/authorizationGroups/{name}` — a
configured, named, ordered, config-synced object, exactly like a client username. So one group
per grant means **one SEMP write per grant**, against a broker that is the customer's
production event mesh.

It is not even one write. The group only names an ACL profile; it does not carry topics. A
grant-scoped group needs its **own ACL profile too**, because the branch a group grants is
written into that profile's exceptions. Per grant, therefore:

| | Candidate (a) — substitution | Candidate (b) — authorization group |
|---|---|---|
| Broker writes to add a grant | **0** | `POST aclProfiles` + `POST …/subscribeTopicExceptions` (one per topic scope) + `POST authorizationGroups` — **3+** |
| Broker writes to revoke a grant | 0 (stop minting the `sub`) | the matching `DELETE`s, **and see cost 3** |
| Broker objects at steady state | 1 profile, 1 exception | 2 objects **per live grant**, unbounded |
| Grant identity lives in | the token's `sub` | broker configuration |

Candidate (b) is therefore viable **only for coarse, long-lived scopes** — a per-tenant or
per-role group, created once at onboarding and changed rarely — and is not viable for
`BE-D5`'s raw-tier chart grants, which are per-chart, per-session and short-lived. Nothing
measured here softens that: the substitution grammar has no equivalent in authorization
groups, so there is no "one group, many grants" form to fall back on. A grant-shaped group set
would also grow without bound unless revocation is reliable, and cost 3 makes revocation
hazardous.

### 2. Precedence: a pre-created client username **beats** the groups claim

Measured, and it is the opposite of the intuitive answer. Same OAuth profile, same valid
`groups: ["g-stream-alpha"]` claim, but `sub: "grant-111"` **is** a client-username object
(Task 2's):

```
precedence session: {"aclProfileName":"acl-subst","clientUsername":"grant-111",
                     "originalClientUsername":"grant-111"}
```

Not `acl-alpha`. And this is enforcement, not a monitor artefact: that session can subscribe
`sm/v1/acme/stream/grant-111/s1` (candidate (a)'s substituted branch) and is **refused**
`sm/v1/acme/stream/alpha/s1` (the group's branch).

So the resolution order this broker actually implements is:

> **client-username object → authorization group → login refused.**

Note what this rules out. Candidates (a) and (b) do **not** compose additively: on any subject
that has a client-username object the groups claim is inert, and on any subject that does not,
candidate (a)'s `default` fallthrough is unreachable (cost 4). You get one or the other per
subject, decided by whether an object happens to exist — which is a fragile thing to build an
authorization model on.

⚠️ **Measured, with an inference boundary.** What was measured is: *an existing client-username
object with an explicitly bound ACL profile wins over a matching enabled authorization group.*
Whether the broker skips the group lookup entirely, or consults it and lets the username
override, is not distinguished here, and neither is the behaviour when the client-username
object exists but leaves `aclProfileName` at its own default. Neither ambiguity changes the
conclusion, but Task 5 should not over-state the finding.

Solace's docs cover only the multiple-groups case, verbatim from
[Configuring Client LDAP Authorization](https://docs.solace.com/Security/Configuring-LDAP-Groups.htm):
*"The response … may indicate that there is more than one matching group configured in the
Message VPN. In this case, the first match found in the ordered list of groups will provide
the client with its authorizations."* — hence `orderBefore…`/`orderAfter…` on the object. The
docs say **nothing** about precedence against client usernames, or about non-matching groups,
which is why both are measured here rather than quoted.

### 3. It fails **closed** at login — safe, and operationally sharp

A token whose group does not resolve does not get a reduced view. It **cannot connect at all**.
Four measurements, **three** distinct broker messages — all `403` /
`errorSubcode: LOGIN_FAILURE (13)`:

| Token | VPN state | Broker refusal |
|---|---|---|
| no `groups` claim | any | `No Authorization Groups were retrieved from the Authorizing Server` |
| `groups: []` | any | `No Authorization Groups were retrieved from the Authorizing Server` |
| `groups: ["g-nope"]` (no such group) | ≥1 group **enabled** | `No matching configured Authorization Group was found (or all such matching groups are disabled)` |
| `groups: ["g-nope"]` | **zero** groups enabled | `No Authorization Groups in enabled state were configured on the appliance` |

⚠️ **The last row is a distinct broker state, and the message above it is misleading about it.**
Row 3's text advertises itself as covering *"or all such matching groups are disabled"* — but when
the VPN actually has **no enabled authorization group at all**, the broker short-circuits before
the match and emits row 4's different message instead. Measured, with a control, under
"Prove-it-fires" plant 2 below. Anything that matches on the refusal text — an operator runbook, a
log alert, a client-side error mapper — has to handle all three strings, and must not assume row
3's parenthetical is the all-disabled case.

Each is paired with a **control**: the *same* token connects fine on the default OAuth profile,
which is what makes "refused" mean the group lookup rather than a malformed token, an expired
JWKS or a TLS fault. Each negative also asserts that **no session appeared** in the SEMP
monitor, so a slow login cannot masquerade as a refusal.

Failing closed is the right default. The operational edge is that the blast radius is a
**login** failure, not a degraded view: disabling or deleting an authorization group locks out
every holder of a live token naming it, and the console cannot connect to tell the user why.
Under candidate (a) the equivalent mistake costs one empty chart.

Measured directly — the second prove-it-fires plant below sets `enabled: false` on
`g-stream-alpha` and the positive test stops connecting entirely.

### 4. Coexistence cost: it needs its **own OAuth profile**, and that is not optional

`authorizationGroupsClaimName` is a property of the **OAuth profile**, and the profile has no
per-client and no per-token opt-out — the only two group-related properties it has are
`authorizationGroupsClaimName` and `authorizationGroupsClaimStringFormat`, and neither makes
the claim optional. Turning it on for `sm_spike_oauth` refuses every token that lacks the
claim. That is Task 1 finding 2, and here is the measurement, taken by doing exactly that.
**Reproduce it the way the plants below are reproduced** — one PATCH, run, one PATCH back. This
was measured before `sm_spike_oauth_b` existed, so `tests/02` did not yet run and the 8 tests are
Tasks 1–2's:

```console
$ curl -s -X PATCH -u admin:admin -H 'Content-Type: application/json' \
  ".../msgVpns/sm-spike/authenticationOauthProfiles/sm_spike_oauth" \
  -d '{"authorizationGroupsClaimName":"groups"}'
PATCH 200
$ npm test  ->  Tests  3 failed | 5 passed (8)
  × G1 baseline > browser subscribes over OAuth+TLS; the INTERNAL identity publishes …   (sub op-1)
  × G1 baseline > a browser OAuth token can NEVER publish to any sm/v1 topic …           (sub op-1)
  × G1 candidate (a) > an UNMATCHED subject still gets its OWN branch …                  (sub grant-999)
broker, for each: Login failed. Subcode: 13 respCode: 403 respString: No Authorization
                  Groups were retrieved from the Authorizing Server

$ curl -s -X PATCH -u admin:admin -H 'Content-Type: application/json' \
  ".../msgVpns/sm-spike/authenticationOauthProfiles/sm_spike_oauth" \
  -d '{"authorizationGroupsClaimName":""}'      # or ./provision.sh, which re-PATCHes it to ""
PATCH 200
```

Precisely the three tests whose `sub` has no client-username object — candidate (a)'s
`grant-111`/`grant-222` tests survived, which is cost 2 showing up from the other side.

**Two consequences, and the second is a design finding, not a harness one:**

- **In the harness.** Candidate (b) runs on a second OAuth profile, `sm_spike_oauth_b`, and
  `sm_spike_oauth` is left byte-for-byte as Task 1 wrote it. No earlier test was altered,
  relaxed or re-baselined; Tasks 1–2 still measure exactly what they measured (`npm test` →
  **15 passed**, 8 of them theirs). Task 4 inherits an untouched baseline profile.
- **In the product.** Under `BE-D5`, browsers are not the only OAuth identity on the customer's
  VPN. Enabling candidate (b) makes the groups claim mandatory for **every** client on whichever
  OAuth profile it is enabled on, so SM would have to own a dedicated OAuth profile on the
  customer's broker — a heavier ask than "add an ACL profile", and one the customer's broker
  team has to agree to.

**How the second profile is selected — and this is a genuinely useful mechanism in its own
right.** Two facts, both measured here rather than recalled:

- The OAuth profile's `issuer` field ("The Issuer Identifier for the OAuth provider") is the
  **selector**, and is independent of `resourceServerRequiredIssuer` (the `iss` claim that is
  actually validated). `sm_spike_oauth_b` is selected as `sm-spike-oauth-b` while still
  requiring `iss: sm-spike-issuer` — the same spike issuer feeds both profiles. The VPN's
  `authenticationOauthDefaultProfileName` is, verbatim from the served spec, *"The name of the
  profile to use when the client does not supply a profile name"*, so a client that supplies
  nothing still lands on `sm_spike_oauth`.
- The client supplies it as solclientjs `SessionProperties.issuerIdentifier`, surfaced by the
  WebApp SDK as `OAuthConfig.issuerIdentifier` (`src/config.ts`, forwarded through
  `src/client.ts` and `src/protocols/smf/smf-adapter.ts:139-147`).

The harness proves the selection is real rather than assumed: test 3 sends the **identical
token** with no `issuerIdentifier` and gets `clientUsername: "default"` /
`aclProfileName: "acl-browser-baseline"`, with the `beta` branch it was denied in test 1 now
allowed by the blanket exception.

### Finding: by default the SDK **retries a rejected login forever**, so a 403 never surfaces

Not specific to candidate (b), and Task 5 should carry it. With the SDK's default
`connectRetries`, a broker login refusal (`403` / `LOGIN_FAILURE`) is retried about once a
second indefinitely: `connect()` never settles, and the caller sees a hang, not an error. The
first attempt at these negatives died on vitest's 30s timeout — which is indistinguishable from
a hung broker, and is exactly the false-green class R6 flagged.

Setting `connectRetries: 0` turns it into a clean rejection in ~10-90 ms carrying the broker's
own text (`ConnectionError` / `CONNECTION_FAILED`, with `infoStr`, `responseCode: 403` and
`errorSubcode: 'LOGIN_FAILURE (13)'` in the message). That is what tests 4 and 5 assert.

**Product consequence:** the console must bound its connect retries, or an authorization
failure at the mesh is indistinguishable from the mesh being down, and the user is told
nothing. Pair this with Task 1 finding 1 (publish denials are silent) and Task 2's
(subscribe denials are visible) for the full observability picture in Task 5.

### The exact broker config that made it work

| Object | Setting |
|---|---|
| ACL profile `acl-alpha` | `clientConnectDefaultAction: allow`, `publishTopicDefaultAction: disallow` (**no** publish exceptions), `subscribeTopicDefaultAction: disallow` |
| — its subscribe exception | syntax `smf`, topic `sm/v1/acme/stream/alpha/>` — a fixed literal branch, **not** a substitution: candidate (b) authorizes by group, so the branch is a property of the group and not of the session |
| Authorization group `g-stream-alpha` | `aclProfileName: acl-alpha`, `clientProfileName: default`, `enabled: true` |
| OAuth profile `sm_spike_oauth_b` | identical to `sm_spike_oauth` except: `issuer: "sm-spike-oauth-b"` (the selector), `authorizationGroupsClaimName: "groups"`, `authorizationGroupsClaimStringFormat: "single"` |
| OAuth profile `sm_spike_oauth` | **unchanged from Task 1**, `authorizationGroupsClaimName: ""` |

As the broker stores it (`./provision.sh` tail):

```console
Subscribe topic exceptions as the broker stores them:
  acl-subst              smf   sm/v1/acme/stream/$client-username/>
  acl-subst              smf   sm/v1/acme/stream2/${clientUsername}/>
  acl-browser-baseline   smf   sm/v1/>
  acl-browser-baseline   smf   spike/probe/$client-username/>
  acl-alpha              smf   sm/v1/acme/stream/alpha/>

OAuth profiles, groups-claim mapping (candidate (b) switch) and selector:
  sm_spike_oauth     issuer(selector)=sm-spike-issuer    groupsClaim=''       stringFormat='single' usernameClaim='sub' requiredIssuer='sm-spike-issuer'
  sm_spike_oauth_b   issuer(selector)=sm-spike-oauth-b   groupsClaim='groups' stringFormat='single' usernameClaim='sub' requiredIssuer='sm-spike-issuer'

Authorization groups as the broker stores them:
  g-stream-alpha   acl=acl-alpha              clientProfile=default    enabled=True
```

Publish posture is unchanged and asserted, not assumed: `acl-alpha` has
`publishTopicDefaultAction: disallow` and an **empty** `publishTopicExceptions` collection
(test 7, read from SEMP config — a publish denial is invisible to the client, so the guarantee
has to be checked where it lives).

### SEMP verification (`I2`)

Everything above was read out of the broker's own served SEMP 2.48 spec before being scripted:

```console
$ curl -s -u admin:admin http://localhost:8080/SEMP/v2/config/spec | python3 -c "
import json,sys; spec=json.load(sys.stdin)
print([p for p in spec['paths'] if 'authorizationGroup' in p])"
['/msgVpns/{msgVpnName}/authorizationGroups',
 '/msgVpns/{msgVpnName}/authorizationGroups/{authorizationGroupName}']
```

`MsgVpnAuthorizationGroup`: `authorizationGroupName`, `aclProfileName`, `clientProfileName`,
`enabled`, `orderBeforeAuthorizationGroupName`, `orderAfterAuthorizationGroupName`.

`MsgVpnAuthenticationOauthProfile.authorizationGroupsClaimName`, verbatim: *"The name of the
groups claim. If non-empty, the specified claim will be used to determine groups for
authorization. If empty, the authorizationType attribute of the Message VPN will be used to
determine authorization. … The default value is `\"groups\"`."*
`…ClaimStringFormat`: *"single"* (default) or *"space-delimited"*, *"only … when the claim is a
string"*, **"Available since 2.32"** — so **candidate (b)'s SEMP floor is 2.32**, as Task 1
predicted; the `authorizationGroups` resource itself is present at 2.48.

Docs, for the two things the spec does not say
([Configuring Client Authentication](https://docs.solace.com/Security/Configuring-Client-Authentication.htm)
§"Authorization Groups Claim"): *"By default, Solace event brokers use the `groups` field in
OAuth tokens to determine the client authorization group"*, and claim values must be strings or
arrays of strings. Test 6 confirms the string form on this broker (`groups: "g-stream-alpha"`
with `stringFormat: "single"` → `acl-alpha`). The docs are **silent** on absent claims,
unmatched groups and precedence against client usernames — all three are measured above.

### Prove-it-fires (run manually, not committed)

Two plants, because two different objects carry the mechanism.

**Plant 1 — repoint the group at another ACL profile.** The binding, not the claim:

```console
$ curl -s -X PATCH -u admin:admin -H 'Content-Type: application/json' \
  ".../authorizationGroups/g-stream-alpha" -d '{"aclProfileName":"acl-browser-baseline"}'
PATCH 200 acl-browser-baseline
$ npx vitest run tests/02-authzgroup.test.mjs
   × candidate b: groups claim grants exactly the group ACL
     → expected 'acl-browser-baseline' to be 'acl-alpha'
   × a STRING groups claim works under authorizationGroupsClaimStringFormat "single"
     → expected 'acl-browser-baseline' to be 'acl-alpha'
   × candidate (b) grants stay subscribe-only: no publish exception exists on acl-alpha
 Tests  3 failed | 4 passed (7)
```

**Plant 2 — disable the group.** `enabled` is load-bearing, and this is also the measurement
behind cost 3:

```console
$ curl -s -X PATCH -u admin:admin -H 'Content-Type: application/json' \
  ".../authorizationGroups/g-stream-alpha" -d '{"enabled":false}'
PATCH 200 enabled= False
$ npx vitest run tests/02-authzgroup.test.mjs
   × candidate b: groups claim grants exactly the group ACL
     → Failed to connect: { sessionEventCode: 'CONNECT_FAILED_ERROR (2)', …
   × an unknown group name FAILS CLOSED at login — it does not fall back to the client username
     → expected 'Failed to connect: { sessionEventCode…' to contain 'No matching configured Authorization …'
   × a STRING groups claim works under authorizationGroupsClaimStringFormat "single"
   × candidate (b) grants stay subscribe-only: no publish exception exists on acl-alpha
 Tests  4 failed | 3 passed (7)
```

The positive stops *connecting*, not merely subscribing — a disabled group is a login refusal.

**Four failures, not three, and the fourth one is a finding.** Three are the intended red. The
fourth — `an unknown group name FAILS CLOSED at login` — should not have moved: it mints
`groups: ["g-nope"]`, a group that never existed, and disabling `g-stream-alpha` changes none of
its inputs. It failed because **the broker's refusal text for an unmatched group changes when the
VPN has no enabled authorization group at all**:

```
unknown-group login: {"outcome":"login-refused","message":"Failed to connect: { sessionEventCode: 'CONNECT_FAILED_ERROR (2)',
  infoStr: 'No Authorization Groups in enabled state were configured on the appliance',
  responseCode: 403, errorSubcode: 'LOGIN_FAILURE (13)', …
```

Not row 3's *"No matching configured Authorization Group was found…"* but row 4's *"No
Authorization Groups in enabled state were configured on the appliance"*. `g-stream-alpha` is the
VPN's only authorization group, so disabling it left **zero enabled**.

**Control, to prove the trigger is "zero enabled groups" and not "this particular group is
disabled":** add a second, enabled group while leaving `g-stream-alpha` disabled — it does not
match `g-nope` either, so the only thing that changes is whether *any* enabled group exists.

```console
$ curl -s -X POST -u admin:admin -H 'Content-Type: application/json' \
  ".../authorizationGroups" -d '{"msgVpnName":"sm-spike","authorizationGroupName":"g-probe-enabled",
     "aclProfileName":"acl-alpha","clientProfileName":"default","enabled":true}'
POST 200
   group: g-stream-alpha   enabled= False
   group: g-probe-enabled  enabled= True
$ npx vitest run tests/02-authzgroup.test.mjs
unknown-group login: {"outcome":"login-refused","message":"Failed to connect: { sessionEventCode: 'CONNECT_FAILED_ERROR (2)',
  infoStr: 'No matching configured Authorization Group was found (or all such matching groups are disabled)',
  responseCode: 403, errorSubcode: 'LOGIN_FAILURE (13)', …
 Tests  3 failed | 4 passed (7)
```

The message reverts, test 5 goes green, and the run becomes the coherent `3 failed | 4 passed`.
So the trigger is exactly **zero enabled authorization groups in the Message VPN** — cost 3's row
4, and the reason its parenthetical must not be trusted.

`DELETE …/authorizationGroups/g-probe-enabled` then `./provision.sh` restores both plants (the
`create` helper PATCHes `g-stream-alpha` back to `enabled: true`) and the suite returns to
**15 passed** — verified.

---

## Candidate (c) — tenant-coarse ACL + unguessable grant-ids

### Verdict: **PASS**, as it was always going to. The deliverable is the exposure boundary.

Candidate (c) does not attempt per-grant authorization at the broker. One ACL profile per
tenant, whose exceptions cover that tenant's whole `notify` and whole `stream` namespace; the
isolation *between* grants of the same tenant is supposed to come from grant-ids being
unguessable. It therefore cannot fail a functional test, and a green run proves nothing on its
own. What the nine tests in `tests/03-coarse-and-i5.test.mjs` produce instead is a
**measured** statement of what a legitimately-issued ticket can reach beyond what it was issued
for — and one measurement in it is decisive against the candidate.

### The exposure boundary, stated precisely

**What candidate (c) enforces** (measured):

| Enforced | Evidence |
|---|---|
| **Tenant isolation.** `tenant-acme` is refused every `sm/v1/globex/…` topic, and `tenant-globex` is refused every `sm/v1/acme/…` topic, each with `AuthorizationError` / `SUBSCRIBE_ACL_DENIED` | tests 4 and 5, both directions, each with an in-tenant positive control in the same session |
| The cross-tenant **wildcard** `sm/v1/>` is refused too, so the boundary is not merely a per-topic-string match | test 4 |
| **Subscribe-only.** All three new profiles keep `publishTopicDefaultAction: disallow` with an empty `publishTopicExceptions` collection | test 9, read from SEMP config (a publish denial is invisible to the client — Task 1 finding 1) |
| **Ticket lifetime.** An expired access token is refused at login and leaves no session | test 6, with a control at test 7 |

**What candidate (c) does NOT enforce, and this is the boundary** (measured — the topics below
were all *admitted* to a single `tenant-acme` session in test 1):

| Not enforced | The topic that was admitted |
|---|---|
| Per-grant isolation within a tenant | `sm/v1/acme/stream/some-other-grant/s1` — a grant-id never issued to anyone |
| Isolation from **another identity's** grant | `sm/v1/acme/stream/grant-111/s1` — Task 2's grant, belonging to a different client-username object |
| Any narrowing at the stream tier at all | `sm/v1/acme/stream/>` — **the entire tier in one subscription** |
| Tier separation (a stream ticket also reads notify) | `sm/v1/acme/notify/instance/i-1/health` |
| Anything the `scopes` claim says | test 3: a token minted `scopes: ['notify']` reads `sm/v1/acme/stream/any-grant/s1` — the claim is **inert** at the broker |

⚠️ **Admission is not receipt, and test 1 measures only admission.** Whether that exposure is
*realised* depends on traffic actually flowing to a wildcard subscriber, which is a separate
fact. **Test 2 measures it**, so nothing below is inferred from an accepted subscription:
`sm-internal` (the spike's only publisher) publishes to two distinct grant streams, and the
`tenant-acme` wildcard session recovers **both grant-ids and both payloads**:

```
exposure delivery: satisfied [{"topic":"sm/v1/acme/stream/grant-111/s1","payload":{"v":11}},
                              {"topic":"sm/v1/acme/stream/grant-other/s2","payload":{"v":22}}]
```

`grant-111` is a **different identity's** grant (Task 2's client-username object);
`grant-other` was never issued to anybody. Neither id was known to the subscribing session —
both were recovered from the received topic strings. The waiter classifies its outcome
(`satisfied` / `timed-out`) so a timeout can never be read as an empty result.

So, precisely:

> **Under candidate (c) a browser ticket is a bearer credential for its tenant's entire
> `sm/v1/<tenant>/notify/>` and `sm/v1/<tenant>/stream/>` namespace, for the ticket's whole
> lifetime, regardless of which grant, chart, entity or scope it was issued for. The only
> boundaries the broker enforces are the tenant edge and subscribe-only. Everything finer —
> per-grant, per-chart, per-scope, per-session — is enforced nowhere.**

**And the "unguessable grant-ids" half of the candidate does not survive contact with that.**
The premise is that an in-tenant attacker would have to *guess* a grant-id. It does not have to:
`sm/v1/acme/stream/>` is admitted (test 1) **and delivers** (test 2), so any holder of any
in-tenant ticket **enumerates** every live grant-id in the tenant, and receives their payloads,
by issuing one wildcard subscription. Unguessability is a secrecy property; it buys nothing once
the namespace above it is readable. This is the finding that matters for `BE-D5`, and — with
test 2 in place — both halves of it, enumeration *and* payload receipt, are measurements rather
than arguments.

Two consequences that Task 5 should carry:

- The residual risk under (c) is **not** "a same-tenant user might guess a grant-id". It is
  "any same-tenant browser session reads every same-tenant chart tail and every same-tenant
  notify event, by design". Whether that is acceptable is an `I5`-scoped product decision about
  what "tenant" means for SM's customers — it is not a broker question, and no broker
  configuration under this candidate changes it.
- Tightening (c) after the fact costs the same as adopting (a): a narrower exception per grant
  means either a substitution variable (that *is* candidate (a)) or a broker write per grant
  (that is candidate (b)'s cost).

### Design §14 fixture inventory — these become the mesh-provisioning plan's CI fixtures

Five rows across seven of the nine tests — **three of them are design §14 fixtures** (F1 = §14's
first, F3 = §14's second, F2 = §14's third; §14's other three are token-lifecycle fixtures and
live in `tests/04`), and **F1b and F4 are extras beyond §14's list**. Tests 1 and 3 are the
exposure measurement rather than fixtures, and test 2 is both. All measured against a real
broker. Each is listed with the object it depends on, because the mesh-provisioning plan has to provision the same
shapes in CI.

| # | Fixture | Test | Depends on |
|---|---|---|---|
| **F1** | `I5` tenant isolation: an acme ticket is denied every globex topic, and the mirror | tests 4 + 5 | two tenant profiles (`acl-acme`, `acl-globex`) and two client usernames; **both directions**, each with an in-tenant positive control |
| **F1b** | `I5` at **delivery**: a live in-tenant session that is receiving traffic still cannot widen its subscription across the tenant edge | test 2's tail | the same two profiles, plus `sm-internal` as publisher |
| **F2** | An expired ticket is refused at connect, with no session left behind | test 6 (+ control at test 7) | the issuer's `expiresIn` directive; `connectRetries: 0` |
| **F3** | Notify-scope identity cannot reach the stream tier | test 8 | `acl-acme-notify` + `tenant-acme-notify` — **a second profile/username pair**, see below |
| **F4** | Browser identities are subscribe-only: publish default-disallow, zero publish exceptions | test 9 | asserted from SEMP **config**, for all three profiles, because a publish denial never reaches the client |

Two harness properties that the mesh-provisioning plan must keep when it lifts these, or the fixtures stop
measuring anything:

- **Every test that connects asserts the session's effective `clientUsername` and
  `aclProfileName` from the SEMP monitor before believing any subscribe outcome.** Without it a
  subject that silently fell through to the VPN's `default` client username would be judged
  against `acl-browser-baseline`'s blanket `sm/v1/>` exception, and every positive would pass
  for the wrong reason. Proven necessary — plant 4 below deletes the `tenant-acme` client
  username and *only* those assertions catch it.
- **Every subscribe negative asserts `AuthorizationError` / `SUBSCRIBE_ACL_DENIED`**, never a
  bare rejection; every login negative asserts the broker's own refusal shape and that no
  session appeared.

### Which candidate makes scope separation enforceable

Design §14's second fixture is *"a notify-scope ticket must not reach a stream topic"*. Where
that can be enforced differs per candidate, and the difference is the sharpest practical
distinction between them.

| Candidate | Can a **ticket** carry the scope distinction? | Cost of scope separation |
|---|---|---|
| **(a)** substitution | **No, not by itself** — the exception set is a property of the ACL *profile*, and (a) selects the profile the same way (c) does. The `sub` carries the grant, not the scope. | a second profile per scope, **or** encoding the scope into `sub` — which then needs a substitution-scoped notify exception, and design §4's notify topics have no grant level to substitute at (already flagged under candidate (a)) |
| **(b)** authorization groups | **Yes.** The groups claim selects the ACL profile per token, so the same `sub` can present a notify ticket or a stream ticket and get different exception sets, with no per-identity broker object. | two long-lived group objects per tenant, created once at onboarding — exactly the "coarse, long-lived scopes" shape Task 3 found (b) *is* viable for |
| **(c)** tenant-coarse | **No.** The `scopes` claim is inert at the broker (measured, test 3). | a **profile + client-username pair per (tenant × scope)** — provisioned here as `acl-acme-notify` + `tenant-acme-notify` and measured in test 8 |

**So: candidate (b) is the only one of the three that makes notify-vs-stream scope separation
enforceable from the ticket itself.** That is a genuinely useful result, because it is the one
axis where (b)'s per-token group binding is at the right granularity — scopes are few and
long-lived, which is precisely the case Task 3's cost analysis said (b) suits, and the opposite
of per-grant.

⚠️ **Measured / inferred split.** Candidate (c)'s row is **measured** here (tests 3 and 8).
Candidate (b)'s row is an **inference** from Task 3's measurements — that the groups claim
selects the ACL profile per token, that a client-username object beats it, and that the group
is a broker object — applied to a scope-shaped group set; no test in this task minted a
`g-acme-notify` / `g-acme-stream` pair. Candidate (a)'s row is likewise **inferred** from
Task 2's measurements plus design §4's notify topic shape. Task 5 owns the weighing, and should
either measure (b)'s scope-shaped variant or state it as inferred.

### New finding: an expired ticket is a FOURTH refusal shape, and it carries no reason

Task 3 recorded three distinct broker refusal `infoStr` strings, all `403` /
`errorSubcode: LOGIN_FAILURE (13)`, all arising from the authorization-group lookup. An expired
access token is none of them. Measured:

```
solclientjs: [WaitingForLogin] Login failed. Subcode: 13 respCode: 401 respString: Unauthorized
SDK:         AuthenticationError / AUTH_FAILED / "Authentication failed: Unauthorized"
```

`401`, not `403`. The SDK maps it to `AuthenticationError` / `AUTH_FAILED`, where the three
group refusals surface as `ConnectionError` / `CONNECTION_FAILED` carrying the broker's
explanatory text. So:

- **A client can tell the two classes apart by error class** — an authentication/ticket problem
  versus an authorization-group problem — which is more than Task 3's finding promised.
- **It cannot tell *why* within the authentication class.** `Unauthorized` is all the broker
  says; expired, bad signature, wrong audience, unknown `kid` and a JWKS the broker could not
  fetch are indistinguishable at the client. **Product consequence:** the console-gateway must
  re-ticket *proactively*, on its own clock, before `exp`; there is no usable reactive signal to
  drive re-ticketing off, and no way to render a truthful "your session expired" message from
  the mesh error alone.
- **Timing.** Refused at +2 s, +6 s and +31 s past a 1 s-lifetime token, in 23–76 ms each. A
  30 s-lifetime token used immediately connects (test 7's control). So the broker allows **no
  meaningful clock-skew grace** — under a second — and a gateway must not rely on one.

### The exact broker config that made it work

| Object | Setting |
|---|---|
| ACL profiles `acl-acme`, `acl-globex`, `acl-acme-notify` | `clientConnectDefaultAction: allow`, `publishTopicDefaultAction: disallow` (**no** publish exceptions), `subscribeTopicDefaultAction: disallow` |
| `acl-acme` subscribe exceptions | syntax `smf`: `sm/v1/acme/notify/>` **and** `sm/v1/acme/stream/>` |
| `acl-globex` subscribe exceptions | syntax `smf`: `sm/v1/globex/notify/>` **and** `sm/v1/globex/stream/>` |
| `acl-acme-notify` subscribe exception | syntax `smf`: `sm/v1/acme/notify/>` **only** — the absent stream exception is what fixture F3 tests |
| Client usernames `tenant-acme`, `tenant-globex`, `tenant-acme-notify` | `aclProfileName` = the matching profile, `clientProfileName: default`, `enabled: true`, **no password** (OAuth only) |
| OAuth profile | `sm_spike_oauth`, unchanged from Task 1. Candidate (c) runs on the VPN's default profile; `usernameClaimName: "sub"` is the whole binding mechanism. |

Pre-created client usernames are **required** by this candidate, unlike candidate (a): the
profile is chosen by the client-username object, so a subject without one falls through to
`default` / `acl-browser-baseline` (Task 1 finding 3). That is one broker object pair per
(tenant × scope) — cheap and long-lived, which is (c)'s one real advantage over (b).

As the broker stores it (`./provision.sh` tail):

```console
Subscribe topic exceptions as the broker stores them:
  acl-subst              smf   sm/v1/acme/stream/$client-username/>
  acl-subst              smf   sm/v1/acme/stream2/${clientUsername}/>
  acl-browser-baseline   smf   sm/v1/>
  acl-browser-baseline   smf   spike/probe/$client-username/>
  acl-alpha              smf   sm/v1/acme/stream/alpha/>
  acl-acme               smf   sm/v1/acme/notify/>
  acl-acme               smf   sm/v1/acme/stream/>
  acl-globex             smf   sm/v1/globex/notify/>
  acl-globex             smf   sm/v1/globex/stream/>
  acl-acme-notify        smf   sm/v1/acme/notify/>

Client usernames and the ACL profile each one binds:
  #client-username       acl=#acl-profile           enabled=True
  default                acl=acl-browser-baseline   enabled=True
  grant-111              acl=acl-subst              enabled=True
  grant-222              acl=acl-subst              enabled=True
  sm-internal            acl=acl-internal           enabled=True
  tenant-acme            acl=acl-acme               enabled=True
  tenant-acme-notify     acl=acl-acme-notify        enabled=True
  tenant-globex          acl=acl-globex             enabled=True
```

### SEMP verification (`I2`)

Candidate (c) introduces **no new SEMP resource and no new property**. The three it uses were
re-read out of the broker's own served SEMP 2.48 spec for this task before anything was
scripted:

```console
$ curl -s -u admin:admin http://localhost:8080/SEMP/v2/config/spec | python3 -c "
import json,sys; d=json.load(sys.stdin)['definitions']
for n in ['MsgVpnAclProfile','MsgVpnAclProfileSubscribeTopicException','MsgVpnClientUsername']:
    print('==',n); [print('   ',k) for k in sorted(d[n]['properties'])]"
== MsgVpnAclProfile
     aclProfileName / clientConnectDefaultAction / msgVpnName / publishTopicDefaultAction
     subscribeShareNameDefaultAction / subscribeTopicDefaultAction
== MsgVpnAclProfileSubscribeTopicException
     aclProfileName / msgVpnName / subscribeTopicException / subscribeTopicExceptionSyntax
== MsgVpnClientUsername
     aclProfileName / clientProfileName / clientUsername / enabled
     guaranteedEndpointPermissionOverrideEnabled / msgVpnName / password / subscriptionManagerEnabled
```

`subscribeTopicException`, verbatim from the same spec: *"The topic for the exception to the
default action taken. May include wildcard characters."* The `>` wildcards in `acl-acme` and
`acl-globex` are ordinary SMF wildcards — **no** substitution grammar is involved anywhere in
candidate (c), which is exactly what makes it coarse.

### Prove-it-fires (run manually, not committed)

Seven plants. Each turns **exactly** the intended tests red, on the intended assertion — except
plant C, whose *failure* to fire was itself a finding and is recorded as such.

**Plant 1 — give `acl-acme` a cross-tenant exception.** `I5` must be a measurement:

```console
$ curl -s -X POST -u admin:admin -H 'Content-Type: application/json' \
  ".../aclProfiles/acl-acme/subscribeTopicExceptions" \
  -d '{"msgVpnName":"sm-spike","aclProfileName":"acl-acme","subscribeTopicExceptionSyntax":"smf",
       "subscribeTopicException":"sm/v1/globex/notify/>"}'
POST 200
$ npx vitest run tests/03-coarse-and-i5.test.mjs
   × I5: tenant acme token cannot subscribe tenant globex topics
     → sm/v1/globex/notify/>: {"outcome":"allowed"}: expected { outcome: 'allowed', …(1) }
       to match object { outcome: 'acl-denied', …(2) }
 Tests  1 failed | 8 passed (9)
```

**Plant 2 — give `acl-acme-notify` a stream exception.** F3's whole content is that absence:

```console
$ curl -s -X POST … "…/aclProfiles/acl-acme-notify/subscribeTopicExceptions" \
  -d '{… "subscribeTopicException":"sm/v1/acme/stream/>"}'
POST 200
$ npx vitest run tests/03-coarse-and-i5.test.mjs
   × §14 F3 scope separation: under (c) it takes a SECOND ACL profile, not a scope claim
     → sm/v1/acme/stream/any-grant/s1: {"outcome":"allowed"}: expected { outcome: 'allowed', …(1) }
       to match object { outcome: 'acl-denied', …(2) }
 Tests  1 failed | 8 passed (9)
```

**Plant 3 — mint the "expired" ticket with a 300 s lifetime.** This is the plant that proves
F2 measures *expiry* and not "a token minted through the `expiresIn` path is broken":

```console
$ sed -i '' "s/expiresIn: '1s'/expiresIn: '300s'/" tests/03-coarse-and-i5.test.mjs
$ npx vitest run tests/03-coarse-and-i5.test.mjs
expired-ticket login: {"outcome":"connected"}
   × expired ticket is refused at connect
     → {"outcome":"connected","session":{"aclProfileName":"acl-acme",…,"clientUsername":"tenant-acme",…}}:
       expected 'connected' to be 'login-refused'
 Tests  1 failed | 8 passed (9)
```

**Plant 4 — delete the `tenant-acme` client username.** The important one, and the reason every
test asserts its session binding. The subscribes would all still have *passed*: the session
falls through to `default` → `acl-browser-baseline`, whose blanket `sm/v1/>` exception allows
everything the positives ask for. **Only the binding assertions catch it:**

```console
$ curl -s -X DELETE -u admin:admin ".../clientUsernames/tenant-acme"
DELETE 200
$ npx vitest run tests/03-coarse-and-i5.test.mjs
candidate (c) session: {"aclProfileName":"acl-browser-baseline","clientUsername":"default",
                        "originalClientUsername":"tenant-acme", …}
   × candidate c: tenant-coarse ACL admits any stream in-tenant (the stated exposure)
     → expected 'default' to be 'tenant-acme'
   × candidate (c) exposure DELIVERED: one wildcard subscription enumerates other grants …
     → expected 'default' to be 'tenant-acme'
   × candidate (c): the `scopes` claim is INERT at the broker …
     → expected 'acl-browser-baseline' to be 'acl-acme'
   × I5: tenant acme token cannot subscribe tenant globex topics
     → expected 'default' to be 'tenant-acme'
   × expired-ticket CONTROL: the same short-lived minting path connects while still valid
     → expected 'acl-browser-baseline' to be 'acl-acme'
 Tests  5 failed | 4 passed (9)
```

Three more for the delivery test (test 2), because its three claims — enumeration, payload
receipt, and the tenant edge holding on a live receiving session — are separable and each needs
its own plant.

**Plant A — publish both messages to the SAME grant-id.** Proves the enumeration assertion is
not vacuous: two messages still arrive, but only one distinct grant-id is recoverable.

```console
$ sed -i '' "s|stream/grant-other/s2', { v: 22 }|stream/grant-111/s1', { v: 22 }|" tests/03-…
$ npx vitest run tests/03-coarse-and-i5.test.mjs
exposure delivery: satisfied [{"topic":"sm/v1/acme/stream/grant-111/s1","payload":{"v":11}},
                              {"topic":"sm/v1/acme/stream/grant-111/s1","payload":{"v":22}}]
   × candidate (c) exposure DELIVERED: …
     → expected [ 'grant-111' ] to deeply equal [ 'grant-111', 'grant-other' ]
 Tests  1 failed | 8 passed (9)
```

**Plant B — publish nothing in-tenant** (both publishes redirected to `globex`). Proves the
waiter reports a timeout **as a timeout** rather than as an empty result — the failure mode a
bare `sleep()`-then-assert would hide:

```console
$ npx vitest run tests/03-coarse-and-i5.test.mjs
exposure delivery: timed-out []
   × candidate (c) exposure DELIVERED: …
     → []: expected 'timed-out' to be 'satisfied'
 Tests  1 failed | 8 passed (9)      (10.1 s — the full waiter timeout, as intended)
```

**Plant C — give `acl-acme` a `sm/v1/globex/stream/>` exception.** This one **did not fire at
first, and that was a finding.** The delivery test's original "nothing from globex arrived"
assertion stayed green with the cross-tenant exception in place — because the session's
subscription is `sm/v1/acme/stream/>`, so a topic **filter** produces that result whether or not
any ACL is doing work. The assertion was measuring the filter, not the edge.

Fixed by adding the half that the ACL actually enforces: the same live, receiving session then
attempts `sm/v1/globex/stream/>` and must be refused. With that in place the plant fires:

```console
$ curl -s -X POST … "…/aclProfiles/acl-acme/subscribeTopicExceptions" \
  -d '{… "subscribeTopicException":"sm/v1/globex/stream/>"}'
POST 200
$ npx vitest run tests/03-coarse-and-i5.test.mjs
   × candidate (c) exposure DELIVERED: …
     → {"outcome":"allowed"}: expected { outcome: 'allowed', …(1) } to match object
       { outcome: 'acl-denied', …(2) }
   × I5: tenant acme token cannot subscribe tenant globex topics
     → sm/v1/globex/stream/any-grant/s1: {"outcome":"allowed"}: expected …
 Tests  2 failed | 7 passed (9)
```

The weak-assertion note is kept in the test's own comments, so the distinction survives being
lifted into the mesh-provisioning plan.

`./provision.sh` (plants 1, 2, 4 — the `create` helper re-POSTs the deleted username) plus
`DELETE` on the planted exceptions (plants 1, 2, C) plus reverting the `sed`s (plants 3, A, B)
restores everything; `npm test` returns to **24 passed** — verified.

---

## Task 5 — composition, JWKS rotation, key persistence, renewal

Task 5's durable output is [`docs/design/backend/spike-authz-result.md`](../../../docs/design/backend/spike-authz-result.md),
the written G1 result. This section is the evidence behind the parts of it that Tasks 1–4 did
not measure.

| File | What it is |
|---|---|
| `issuer/issuer.mjs` | `POST /rotate` (new kid, both JWKs published, signs with the new one; a second call evicts the oldest) and `GET /stats` — a timestamped log of every `GET /jwks.json`, which is how the broker's refresh behaviour is observed **at the source** rather than inferred from whether a login happened to succeed. |
| `tests/04-rotation-renewal.test.mjs` | Six assertions: the composition, the force-refresh absence, rotation in both directions, key persistence across an issuer restart, renewal, and the expiry disconnect. |

### 1. The `default` → `acl-subst` composition — Task 2's deferred measurement, now MEASURED

Task 2 recorded this as *"NOT measured — the two composed"* and asked Task 4 or 5 to close it.
Closed. The measurement binds the VPN's `default` client username to `acl-subst` — a profile
whose only exceptions are substitution-scoped, with **no** blanket `sm/v1/>` — and connects a
subject (`grant-777`) that has no client-username object. Its absence is asserted, not assumed.

The test takes its own **control first**, on the pre-PATCH binding, which is what makes the
denials afterwards mean something:

```
composition CONTROL (default -> acl-browser-baseline): {"wide":{"outcome":"allowed"},
                                                        "other":{"outcome":"allowed"}}
composition session: {"aclProfileName":"acl-subst","clientUsername":"default",
                      "originalClientUsername":"grant-777", …}
composition measured: {"own":       {"outcome":"allowed"},
                       "other":     {"outcome":"acl-denied", …"SUBSCRIBE_ACL_DENIED"},
                       "asDefault": {"outcome":"acl-denied", …"SUBSCRIBE_ACL_DENIED"},
                       "wide":      {"outcome":"acl-denied", …"SUBSCRIBE_ACL_DENIED"},
                       "notify":    {"outcome":"acl-denied", …"SUBSCRIBE_ACL_DENIED"},
                       "otherTenant":{"outcome":"acl-denied", …"SUBSCRIBE_ACL_DENIED"}}
```

**Upgrade Task 2's label from inferred to measured.** An unprovisioned subject riding the
`default` fallthrough onto a substitution-only profile gets a branch named after its own token
`sub` (`own`), and is refused another grant's branch (`other`), the branch named after its
*authorization* username (`asDefault`) and the cross-tenant wildcard (`wide`). So candidate
(a)'s **zero broker writes per grant** is now an observation, on exactly the binding the
recommendation uses — not an inference from three separate measurements.

⚠️ **`otherTenant` is the multi-tenant constraint, and it is the reason the zero-write claim is
SINGLE-TENANT.** The exception `acl-subst` carries is `sm/v1/`**`acme`**`/stream/$client-username/>`
— the tenant level is a **literal** and only the username level substitutes. The same session
that is allowed `sm/v1/acme/stream/grant-777/s1` is **refused**
`sm/v1/globex/stream/grant-777/s1`: its own substituted username, other tenant. Good for `I5`
(the tenant edge is broker-enforced — by the literal), bad for the cost claim, because a Message
VPN has exactly **one** `default` client username: one fallthrough, one profile, one tenant
literal — while `BE-D2` provisions *"**one dedicated Message VPN** for SM"* per install and
design §4 makes *"**Tenant** … a topic level, so broker ACLs can scope subscriptions by prefix —
the mesh-side half of `I5`"* **inside** that VPN (restated in design §11, fixture in design §14).
Several tenants therefore share one VPN, and Task 4 provisioned both `acme` and `globex` on this
one. Three routes out, each priced in the result document
([gap 1](../../../docs/design/backend/spike-authz-result.md)); **none is measured and none is
recommended**, because each gives up something the mechanism was chosen for.

⚠️ **And the cost Task 2 warned about is measured too, in the same run:** `notify` is
**denied**. A stream-only exception set cannot serve `BE-D5`'s thin-notify tier. This is not a
detail to fix later — under the zero-write recommendation `default` is the *only* profile a
legitimate console ever gets, so the fallback profile must also carry the console's non-stream
subscriptions, and those must themselves be substitution-scoped or the isolation leaks back
out. Design §4's notify topics have no grant level to substitute at. **This is the open design
question the result document hands to `BE-D5` ratification.**

The binding is restored in a `finally`, the restoration is asserted, and an `afterAll` restores
it again if an assertion throws mid-test — every other file in the suite assumes
`default → acl-browser-baseline` (Task 1 finding 3).

### 2. JWKS rotation — the two directions are NOT symmetric

This is the section `BE-D5`'s "overlap window … through the broker's cache/refresh window,
which is an install-runbook parameter" needs, and the parameter turns out not to be the thing
that governs.

**The configured interval, from the broker's own served spec:**
`MsgVpnAuthenticationOauthProfile.endpointJwksRefreshInterval`, verbatim — *"The number of
seconds between JWKS endpoint requests. … The default value is `86400`."* Both spike profiles
run at that default. The broker enforces a floor:

```console
$ curl -s -X PATCH … -d '{"endpointJwksRefreshInterval":10}'
{"code":400,"error":{"code":11,"status":"INVALID_PARAMETER",
 "description":"'endpointJwksRefreshInterval' validation failed:
                The provided number is smaller than the minimum value 60."}}
```

**But 86400 is not the latency anyone experiences, because the refresh is demand-driven.**
Measured with the issuer's fetch log, which records every `GET /jwks.json`:

| Observation | Evidence |
|---|---|
| The broker does **not** poll the JWKS endpoint while idle | 12 minutes idle with no logins: `jwksFetchCount` never moved |
| A login presenting an **unknown `kid`** makes the broker re-read, essentially immediately | rotate at `16:49:08.088` → issuer fetch at `16:49:08.138` (**50 ms**) → the new-key token connected on the first attempt |
| …but no more often than about **60 s** | rotate at `16:49:08.161`, 23 ms after that fetch → refused at +0/5/…/55 s → next fetch `16:50:08.451` = previous **+60.313 s** → accepted at +60 s |

So the **adoption** number for a new signing key is *whichever comes first*: an immediate
re-read, or the tail of a ~60 s floor. The committed test measures it every run; on the
cold-start run:

```
rotation 1: {"kid":"spike-2","kids":["spike-1","spike-2"]}
rotation ADOPTION: new kid spike-2 satisfied after 60292ms; issuer JWKS fetches 2 -> 4,
                   last at 2026-08-27T17:11:59.177Z
old-key token DURING the overlap: {"outcome":"connected"}
```

**Retirement is the asymmetric half, and it is the one with teeth.** Evicting a key from the
published JWKS does **not** revoke it. The broker serves from its cache, and a token signed
with an evicted key still *succeeds* — so it produces no unknown-`kid` stimulus and would never
prompt the re-read that would kill it.

Measured directly, presenting **only** the evicted key so nothing else could trigger a re-read,
with absolute timestamps and the issuer's fetch counter alongside (uncommitted measurement —
it needs a long quiet window; see deviation 8):

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

**151 seconds — 2 min 31 s — on a key that was no longer published**, with `jwksFetchCount`
pinned at 1 the whole time (the broker's previous read was 15 minutes earlier). It died **70 ms**
after a single login on the current `kid` appeared, at which point the broker re-read
(`17:37:27.617Z`) and the counter moved to 2. So what ends an evicted key's life is *other*
traffic, not the eviction — and with no such traffic on a quiet VPN there is no measured upper
bound short of `endpointJwksRefreshInterval`.

The committed test drives the same thing with the stimulus deliberately present, and measures:

```
rotation 2: {"kid":"spike-3","kids":["spike-2","spike-3"]}
old-key token AFTER eviction: satisfied after 60538ms —
  {"outcome":"login-refused","name":"AuthenticationError","code":"AUTH_FAILED",
   "message":"Authentication failed: Unauthorized"}
```

Note the refusal shape: **401 / `AuthenticationError` / `AUTH_FAILED` / "Unauthorized"** — byte
for byte Task 4's *fourth* refusal shape, the expired-ticket one. A client cannot distinguish
"your signing key was rotated out" from "your ticket expired". That closes the observability
picture rather than adding to it.

### 3. Is there a force-refresh? No SEMP **action** — but two config changes do it

Asserted in the committed test against the broker's own served **action** spec:

```
SEMP action paths mentioning oauth: ["…/authenticationOauthProfiles",
  "…/authenticationOauthProfiles/{oauthProfileName}",
  "…/authenticationOauthProfiles/{oauthProfileName}/clearStats",
  "…/authenticationOauthProviders", "…/authenticationOauthProviders/{oauthProviderName}",
  "…/authenticationOauthProviders/{oauthProviderName}/clearStats",
  "/oauthProfiles", "/oauthProfiles/{oauthProfileName}", "/oauthProfiles/{n}/clearStats"]
```

Nothing but `clearStats`. The test also asserts that
`/certAuthorities/{certAuthorityName}/refreshCrl` **does** exist — the broker models
"refresh this cached artefact now" as an action where it wants to, so the absence for JWKS is a
real gap and not a bad search.

Two **config changes** do force an immediate re-read. Measured with no login traffic at all, and
quiesced past the ~60 s floor first so the fetch could not be attributed to demand:

| Lever | Result |
|---|---|
| `PATCH endpointJwks` to the **same** value | **no fetch in 30 s** — an unchanged value is not a change |
| `PATCH endpointJwks` to a **different** value | fetch **immediately** (`16:55:42.830`) |
| `PATCH enabled:false` then `enabled:true` | fetch **immediately** (`16:56:37.903`); costs a window in which every OAuth login on that profile is refused |

Solace's docs agree the broker refreshes on configuration change
([Configuring Client Authentication](https://docs.solace.com/Security/Configuring-Client-Authentication.htm)),
and add two facts the spike did not measure, quoted rather than tested: *"By default, Solace
event brokers refresh any JWKS public keys obtained from an OAuth authorization server once
every twenty four hours"*, and *"JWKS keys expire when they are older than three times the
`jwks-refresh-interval`."*

### 4. Key persistence across an issuer restart — `BE-D5`'s explicit requirement

`BE-D5`, verbatim: *"A gateway restart must never invalidate every live ticket."* The test kills
the issuer process, relaunches it, and checks a ticket minted **before** the restart. It runs
after the rotation test on purpose, so what survives is the *rotated* state rather than the
first-boot one:

```
issuer restart: {"before":{"kid":"spike-3","kids":["spike-2","spike-3"]},
                 "after": {"kid":"spike-3","kids":["spike-2","spike-3"]}}
```

Same active kid, same published set, and the pre-restart token still connects. Nothing is
re-fetched to make that work — the broker's cached key is unchanged because the issuer
republished an identical JWK set. Liveness during the restart is probed on `/stats`, never on
`/jwks.json`, so the probe cannot spend the very counter the assertion reads.

⚠️ **Operational note for anyone re-running the spike.** `run-tests.sh` starts the issuer only
if `:8090` is not already serving. If `issuer/keys.json` is deleted while an issuer is still
running, the stale process keeps serving the old keys and the new file is never read. Kill the
issuer as part of any teardown — the cold-start recipe below does.

### 5. Renewal — a reconnect, invisible to the app, and it drops messages

`BE-D5`'s tickets are *"renewable over REST"*, and `BE-D6`'s chart tails ride the same session,
so what a renewal does to a live session is a design input, not a detail.

**The SDK has no public `updateAccessToken`.** `SolaceClient` does not expose one; the method
exists only on the protocol adapter (`src/protocols/smf/smf-adapter.ts:733`) and is reached
through the `onTokenExpired` callback (`src/client.ts:183-207`). The test therefore drives the
real path: a 70 s ticket with `refreshBeforeExpirySeconds: 60`, which fires the renewal about
10 s after connect. Read out of the SDK source before being relied on — the adapter's
implementation is literally *store subscriptions → `session.disconnect()` → `connect()` with
the new token → re-`subscribe()` each one*.

Measured, with `sm-internal` publishing a numbered sequence every 10 ms throughout
(cold-start run):

```
renewal measured: {"renewedEvents":1,"mintCallbacks":1,
  "appVisibleDisconnects":0,"appVisibleReconnects":0,
  "firstClientName":"…/0049","clientNamesAfter":["…/0051"],
  "published":996,"received":995,"missingCount":1,"missing":[812],
  "longestArrivalGapMs":39}
```

Three findings:

- **The broker session is REPLACED.** The pre-renewal `clientName` is gone from the SEMP monitor
  and a different one is serving the same client. Asserted from the broker, because the SDK
  never says so.
- **The app is told nothing but `auth:tokenRefreshed`.** Zero `disconnected` events, zero
  `reconnected` events — the SDK emits `disconnected` only on a user-requested disconnect
  (`src/client.ts:342`). A console cannot detect the seam from SDK events.
- **Messages published during the reconnect are lost.** Direct messaging has no replay, so the
  restored subscription brings the subscription back and never the messages. Reproducible:
  2 lost on each of three consecutive runs at a 10 ms publish cadence, 1 on the cold-start run,
  with the longest arrival gap (~30–40 ms) landing on the seam. **At the 50 ms cadence this test
  first used, ZERO were lost** — the outage is real but short, and the sampling rate has to
  resolve it. The deterministic fact underneath, which no sampling rate can hide, is the session
  replacement.

**Chart-seam consequence, confirmed:** a data-bearing tail must **re-backfill across every
renewal**, on the console's own policy, because there is neither a signal to react to nor a
replay to recover from.

### 6. The renewal deadline is HARD — the broker disconnects at `exp`

`MsgVpnAuthenticationOauthProfile.disconnectOnTokenExpirationEnabled`, verbatim: *"Enable or
disable the disconnection of clients when their tokens expire."* Default `true`, and `true` on
this VPN. Measured rather than read off the property description — a live, subscribed session on
a 30 s ticket:

```
session dropped at ticket expiry: satisfied after ~31s (ticket lifetime 30s)
```

So `BE-D5`'s renewal is not a convenience. Miss `exp` and the mesh session is gone mid-chart,
and per Task 4's finding the client is told only `Unauthorized`. Combined with finding 5, the
gateway must re-ticket **proactively, on its own clock, well before `exp`** — which is exactly
what the SDK's `refreshBeforeExpirySeconds` is for.

### 7. The reverse proxy — Task 1 finding 8 settled, and its premise was wrong

Task 1 wrote *"SMF keepalives are minutes apart, so nginx's default 60s would kill idle
sessions"* and flagged the real number as design §16 question 5, for Task 5.

**Measured, from the broker's own per-client byte counters on an idle subscribed session:**

```
  +1s  rx=1062 tx=580  delta={}
  +2s  rx=1062 tx=580  delta={"drx":0,"dtx":0}
  +3s  rx=1074 tx=600  delta={"drx":12,"dtx":20}
  +6s  rx=1086 tx=620  delta={"drx":12,"dtx":20}
  +9s  rx=1098 tx=640  delta={"drx":12,"dtx":20}
 +12s  rx=1110 tx=660  delta={"drx":12,"dtx":20}
 +15s  rx=1122 tx=680  delta={"drx":12,"dtx":20}
```

**Every 3.0 s, in both directions** — 12 bytes client→broker, 20 bytes broker→client. That
matches solclientjs's own defaults, read from its source rather than recalled:
`keepAliveIntervalInMsecs` returns `3e3` when unset and `keepAliveIntervalsLimit` returns `3`
(`node_modules/solclientjs/lib/solclientjs-debug.js`, the `SessionProperties` accessors). So the
client gives up after ~9 s of silence, and traffic crosses the proxy every 3 s.

**Confirmed end to end:** with `proxy_read_timeout`/`proxy_send_timeout` lowered to **10 s** and
nginx restarted (value verified inside the container), an idle subscribed session survived
**60 s** with no disconnect, at the broker and at the SDK.

```console
$ docker compose exec nginx grep proxy_read_timeout /etc/nginx/nginx.conf
      proxy_read_timeout 10s;
$ node idle-probe 60
IDLE PROBE: session solclientjs/…/0001 up; going idle for 60s
  +10s brokerSeesSession=true sdkIsConnected=true disconnectedEvents=0
  …
  +60s brokerSeesSession=true sdkIsConnected=true disconnectedEvents=0
RESULT after 60s idle: brokerSeesSession=true sdkIsConnected=true
```

**So nginx's own 60 s default would never have killed an idle session, and Task 1's 3600 s was
solving a problem that does not exist.** The measured floor is ~10 s (3× the keepalive). The
committed value stays 3600 s for headroom a Node client cannot exercise — a **browser**
throttles timers in a backgrounded tab, so a real console's keepalives may stretch far past 3 s.
That headroom is a judgement, explicitly labelled as such in `nginx.conf`; the floor is the
measurement.

Runs at 5 s, 2 s and 1 s also survived, but those configs were not re-verified inside the
container the way the 10 s one was, so **10 s is the number this spike stands behind** and the
shorter runs are not quoted as results.

Nothing beyond the `Upgrade`/`Connection` headers and `proxy_http_version 1.1` was needed —
Task 1 finding 9 (the `/mesh` path passes through unrewritten) still holds, now across
rotation, renewal-reconnect and expiry-disconnect traffic as well.

### 8. Cold start from nothing — `provision.sh` alone reaches the tested state

Required before G1 signs off. Everything destroyed — broker volume, certs, and the issuer's
private key — then rebuilt by the committed scripts only. Real transcript:

```console
### 2026-08-27T17:10:19Z  cold start: tearing everything down
killed the running issuer
 Container sm-spike-broker  Removed
 Volume mesh-authz_sm-spike-solace-data  Removed
 Network mesh-authz_default  Removed
$ rm -rf certs issuer/keys.json
--- what is left ---
. .. .gitignore docker-compose.yml issuer nginx.conf node_modules package-lock.json
package.json provision.sh README.md run-tests.sh setup-certs.sh tests
### 2026-08-27T17:10:21Z  ./setup-certs.sh
Certificate generation complete. Files in ./certs:
### 2026-08-27T17:10:22Z  docker compose up -d
 Container sm-spike-broker  Started
 Container sm-spike-nginx  Started
### 2026-08-27T17:10:43Z  SEMP ready: {'platform': 'VMR', 'sempVersion': '2.48'}
### 2026-08-27T17:10:53Z  npm test (cold)
 ✓ tests/00-connect.test.mjs        (2 tests)    561ms
 ✓ tests/01-substitution.test.mjs   (6 tests)    240ms
 ✓ tests/02-authzgroup.test.mjs     (7 tests)    510ms
 ✓ tests/03-coarse-and-i5.test.mjs  (9 tests)   3752ms
 ✓ tests/04-rotation-renewal.test.mjs (6 tests) 165042ms
 Test Files  5 passed (5)
      Tests  30 passed (30)
### exit=0 at 2026-08-27T17:13:49Z
```

**3 minutes 30 seconds from an empty directory to 30 green tests**, with the whole
`spike-4` … `spike-8` key history from the exploratory runs thrown away — the issuer generated
`spike-1` from nothing and the rotation test drove it to `spike-3`.

Two ordering facts the transcript encodes, both load-bearing:

1. **`setup-certs.sh` must run BEFORE `docker compose up -d`.** nginx bind-mounts `./certs`; with
   the directory missing the container starts and fails. `run-tests.sh` regenerates certs when
   they are absent, but by then nginx has already been given nothing to mount.
2. **Kill any running issuer first.** See the operational note under finding 4.

`npm test` takes ~2 min 55 s now, essentially all of it `tests/04` waiting out two ~60 s JWKS
refresh floors. That is the measurement, not overhead that can be tuned away.

---

## Deviations from the task text

Everything below differs from Task 1's written text in
[`docs/plans/backend/001-mesh-foundations-and-gates.md`](../../../docs/plans/backend/001-mesh-foundations-and-gates.md).

1. **The negative test is not `expect(publish(...)).rejects.toThrow()`** — it cannot be. See
   finding 1. Replaced with broker-side proof plus a positive control, and verified to fire by
   planting a violation.
2. **SDK import path.** The plan writes `import SolaceWebAppSDK from 'solace-webapp-sdk'`. The
   package has no `exports` map and its `main` is the UMD bundle, so under Node ESM that
   import yields an empty module (`Object.keys(m)` → `[]`). The test imports
   `solace-webapp-sdk/dist/solace-webapp-sdk.es.js`. Everything else about the SDK matched the
   plan: `connect({url, vpn, protocol, auth})`, `auth: {type:'oauth', accessToken}`,
   `auth: {type:'basic', username, password}`, `subscribe(topic, handler)`,
   `publish(topic, payload)`, `disconnect()` are all exactly as written.
   `NODE_TLS_REJECT_UNAUTHORIZED=0` alone is enough for the self-signed cert — the SDK's
   `tls: {rejectUnauthorized:false}` option was not needed.
3. **Broker image is Enterprise, not `solace/solace-pubsub-standard`.** The plan's Step 1
   describes the source compose file as starting the Standard image; it actually defaults to
   Enterprise, and OAuth requires it. `pull_policy: missing` was added because the Enterprise
   image is loaded from a local tarball and is not pullable.
4. **`run-tests.sh` is a file the plan does not list.** Step 6 requires the whole sequence
   behind `npm test`; a background issuer plus an ordered vitest run does not belong in a JSON
   string. `npm test` runs it.
5. **OAuth profile is named `sm_spike_oauth`,** not a hyphenated name — see finding 4.
6. **`provision.sh` configures more than the plan enumerates:** `authorizationGroupsClaimName`
   (finding 2), the `default` client username's ACL binding (finding 3),
   `resourceServerValidateTypeEnabled` (finding 5), and guaranteed messaging on the `default`
   client profile. The last was added while investigating finding 1 and left in place —
   Tasks 2–4 may want persistent delivery, and `maxMsgSpoolUsage` is set for the same reason.
   **Three further OAuth-profile settings the plan does not name, recorded here so the list is
   complete:** `usernameClaimName: "sub"` — the claim the broker maps to a client username, and
   the whole premise of candidate (a)'s `$client-username` substitution, so it is load-bearing
   rather than incidental; `resourceServerParseAccessTokenEnabled: true` — the broker must parse
   the JWT to read any claim at all; `resourceServerValidateScopeEnabled: false` — the spike's
   `scopes` claim is deliberately *not* validated at the broker, which is what lets test 3
   measure that the claim is **inert** for authorization (turning it on would refuse the token
   rather than admit it with the claim ignored, and the measurement would be a different one).
7. **The baseline test awaits its `subscribe` before publishing.** The plan's sketch wraps the
   un-awaited `subscribe()` inside the `new Promise` executor; the broker confirms
   subscriptions asynchronously, so publishing straight afterwards is a race. Same assertions,
   no flake.
8. **`setup-certs.sh` produces `server.crt`/`server.key` only.** The source script's Keycloak
   cert, client/mTLS certs, PKCS#12 bundle and expired-cert fixture are all dropped — this
   spike terminates TLS at nginx and nothing else.
9. **The broker's REST-TLS `9443` port mapping is dropped** from the source compose file. Host
   9443 belongs to **nginx**, which is the `wss://localhost:9443/mesh` origin standing in for
   design §5's production path; the broker's plaintext web transport is reached only through it.
   Recorded, not a behaviour change — the broker's own TLS listener is simply unused. (Host
   `8008` **is** still published, and deliberately: Task 10's compose-gated backend IT connects
   to `ws://localhost:8008` directly.)
10. **A `.gitignore` was added** for the issuer's private key material and the generated TLS
    certs. Report-only: no test reads it, nothing behaves differently, but a spike that mints
    signing keys must not be one `git add -A` away from committing them.

### Task 2

1. **The substitution syntax is `$client-username`, not `${clientUsername}`.** The task text
   said to confirm it and record what it actually is; see "The exact broker config that made it
   work" above for the three sources. The guessed spelling is provisioned alongside the real one
   on a parallel `stream2/` namespace as a grammar control, and is proven inert.
2. **`.resolves.toBeDefined()` cannot pass.** `subscribe()` is declared `Promise<void>` in the
   SDK (`src/client.ts:436`), so it resolves to `undefined`. The assertion is
   `.resolves.toBeUndefined()`; resolution is still the broker's `SUBSCRIPTION_OK`, which is
   what the test means to observe.
3. **`.rejects.toThrow()` is tightened to an ACL-denial assertion.** A bare rejection is also
   what a timeout or a dropped session produces. Each negative asserts
   `AuthorizationError` / `SUBSCRIBE_ACL_DENIED`. The task text's assumption that a subscribe
   denial is observable client-side was checked against the SDK source first and holds — unlike
   publish (finding 1).
4. **Four tests beyond the task's one.** The mirror case (`grant-222`), the
   `originalClientUsername`/`clientUsername` distinction, the grammar control, and the
   unmatched-subject probe. The task text asks for a verdict on whether pre-created usernames
   are required; only the probe answers that, and it is the finding that changes `BE-D5`'s cost.
5. **One Task 1 object was extended:** `acl-browser-baseline` gained a **subscribe** exception
   on `spike/probe/$client-username/>`. It is additive, outside `sm/v1`, leaves the profile's
   publish posture untouched, and every Task 1 assertion still passes (`npm test` → 8 passed).
   Without it the unmatched-subject question cannot be measured at all, because that path
   reaches only `default`'s profile and that profile allows all of `sm/v1/>`.
6. **`spike/probe/…` is a topic outside the `sm/v1/` namespace.** Measurement scaffolding in a
   throwaway harness, deliberately named so it can never be mistaken for a product topic.

### Task 3

1. **The groups-claim mapping is turned on for a SECOND OAuth profile, not for
   `sm_spike_oauth`.** The task text says to enable it on "the OAuth profile". Doing that
   refuses every token Tasks 1–2 mint and turns 3 of the 8 existing tests red — the measurement
   is quoted in full under "Coexistence cost", because the breakage *is* candidate (b)'s cost
   and had to be recorded rather than avoided. Enabling it on `sm_spike_oauth_b` and selecting
   that profile per connection keeps the mapping genuinely ON for candidate (b) while leaving
   Task 1's profile untouched. Both profiles are provisioned by `provision.sh`; nothing is
   configured out of band, and no earlier test was edited.
2. **`.resolves.toBeDefined()` → `.resolves.toBeUndefined()`**, and `.rejects.toThrow()` →
   an ACL-denial assertion. Same reasons as Task 2 deviations 2 and 3.
3. **`connectRetries: 0` on every connection in this test file.** Without it a refused login is
   retried forever and the negatives die on vitest's timeout instead of on the broker's 403.
   See "Finding: by default the SDK retries a rejected login forever".
4. **Six tests beyond the task's one.** Precedence against client usernames, the
   default-profile coexistence control, the absent/empty-claim refusal, the unknown-group
   refusal, the string-form claim, and the subscribe-only config assertion. The task asks for a
   verdict plus a cost note; the cost note is only defensible because precedence and
   coexistence were measured, and those are the two facts that decide whether candidates (a)
   and (b) can be combined.
5. **The issuer change is slightly wider than the sketch.** The task text gives
   `new SignJWT({ ...JSON.parse(body) })`. Implemented as
   `new SignJWT({ scopes: [], ...claims })` so a body that omits `scopes` still produces the
   exact token shape Tasks 1–2 measured; anything in the body overrides it. `expiresIn` is
   **not** added — that is Task 4's.
6. **`op-2` is used as the task text's subject, and the other probe subjects (`op-3`…`op-6`)
   follow its shape.** None has a client-username object, which is the point: cost 2 means a
   subject that has one never reaches the group at all.

### Task 4

1. **`issuer/issuer.mjs` is modified, and the task's Files list does not name it.** The task's
   own test text requires the issuer to honour an `expiresIn` field, which Task 3 explicitly
   left to Task 4 (Task 3 deviation 5). Authorised by the controller as an extension of scope to
   exactly that change. Implemented as a **minting directive**, not a claim: `expiresIn` is
   destructured out of the body so it never reaches the payload, and defaults to `'15m'` —
   verified that a body without it still mints `exp - iat = 900`, so Tasks 1–3's token shape is
   byte-identical. Nothing else in the issuer changed.
2. **`.resolves.toBeDefined()` → `.resolves.toBeUndefined()`**, and `.rejects.toThrow()` → an
   ACL-denial assertion. Same reasons as Task 2 deviations 2 and 3.
3. **The expired-ticket test mints, waits, and only then connects.** The task's sketch reads
   `mint(...)` then `connect(t)` with the token already in hand; the file's `connect()` helper
   mints internally, so it takes an optional pre-minted `accessToken`. Without that the 2 s wait
   would happen *before* minting and the token would still be valid at login — the test would
   pass on a token that never expired, which is the R6 false-green class exactly.
4. **The expired-ticket assertion is not `rejects.toThrow()`.** It asserts the measured refusal
   shape — `AuthenticationError` / `AUTH_FAILED` / `"Unauthorized"` — and that no session was
   left behind. Task 3's README warns against matchers that assume one broker refusal string;
   this refusal turned out to be a **fourth** shape, `401` rather than the group family's `403`,
   so the string was measured first and only then written down. See the finding above.
5. **A control test for the expired-ticket fixture (test 7).** The same short-lived minting path
   used *before* expiry connects. Without it, test 6 proves only "a token minted with
   `expiresIn` is refused", which an issuer bug would also produce.
6. **Five tests beyond the task's three.** The delivery measurement (test 2 — added in review;
   see item 9), the `scopes`-claim-is-inert measurement (test 3), the `I5` mirror (test 5), the
   expiry control (test 7), and the subscribe-only config assertion
   (test 9). Test 3 is the one that matters: it is what turns "candidate (c) is coarse" from a
   description of the config into a measurement, and it is why F3 needs a second profile.
7. **`acl-globex` carries a `stream` exception as well as `notify`.** The task text says the
   second tenant gets "the `globex` prefixes"; both were provisioned, so the mirror test can
   deny an acme *stream* topic to a globex ticket and still have an in-tenant stream positive as
   its control.
8. **`provision.sh`'s section labels were renumbered `[n/10]` → `[n/13]`** (and later to
   `[n/14]` when the final-review fail-safe section was appended — see the Task 11 deviation).
   Cosmetic; no behaviour in sections 1–10 changed, and Tasks 1–3's 15 tests are green
   throughout.
9. **Test 2 (delivery) was added in review, and it changed a claim from inferred to measured.**
   As first written, the exposure headline said an in-tenant holder "enumerates every live
   grant-id … and receives their payloads" and called that a measurement — but every assertion
   behind it measured only that the *subscriptions were admitted*. Admission is not receipt: no
   message was published anywhere in the file. Rather than soften the wording, the measurement
   was taken — `sm-internal` publishes to two distinct grant streams and the wildcard session
   recovers both grant-ids and both payloads. The sentence is now true as written.
   The same review pass found the delivery test's cross-tenant assertion was measuring a topic
   filter rather than the ACL (plant C), and it was strengthened accordingly.

### Task 5

1. **`c.updateAccessToken(...)` does not exist.** The task's test sketch calls it on the client.
   `SolaceClient` exposes no such method — it lives only on the protocol adapter
   (`src/protocols/smf/smf-adapter.ts:733`) and is reached through the `onTokenExpired`
   callback (`src/client.ts:183-207`). Verified in the SDK source before being worked around.
   The test drives the real callback path instead, which is also what a console would do.
2. **`c.on('disconnected', …)` counts zero, and that is the finding rather than a broken test.**
   The task's sketch expects renewal to surface as disconnects. It does not: the SDK emits
   `disconnected` only for a user-requested disconnect. The session replacement is asserted
   from the SEMP monitor instead, and the absent event is recorded as an observability result.
3. **The renewal test measures message LOSS, not just "the subscription still works".** The task
   text asks whether the subscription survives. It does — but at the 50 ms publish cadence the
   test first used, nothing was lost, which would have understated the seam. The cadence was
   tightened to 10 ms and the loss became reproducible (2, 2, 2 across three runs; 1 on the
   cold-start run). The task's question is answered *and* `BE-D6`'s re-backfill requirement is
   measured rather than assumed.
4. **The rotation test does not "retry connect every 5s and record how long until the broker
   accepts it" naively.** That works for adoption, but not for eviction: a token on an
   evicted-but-cached key still *succeeds*, so polling it produces no stimulus and it would
   never die. Each eviction poll therefore presents a **post-rotation** token first (the poke)
   and re-tests the evicted one after. Without that the test would hang until its budget and
   report a false "the key was never revoked".
5. **Three tests beyond the task's three.** The `default → acl-subst` composition (explicitly
   deferred to this task by Task 2), the force-refresh absence read from the served action spec,
   and the expiry-disconnect measurement. The first is candidate (a)'s last open cell and
   changes a label from inferred to measured; the third is what makes "renew proactively" a
   requirement rather than advice.
6. **`GET /stats` on the issuer is an addition the task's Files list does not name.** The task
   requires a *measured* JWKS refresh latency; without an observation point at the JWKS endpoint
   itself, "the broker accepted the token" and "the broker re-read the endpoint" are
   indistinguishable, and the adoption assertion could pass on a cached key. It records
   timestamps and counts only — no key material.
7. **`nginx.conf` is modified, and the task's Files list does not name it.** Comment only: Task 1
   finding 8 explicitly routed the keepalive number to Task 5, the measurement contradicts the
   comment that was there, and leaving a measured-false statement in a committed file is worse
   than the one-line diff. No directive changed.
8. **Five exploratory measurements are recorded above but are NOT committed tests:** the ~60 s
   floor between JWKS fetches; the force-refresh levers (same-value vs changed-value
   `endpointJwks`, and the `enabled` toggle); **the retirement measurement — an evicted key
   surviving 151 s of retries with no other traffic, then dying 70 ms after one current-kid
   login (§2, full timestamped transcript)**; the 3.0 s keepalive cadence; and the
   proxy-timeout runs.
   Each needs either a multi-minute quiesce or a container restart, which does not belong in
   `npm test`. They are written up with their raw timestamps so they can be re-run by hand, in
   the same style as Tasks 2–4's prove-it-fires plants.

### Task 11 (final whole-branch review)

1. **`provision.sh` gained section `[14/14]` — the stock `default` ACL PROFILE is pinned to
   `disallow` on all three default actions.** Not in any task text. Every profile the script
   *creates* states its defaults explicitly; the stock `default` profile is the one nobody
   wrote, and it carries the vendor's. The `default` CLIENT USERNAME is already bound to
   `acl-browser-baseline` (Task 1 finding 3) — a different object — but any client username
   created later without an explicit `aclProfileName` lands on the stock profile and inherits
   whatever the vendor chose. Fail-safe only: nothing in the suite resolves there, and all 30
   tests stay green.

## Not committed

`issuer/keys.json` (RSA private key) and `certs/` (nginx TLS private key) are generated at
runtime and git-ignored. The `sm-internal`/`sm-internal` pair in `provision.sh` is the one
credential committed on purpose: a spike-local throwaway that exists nowhere else.
