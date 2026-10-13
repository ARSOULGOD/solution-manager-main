# §11.3 broker ACL spike — sub-organization / environment isolation

Throwaway harness, **not product code**. Executes
[`console-integration.md` §11.3](../../../docs/design/common/contracts/console-integration.md#113--the-broker-acl-spike-matrix-written-not-executed) — the matrix that open items 32, 33 and 35 have been
waiting on. Result document, with the leg table, the finding-by-finding disposition of the round-1
reviews (rounds 1 to 4) and the item-by-item closure mapping:
[`docs/design/backend/spike-scope-acl-result.md`](../../../docs/design/backend/spike-scope-acl-result.md).

Adapted from the G1 spike (`backend/spikes/mesh-authz/`), which measured ONE substitutable level.
This spike measures the case §11.3 says is two levels deeper: a subscriber whose identity carries
only **one** substitutable level (its client username / OAuth `sub`), confined to a **narrow
two-level scope** (sub-organization AND environment) inside the notify tier.

**Round 1 (2026-09-30)** fixed the previous run's review: a browser identity could bind a
prod-subscribed queue (subscribe ACLs are not consulted on a queue bind), a JWT `sub` containing
`/` escaped into another session's stream branch, and several evidence gaps. See the result
document's disposition table.

**Round 2 (2026-09-30)** fixed the second review: the issuer's `sub` rule only rejected characters,
so a `sub` equal to a *non-browser* client username (`sm-internal-orgA`, a `*-ctl` or `*-abl-*`
identity, even the broker's built-in `#client-username`) became that internal identity (every
provisioned client username is reachable over OAuth); and a ticket was not bound to an
organization (both VPNs trusted the same audience). Now: browser `sub` comes only from the reserved
grammar `^br-[0-9a-f]{16}$` (issuer allow-list) and provisioning + the suite assert from SEMP that
every grammar-matching username is `sm-browser` and no internal/control/ablation username matches;
each VPN requires its own audience `sm-mesh-acl-<org>`; leg 14 is also run in the composed shape
(notify grant + stream branch on one session). See `tests/07`, `tests/08` and the result document.

**Round 3 (2026-09-30)** fixed the third review: the round-2 text said the browser-to-identity
binding was "measured", but legs 16-17 only measure that non-browser usernames cannot be minted
and that a ticket for one organization is refused at the other; the spike's `/token` has no caller
authentication, so any caller could take **another grant's** `sub` (and a 3650-day lifetime). Now:
a principal-bound `/ticket` on the issuer (sub, audience and lifetime only from the principal's
server-side grant record; the request carries none of them), with legs 19a-19e showing that a
principal cannot obtain another's grant and that the unauthenticated `/token` can (load-bearing).
Also: **delivery is now counted at the broker** (per-client `dataTxMsgCount`), not only by SDK
handlers that filter by their own local pattern; **temporary queues and topic endpoints** are
measured (legs 15j/15k) with `sm-internal` controls; the **other-VPN path** (the browser wss proxy
reaches every Message VPN with web transport on, including the broker's built-in `default`) is
measured and mitigated (tests/10, provision.sh section 7); the **ACL assertions** now cover every
browser-facing username's ACL in every VPN (`default` and composed ACLs included), assert the
subscribe side, follow SEMP paging, and are proven able to fail by planted violations
(tests/11). See the result document's round-3 disposition table.

**Round 4 (2026-09-30)** fixed the five MINOR findings of the review that accepted round 3:
(1) the issuer's principal lookup is a `Map` (a credential naming an `Object.prototype` key such as
`__proto__` was a "known principal" and got a signed ticket; `tools/issuer-proto-probe.sh` measures the
round-3 issuer against the current one; 19b-auth asserts HTTP 401 for every prototype key);
(2) `provision.sh` no longer carries its own Python copy of the identity/ACL assertion: it runs
`tools/acl-check.mjs`, the same SDK-free function (`tests/lib/acl-check.mjs`) the suite asserts, and
tests/11 plants 14 violations against the function AND that entry point AND runs `provision.sh`
itself against a plant; (3) `tests/13` measures basic authentication as an internal identity through the
browser-facing gateway (a hole) and a client-connect-address restriction on `acl-internal` with positive
controls; (4) `tests/12` measures the broker's token validation (expired, `alg:none`, foreign key under the
issuer's `kid`, swapped `sub`, cross-VPN ID token, client-supplied SMF `userName`, and expiry during a live
session); (5) `tests/05` gains admin-created durable **topic endpoints** (browser bind, gate-forced wire
bind, `sm-internal` controls).

## Fixture

| | |
|---|---|
| Organizations | `orgA`, `orgB` — **two Message VPNs**, one broker container (`docker-compose.yml`) |
| Sub-organizations / environments | `su1`,`su2` x `dev`,`prod` — topic levels — **8 leaf environments**, one instance per leaf, health + placement fact on each, plus the org-terminating `catalog/package` topic |
| Browser identity under test | `br-000000000000d001` (= the `[orgA, su1, dev]` grant; was `orgA-su1-dev`) — OAuth/JWT `sub`, **orgA's** VPN, client profile **`sm-browser`**. Also `br-…c001` (grant + exact catalog exception) and `br-…c101` / `br-…c102` (composed: notify + stream) |
| Reserved browser grammar | `^br-[0-9a-f]{16}$` — the only `sub` shape the ruled issuer mints |
| Non-browser usernames (never mintable) | `sm-internal-orgA/B`, `orgA-su1-dev-ctl`, `orgA-abl-*` (reachable only via `/raw-token`), plus the broker built-in `#client-username` |
| Topic shape | `sm/v1/{org}/{subOrg}/{env}/notify/solution/{solutionId}/instance/{instanceId}/{aspect}` (§11.2) |

Notify-tier mechanism: **one ACL profile per grant with a LITERAL subscribe exception**
(`acl-orgA-su1-dev` -> `sm/v1/orgA/su1/dev/notify/>`); the stream tier (leg 14) uses the
`$client-username` substitution on the VPN's `default` client username — on **both** VPNs since
round 2 (orgB's `default` is pinned to `acl-orgB-stream-subst`, no longer stock).

### Client profiles (round 1, the BLOCKER 1 fix)

| Profile | Used by | Guaranteed send / receive / endpoint-create | shared subs / transacted / bridge | flows / endpoints |
|---|---|---|---|---|
| `sm-browser` | every browser-facing identity (every `br-…` username and the `default` client username) | all **false** | all **false** | 0 / 0 |
| `sm-internal` | `sm-internal-<vpn>` publishers and the positive-control identity `orgA-su1-dev-ctl` | all true | all true | 1000 |
| `default` (stock) | nobody — **pinned closed** as a fail-safe | all false | all false | (stock) |
| `sm-abl-{create,recv,send}-off` | ablation identities `orgA-abl-*` | exactly ONE flag off | on | 1000 |

Why: Solace does not consult subscribe-topic ACLs when a client binds directly to a durable
endpoint, so a subscribe ACL only confines a client that also cannot bind or create a queue
(docs cited in the result document).

## Running it

Prerequisites: Docker with the local image `solace-pubsub-enterprise:10.25.0.177` (Enterprise is
required for OAuth client authentication; the image is loaded from the Solace support-portal
tarball and is never pulled), Node 20+, `openssl`, `curl`, `python3`.

### SDK

The Solace Web App SDK is **not** a `package.json` dependency any more (it used to be an absolute
`file:/Users/...` path, so the harness only ran on one machine). Point
`SOLACE_WEBAPP_SDK_PATH` at a **built** checkout — a directory containing
`dist/solace-webapp-sdk.es.js` — and `run-tests.sh` links it with `npm install --no-save`:

```bash
export SOLACE_WEBAPP_SDK_PATH=/path/to/RapidPilot/webAppSDK
```

### Steps

```bash
./setup-certs.sh                 # once — self-signed TLS for nginx (wss://localhost:9444/mesh)
docker compose up -d             # sm-spike-acl-broker (VPNs orgA/orgB), sm-spike-acl-nginx
./run-tests.sh                   # provisions (idempotent), starts the issuer if needed, runs all 13 files
```

`docker compose` uses whichever Docker context/`DOCKER_HOST` is current; the spike never switches
context. Every published port is bound to **127.0.0.1** (SEMP is `admin/admin`).

Ports: `8081` SEMP, `8009` SMF WS, `5551` health, `9444` wss (nginx), `8091` token issuer. G1's
range (8080/5550/8008/9443/8090) is avoided so both spikes can coexist.

## What each test file measures

| File | Legs |
|---|---|
| `tests/01-notify-legs.test.mjs` | 8-leaf fixture liveness; 1–3 (allow **and deliver exactly the granted leaf**); 4–10 (refuse, broker-held subscription set unchanged, resolved ACL + client profile asserted, **and only the granted leaf delivered afterwards**); 11a/11b with an **orgA-side liveness control**. Round 3: delivery is also counted **at the broker** (`dataTxMsgCount` delta per session) |
| `tests/02-leg12-publish.test.mjs` | 12A config (ACL, client username -> `sm-browser`, profile flags; round 3 **12A-all**: every browser-facing username and its ACL in every VPN, `default` and composed ACLs included, subscribe side included, paged); 12B what each session **actually resolved to**; 12C **measured** non-delivery of direct and guaranteed publishes (witness, self-loop, watch-queue spool, broker discard counter), a capability-spoofed variant, a flag-isolation control, and an attribution control |
| `tests/03-leg13-catalog-exception.test.mjs` | 13 — **measurement only, no pass/fail verdict** on §11.4; delivery asserted |
| `tests/04-leg14-stream-tier.test.mjs` | 14 (exploratory) + **the `sub` escape**: real without the issuer rule, blocked with it, invariant over a corpus |
| `tests/05-durable-endpoint-bypass.test.mjs` | **BLOCKER 1**: bind / createIfMissing / browse by the browser identity (library level AND wire level), positive control under `sm-internal` with the identical ACL, single-flag ablations, shared subscriptions. Round 3: **temporary queues and non-durable topic endpoints** (15j/15k), plain and gate-forced, with `sm-internal` controls |
| `tests/06-write-counts.test.mjs` | **measured** SEMP write counts (grant, extension, revocation, composed grant W8) cross-checked by inventory diff, plus revocation semantics |
| `tests/07-identity-binding.test.mjs` | **round 2**: 16-config (SEMP invariants, every username in every VPN); 16a (`/token` refuses every non-browser username); 16b (**the raw endpoint escalates** — the refusal is load-bearing); 16c (every mintable ticket resolves to `sm-browser`); 16d (raw-wire basic logins incl. empty password refused); 17a–c (cross-organization tickets refused, broker `CONNECT_FAILED_ERROR`); 17d (positive controls); 17e (relax the audience and the leak reappears) |
| `tests/08-composed-shape.test.mjs` | **round 2**: leg 14 with two subjects that hold a notify grant AND a stream branch on one dedicated ACL profile |
| `tests/09-ticket-binding.test.mjs` | **round 3**: 19a (each principal's `/ticket` carries exactly its own grant, fixed 900 s lifetime, no caller claims); 19b (asking for another grant / organization / audience / lifetime / claims, by body, query string or header, is HTTP 400 or ignored — no other grant is ever issued); 19b-auth (401); 19e (positive control: the prod principal legitimately reads prod, the dev principal does not); **19c/19d (load-bearing)**: the unauthenticated `/token` mints another grant's ticket (reads su1/prod, counted at the broker) and a 3650-day one |
| `tests/10-other-vpn-path.test.mjs` | **round 3**: 20a the stock `default` VPN is reachable from the browser wss endpoint and the install check fires on it; 20b three mitigations (VPN disabled, web transport off, auth type none -> internal) each measured and restored; 20c the provisioned mitigation, install check clean, organizations unaffected |
| `tests/11-acl-check-selftest.test.mjs` | **round 3**: the browser-facing ACL check is shown to fire on planted violations and to be clean after each removal; SEMP paging (page size 1-2) is followed. **Round 4**: 14 plants (V12 default-username pin, V13 audience, V14 exception syntax added), each also run through `tools/acl-check.mjs`; `./provision.sh` itself run against a plant |
| `tests/12-token-validation.test.mjs` | **round 4**: 22-control; 22a expired ticket; 22b `alg:none`; 22c foreign key under the issuer's `kid`; 22d swapped `sub`; 22e ID-token path (cross-VPN, controls); 22f client-supplied SMF `userName` ignored; 22g expiry during a live session (all through the wss gateway with raw `solclientjs`, refusals are the broker's) |
| `tests/13-internal-via-gateway.test.mjs` | **round 4**: 21a basic auth as an internal identity through the browser wss endpoint (measured hole); 21b broker-seen source addresses of the gateway vs the backend path; 21c `acl-internal` client-connect default disallow + backend-address exception (temporary): gateway logins refused, backend and browser-ticket controls connect; 21d restored |
| `tests/05` (15l-*) | **round 4**: admin-created durable topic endpoints `sm-te-prod` / `sm-te-dev`: browser plain + gate-forced binds, `sm-internal` controls |
| `tools/acl-check.mjs` | the install-time identity/ACL assertion `provision.sh` runs (same function as the suite) |
| `tools/issuer-proto-probe.sh` | round 4: round-3 issuer (read from git `2b266d1`) vs current issuer, prototype-key credentials |
| `tools/hostile-sub-corpus.mjs` | not a test: the exploratory corpus behind the issuer's `sub` rule |

`tests/lib/common.mjs` holds the helpers, including `record()` (every leg logs
`{attempted, expected, observed, pass}` to `evidence/legs.jsonl` and stdout) and the
capability-spoof used to simulate a hostile client.

### The two-line explanation of "spoofed"

`solclientjs` refuses a guaranteed bind or persistent publish **locally** when the capability table
the broker advertised at login says the client may not ("Consumer is not supported by the broker
for this client"). An attacker is not bound by the library, so every client-profile leg is also run
with that table forced open (`spoofCapabilities()`, reaching into `session._capabilities` and
`SessionFSM._gmSendDisallowed`), which puts the operation on the wire so the **broker's** decision
and counters are what is observed. This touches library internals; it is a measurement device, not
an API.

## The issuer, the `sub` rule and `/ticket`

**Round 3:** `POST /ticket` (Authorization: Bearer principal credential, EMPTY body) is the
product-shaped path: the issuer takes `sub`, the audience and the lifetime (fixed 900 s) only from
the authenticated principal's server-side grant record (`PRINCIPALS` in `issuer.mjs`); any body
field or query parameter is HTTP 400. The credential is a static bearer string — the spike does
**not** measure real authentication, the RBAC check that decides which grant a principal holds, or
key management. `/token` (below) has **no caller authentication** and remains only as the fixture
that legs 16-17 use and that 19c/19d show to be exploitable by design.

### `/token` and its `sub` rule (rounds 1-2)

`issuer/issuer.mjs` is the spike's stand-in for the gateway ticket issuer. `POST /token {org, sub?}`
requires `org` (selects the audience `sm-mesh-acl-<org>`) and mints `sub` **only from the reserved
grammar** `^br-[0-9a-f]{16}$` (or generates one when omitted); everything else — including the old
character deny-list (`/ * > # +`, whitespace, control characters, empty, > 128 chars) which is kept
as the first check for a readable reason — is HTTP 400 with no token. `POST /raw-token` has no rule and exists **only** so the tests
can re-prove that the escape the rule closes is real. A product issuer has no such endpoint. It
listens on 127.0.0.1 (`ISSUER_HOST` overrides); the broker reaches it via `host.docker.internal`.

## Evidence

- `evidence/provision.log` — cold provisioning run, including the broker's own stored exceptions,
  client-username -> ACL/client-profile resolution and every client profile's flags.
- `evidence/vitest-run.log` — the full verbose run (solclientjs INFO chatter filtered by
  `vitest.config.mjs`).
- `evidence/legs.jsonl` — one JSON record per measured leg.
- `evidence/hostile-sub-corpus.log` — output of `tools/hostile-sub-corpus.mjs`.
- `evidence/issuer-proto-probe.log` — output of `tools/issuer-proto-probe.sh` (round 4).

Cold-run recipe (what produced the recorded evidence): `docker compose -p sm-spike-acl down -v && docker compose up -d`, then `./provision.sh > evidence/provision.log`, then `./run-tests.sh > evidence/vitest-run.log`.

## Teardown

This spike's own resources only — never touch anything not prefixed `sm-spike-acl-`:

```bash
docker compose -p sm-spike-acl down -v
pkill -f 'issuer/issuer.mjs'      # only if run-tests.sh's trap did not (it kills the copy it started)
```
