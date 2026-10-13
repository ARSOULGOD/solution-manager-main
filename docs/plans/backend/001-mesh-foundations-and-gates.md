# Backend Plan 001 — Mesh Foundations and Gates

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close Gate G1 (broker authorization spike, written result), prepare Gate G2 (backend inputs to the `D10` amendment), and build the gate-independent mesh foundations: the versioned event contract in `contracts/events/`, the Java multi-module skeleton with `I7` boundary enforcement, the shared event envelope model, and the JCSMP publisher seam.

**Architecture:** Everything here is either *evidence* (the spike — throwaway harness, durable written result), a *versioned contract* (AsyncAPI/JSON-Schema event definitions, a deliberate reviewed contract change), or a *reversible scaffold* (Maven modules, envelope records, publisher seam — nothing auth-bearing, nothing plane-semantic). Per the spec's gate-first rule (design §13), **no task in this plan implements login, tickets, RBAC checks, the websocket proxy, or any notify emission from real state** — those are plan 002, unlocked by G1+G2.

**Tech Stack:** Java 17 · Spring Boot 3.x · Maven multi-module · ArchUnit · JCSMP (`com.solacesystems:sol-jcsmp`) · Node 18+ for the spike harness (reusing the webAppSDK repo's broker tooling) · Python 3 for contract checks (matches `contracts/validation/validate-package.py`).

**Spec:** [`../../design/backend/design.md`](../../design/backend/design.md) + [`../../design/backend/decisions.md`](../../design/backend/decisions.md) (`BE-D1`–`BE-D9`, all PROPOSED). Read both before starting. Requirement: [`REQ-012`](../../requirements/REQ-012-event-driven-live-console.md).

## Global Constraints

- **Java 17, Spring Boot 3.x** (`SM-D18`). Build tool: **Maven** (the reference MIs — see `~/Work/Development/solace-cache-engine/pom.xml` — are Maven; no repo decision says otherwise).
- **Gate discipline (design §13):** tasks 1–5 are Gate G1; task 6 prepares Gate G2; tasks 7–10 are gate-independent. **Do not** build login, ticket endpoints, `/mesh` proxying, RBAC checks, or notify emission wired to real state changes — blocked on G1/G2.
- **Topic namespace:** `sm/v1/<tenant>/notify/…`, `sm/v1/<tenant>/stream/<grantId>/…`, `sm/v1/internal/…` — exactly as design §4. `v1` is load-bearing.
- **Event envelope (design §3):** every event carries `{eventId, occurredAtUtc, producer, tenant, entity{kind, ids…}, stateVersion}`. Ordering and stale-discard use `stateVersion` **only, never wall-clock** (`B/D15` 🔒).
- **Notify payloads carry identifiers only** — no config value, no secret, no metric value, no label values (`I6`). The contract's negative cases must enforce this.
- **Browsers never publish.** Only backend/internal identities publish to `sm/v1/…` (design §3: notify events are produced by backend modules after their own writes). Every browser-facing ACL has publish default-disallow with **no** publish exceptions, and the spike proves the denial.
- **`I7`:** `cp-core` and `dp-core` never depend on each other; `console-gateway` depends on their `-api` modules only. Enforced by ArchUnit in this plan, with a planted-violation test proving each rule fires (the repo's guard-self-test discipline).
- **`I2`:** never write a vendor fact from memory. The spike verifies SEMP resource names against the broker's own served spec before scripting them.
- **Verification bar for doc changes:** all markdown links resolve; decision IDs correctly prefixed.
- **Commit style:** imperative subject, body explains why, `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` footer. Never push.
- **Status tracking** lives in the GitHub Project ("Solution Manager"), not in files. Update the issue for this plan as tasks complete.

## Code Execution Contract for Sub-Agents

Every sub-agent dispatched against this plan is bound by all of the following. A task is **not done** until every applicable line holds.

**Scope**
- Implement **exactly one task** — the one named in your dispatch prompt. Never start a later task, even "while you're in there".
- Before writing anything, read: `CLAUDE.md`, `docs/design/backend/design.md`, `docs/design/backend/decisions.md`, and your task's full text including its Files/Interfaces blocks.
- Do **not** implement login, ticket endpoints, RBAC checks, production `/mesh` proxying, or notify emission wired to real state changes — those are plan 002, gated on G1/G2 — unless your task explicitly says otherwise.
- Do **not** create broker users, topics, ACL profiles, or OAuth profiles outside the committed provisioning scripts (`provision.sh` and its Task 2–4 extensions). If provisioning is missing something, change the script and re-run it.

**Facts and safety**
- Verify vendor/version facts from source (the broker's served SEMP spec, the SDK's own code, official docs via WebFetch) — never from memory (`I2`).
- No secrets, tokens, private keys, passwords, or full env-file dumps in logs, test output, commit messages, or reports. Spike-local throwaway credentials (`sm-internal`/`sm-internal`) are the one allowed exception and exist only in the committed spike files.

**Testing discipline**
- Where a task specifies a failing test first, write it first and **run it to see it fail** before implementing.
- Run the task's exact verification commands; paste real output, never a summary of expected output.
- Negative and prove-it-fires tests must fail/pass **for the intended reason** — inspect the error, don't accept any red as the right red (the R6 false-green class).
- A skipped or environment-gated check (e.g. the `broker`-tagged IT without Docker) is reported as **skipped**, never counted as complete, unless the task explicitly allows it.

**Invariants (exact values — do not paraphrase)**
- Event envelope is exactly `{eventId, occurredAtUtc, producer, tenant, entity, stateVersion, event, payload}`.
- Ordering and stale-discard use `stateVersion` **only** — never wall-clock (`B/D15` 🔒).
- Notify payloads carry no config values, secrets, metric values, or label values (`I6`).
- Browser OAuth identities are **subscribe-only**; only `sm-internal` may publish SM events in the spike.
- The spike connects via `wss://localhost:9443/mesh` — never plaintext `ws://`.
- JWT signing keys persist across issuer restart (`issuer/keys.json`) and rotate with an overlap window.
- Java serialization goes through the single `EventJson.mapper()` — never a second `ObjectMapper`.
- No CP↔DP imports (`I7`); `console-gateway` imports only `cp-api` and `dp-api`. ArchUnit rules carry planted-violation tests proving each rule fires.
- `EventPublisher.publish()` must never throw into the caller's transaction path on broker failure — log and drop.

**Done means:** tests run (output shown), negatives verified for the right reason, changes scoped to the task's listed files, one commit made (plan's commit style), and every deviation from the task text explicitly reported back.

### Dispatch template

Sub-agents implementing tasks from this plan run on **Claude Opus 5**; model downgrades require Parikshit's explicit approval (these tasks touch security gates, broker authorization, event contracts, and cross-plane boundaries). Dispatch with:

> You are implementing Task <N> from `docs/plans/backend/001-mesh-foundations-and-gates.md`.
> Implement only Task <N>. Do not start later tasks.
> Follow the Code Execution Contract in the plan.
> Preserve gates: G1 blocks mesh-access decisions; G2 blocks auth-bearing product work.
> Read `CLAUDE.md`, the backend design, the backend decisions, the plan, and the exact files listed under Task <N>.
> Use exact names/signatures from the task. Do not invent alternate module names, topic shapes, event fields, or broker identities.
> Run the task's verification commands.
> Commit only Task <N> files.
> Report files changed, commands run, deviations, and commit hash.

---

### Task 1: Spike environment — broker + reverse proxy + token issuer, OAuth connect proven

The spike lives in `backend/spikes/mesh-authz/` and is **explicitly non-production**: no Spring, no reuse into shipping code. Its durable output is Task 5's result document.

**Files:**
- Create: `backend/spikes/mesh-authz/README.md`
- Create: `backend/spikes/mesh-authz/docker-compose.yml`
- Create: `backend/spikes/mesh-authz/nginx.conf`
- Create: `backend/spikes/mesh-authz/issuer/issuer.mjs`
- Create: `backend/spikes/mesh-authz/issuer/package.json`
- Create: `backend/spikes/mesh-authz/provision.sh`
- Create: `backend/spikes/mesh-authz/tests/00-connect.test.mjs`
- Create: `backend/spikes/mesh-authz/package.json`

**Interfaces:**
- Produces: a running broker (`docker compose up`) with Message VPN `sm-spike`, an OAuth profile trusting the local issuer's JWKS; `issuer.mjs` exporting HTTP `GET /jwks.json` and `POST /token {sub, tenant, scopes[]} → {token}` on port 8090, **signing keys persisted to `issuer/keys.json`** (survive restarts — the `BE-D5` persistence requirement); nginx TLS-terminating `wss://localhost:9443/mesh` → broker websocket port (self-signed spike certs; tests run with `NODE_TLS_REJECT_UNAUTHORIZED=0`, the webAppSDK repo's own test convention); an **internal basic-auth identity `sm-internal`** (the only identity with publish rights on `sm/v1/>`); npm script `npm test` running the spike test files in order. Tasks 2–5 and Task 10's IT consume all of these.

- [ ] **Step 1: Copy the known-working broker tooling from the webAppSDK repo**

Copy `docker-compose.yml`, `tools/setup-broker.sh` (rename to `provision.sh`) **and `tools/setup-certs.sh`** from `~/Work/Development/RapidPilot/webAppSDK/` into `backend/spikes/mesh-authz/`. These already start `solace/solace-pubsub-standard` with correct shm/ulimit settings and known ports (SEMP on 8080, SMF-websocket on 8008, admin/admin), and generate the self-signed certs. Strip anything Keycloak-specific — the spike uses its own issuer. Add to the compose file an `nginx` service mounting the generated certs:

```yaml
  nginx:
    image: nginx:1.27-alpine
    ports: ["9443:9443"]
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - ./certs:/etc/nginx/certs:ro
    depends_on: [solace]
```

- [ ] **Step 2: Write `nginx.conf` — websocket reverse proxy to the broker**

The production path is `wss://<origin>/mesh` (design §5) — the spike tests **TLS from the start** so G1's proxy findings hold under the real shape (R6 finding 6):

```nginx
events {}
http {
  server {
    listen 9443 ssl;
    ssl_certificate     /etc/nginx/certs/server.crt;
    ssl_certificate_key /etc/nginx/certs/server.key;
    location /mesh {
      proxy_pass http://solace:8008;
      proxy_http_version 1.1;
      proxy_set_header Upgrade $http_upgrade;
      proxy_set_header Connection "upgrade";
      proxy_read_timeout 3600s;   # SMF keepalives are minutes apart; default 60s kills idle sessions
      proxy_send_timeout 3600s;
    }
  }
}
```

Record in the README: `proxy_read_timeout` is itself a spike measurement target (design §16 question 5).

- [ ] **Step 3: Write the token issuer (`issuer/issuer.mjs`)**

`npm init -y && npm install jose@5` inside `issuer/`. The issuer simulates the console-gateway's role from `BE-D5`: RS256 keypair **persisted to `keys.json` and reloaded on restart** (the `BE-D5` persistence requirement — a restart must never invalidate live tickets; Task 5 tests exactly that), JWKS served, tokens minted with the claims the design names.

```js
import { createServer } from 'node:http';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { generateKeyPair, exportJWK, importJWK, SignJWT } from 'jose';

const KEYS = new URL('./keys.json', import.meta.url).pathname;
let stored;
if (existsSync(KEYS)) {
  stored = JSON.parse(readFileSync(KEYS, 'utf8'));            // restart: same keys, same kids
} else {
  const { publicKey, privateKey } = await generateKeyPair('RS256', { extractable: true });
  const pub = { ...(await exportJWK(publicKey)), kid: 'spike-1', use: 'sig', alg: 'RS256' };
  const priv = { ...(await exportJWK(privateKey)), kid: 'spike-1', alg: 'RS256' };
  stored = { keys: [pub], privateJwks: [priv], kid: 'spike-1' };
  writeFileSync(KEYS, JSON.stringify(stored));
}
const privateKey = await importJWK(stored.privateJwks.find(k => k.kid === stored.kid), 'RS256');
export const state = { ...stored, privateKey };

createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/jwks.json') {
    res.setHeader('content-type', 'application/json');
    return res.end(JSON.stringify({ keys: state.keys }));
  }
  if (req.method === 'POST' && req.url === '/token') {
    let body = ''; for await (const c of req) body += c;
    const { sub, tenant, scopes = [] } = JSON.parse(body);
    const token = await new SignJWT({ tenant, scopes })
      .setProtectedHeader({ alg: 'RS256', kid: state.kid })
      .setIssuer('sm-spike-issuer').setSubject(sub).setAudience('sm-mesh')
      .setIssuedAt().setExpirationTime('15m').sign(state.privateKey);
    res.setHeader('content-type', 'application/json');
    return res.end(JSON.stringify({ token }));
  }
  res.statusCode = 404; res.end();
}).listen(8090);
console.log('issuer on :8090');
```

- [ ] **Step 4: Verify SEMP resource names against the broker itself (`I2`), then extend `provision.sh`**

Start the broker (`docker compose up -d`, wait for SEMP). Fetch the broker's own served SEMPv2 spec and confirm the exact resource names for: creating a Message VPN, an **OAuth profile** (resource-server role, `jwks` endpoint URI), **ACL profiles** with subscribe-topic exceptions, and **client usernames**:

```bash
curl -s -u admin:admin http://localhost:8080/SEMP/v2/config/spec | python3 -c "
import json,sys; spec=json.load(sys.stdin)
print([p for p in spec['paths'] if 'oauth' in p.lower() or 'aclProfile' in p][:20])"
```

Then script, in `provision.sh`, using the confirmed paths: create VPN `sm-spike`; create the OAuth profile pointing at `http://host.docker.internal:8090/jwks.json` (issuer `sm-spike-issuer`, audience `sm-mesh`, resource-server role); enable OAuth **and basic** auth on the VPN (both schemes coexist per VPN — the design's server-side identities use basic/cert, browsers use OAuth). Provision the two identity classes with opposite publish postures (R6 finding 1):
- **`sm-internal`** — basic-auth client username on ACL profile `acl-internal`: publish **and** subscribe exceptions for `sm/v1/>`, defaults disallow. This is the only identity that may publish; it stands in for `mesh-bridge`/the backend, and Task 10's IT reuses it.
- **Every browser-facing ACL profile created in Tasks 2–4** — publish default **disallow with no publish exceptions**. Browsers subscribe only.

**Do not guess a path — every SEMP call in the script must appear in the fetched spec.** Note the broker version (`curl -s -u admin:admin http://localhost:8080/SEMP/v2/config/about/api`) in the README — this answers design §16 question 1's floor for the features actually used.

- [ ] **Step 5: Write the connect test (`tests/00-connect.test.mjs`)**

Root `package.json`: `npm install vitest@2` plus a file-link to the built SDK: `"solace-webapp-sdk": "file:../../../../RapidPilot/webAppSDK"` (run `npm run build` in the SDK repo first). Test: mint a token, connect through nginx, subscribe, publish, receive.

```js
import { describe, it, expect } from 'vitest';
import SolaceWebAppSDK from 'solace-webapp-sdk';

async function mint(claims) {
  const r = await fetch('http://localhost:8090/token', { method: 'POST', body: JSON.stringify(claims) });
  return (await r.json()).token;
}

const WSS = 'wss://localhost:9443/mesh';   // npm test sets NODE_TLS_REJECT_UNAUTHORIZED=0 for the self-signed spike certs

const browser = async (claims) => SolaceWebAppSDK.connect({
  url: WSS, vpn: 'sm-spike', protocol: 'smf',
  auth: { type: 'oauth', accessToken: await mint(claims) },  // confirm exact option name in the SDK README §Authentication
});
const internal = () => SolaceWebAppSDK.connect({             // stands in for mesh-bridge — the ONLY publisher
  url: WSS, vpn: 'sm-spike', protocol: 'smf',
  auth: { type: 'basic', username: 'sm-internal', password: 'sm-internal' },
});

describe('G1 baseline', () => {
  it('browser subscribes over OAuth+TLS; the INTERNAL identity publishes; message arrives', async () => {
    const sub = await browser({ sub: 'op-1', tenant: 'acme', scopes: ['notify'] });
    const got = new Promise(res => sub.subscribe('sm/v1/acme/notify/>', m => res(m.topic)));
    const pub = await internal();
    await pub.publish('sm/v1/acme/notify/instance/i-1/health', { k: 'GREEN' });
    expect(await got).toBe('sm/v1/acme/notify/instance/i-1/health');
    await Promise.all([sub.disconnect(), pub.disconnect()]);
  }, 30000);

  it('a browser OAuth token can NEVER publish to any sm/v1 topic (design §3: only backend modules publish)', async () => {
    const c = await browser({ sub: 'op-1', tenant: 'acme', scopes: ['notify', 'stream'] });
    await expect(c.publish('sm/v1/acme/notify/instance/i-1/health', { k: 'GREEN' })).rejects.toThrow();
    await expect(c.publish('sm/v1/acme/stream/g1/s1', { v: 1 })).rejects.toThrow();
    await c.disconnect();
  }, 30000);
});
```

- [ ] **Step 6: Run it end-to-end**

Run, in order (a single `a && b & c` line backgrounds the whole `a && b` chain and races the test against provisioning — sequence explicitly):

```bash
./setup-certs.sh                      # certs must exist BEFORE compose up — nginx mounts ./certs
docker compose up -d                  # then wait for SEMP: until curl -sf -u admin:admin http://localhost:8080/SEMP/v2/config/about/api; do sleep 5; done
./provision.sh
node issuer/issuer.mjs &              # background the issuer alone; give it a beat: sleep 1
NODE_TLS_REJECT_UNAUTHORIZED=0 npx vitest run tests/00-connect.test.mjs
```

Put this sequence (minus the compose up) behind `npm test` so later tasks inherit it, including the `NODE_TLS_REJECT_UNAUTHORIZED=0` env — the test file comments assume `npm test` sets it.
Expected: PASS. If the OAuth connect fails, debug the profile against the SEMP spec fetched in Step 4 before touching anything else — this baseline gates Tasks 2–4.

- [ ] **Step 7: Commit**

```bash
git add backend/spikes/mesh-authz
git commit -m "spike(G1): broker + nginx ws proxy + JWT issuer, OAuth connect proven end-to-end"
```

---

### Task 2: Spike candidate (a) — substitution-variable ACL

**Files:**
- Create: `backend/spikes/mesh-authz/tests/01-substitution.test.mjs`
- Modify: `backend/spikes/mesh-authz/provision.sh` (append candidate-a provisioning)

**Interfaces:**
- Consumes: Task 1's issuer, broker, `mint()` helper (copy it into each test file — the spike stays dependency-flat).
- Produces: a PASS/FAIL verdict + notes consumed by Task 5's result document.

- [ ] **Step 1: Provision the candidate**

Append to `provision.sh`: an ACL profile `acl-subst` with default subscribe action `disallow` and a subscribe-topic exception using the substitution variable for client username over the stream namespace — the intent is `sm/v1/acme/stream/${clientUsername}/>` (confirm the exact substitution syntax in the SEMP spec / broker docs fetched in Task 1 Step 4; if the broker's substitution grammar differs, record what it actually is). Create client usernames `grant-111` and `grant-222` on that profile. The OAuth profile must map the token to a client username — confirm in the SEMP spec whether a claim-to-username mapping exists on the OAuth profile; if the mapped username must pre-exist, that IS the finding (per-grant SEMP writes required — the cost `BE-D5` wanted to avoid).

- [ ] **Step 2: Write the test**

```js
it('candidate a: grant-111 can subscribe its own stream branch, not grant-222s', async () => {
  const t = await mint({ sub: 'grant-111', tenant: 'acme', scopes: ['stream'] });
  const c = await SolaceWebAppSDK.connect({ url: 'wss://localhost:9443/mesh', vpn: 'sm-spike',
    protocol: 'smf', auth: { type: 'oauth', accessToken: t } });
  await expect(c.subscribe('sm/v1/acme/stream/grant-111/s1', () => {})).resolves.toBeDefined();
  await expect(c.subscribe('sm/v1/acme/stream/grant-222/s1', () => {})).rejects.toThrow();
  await c.disconnect();
});
```

- [ ] **Step 3: Run, record verdict**

Run: `npx vitest run tests/01-substitution.test.mjs`
Record in `README.md` under "Candidate (a)": PASS/FAIL, whether the claim→username mapping required pre-created usernames (i.e., per-grant SEMP writes), and the exact broker config that made it work.

- [ ] **Step 4: Commit**

```bash
git add backend/spikes/mesh-authz
git commit -m "spike(G1): candidate (a) substitution-variable ACL — verdict recorded"
```

---

### Task 3: Spike candidate (b) — authorization groups from token claims

**Files:**
- Create: `backend/spikes/mesh-authz/tests/02-authzgroup.test.mjs`
- Modify: `backend/spikes/mesh-authz/provision.sh` (append candidate-b provisioning)

**Interfaces:**
- Consumes: Task 1's environment.
- Produces: PASS/FAIL verdict + the per-grant-cost note for Task 5.

- [ ] **Step 1: Provision**

Append: enable the OAuth profile's groups-claim mapping (confirm the claim name field in the SEMP spec — the OAuth profile resource has an authorization-groups-claim setting), create authorization group `g-stream-alpha` bound to a new ACL profile `acl-alpha` whose only subscribe exception is `sm/v1/acme/stream/alpha/>`.

- [ ] **Step 2: Write the test**

```js
it('candidate b: groups claim grants exactly the group ACL', async () => {
  const t = await mint({ sub: 'op-2', tenant: 'acme', scopes: ['stream'], groups: ['g-stream-alpha'] });
  const c = await SolaceWebAppSDK.connect({ url: 'wss://localhost:9443/mesh', vpn: 'sm-spike',
    protocol: 'smf', auth: { type: 'oauth', accessToken: t } });
  await expect(c.subscribe('sm/v1/acme/stream/alpha/s1', () => {})).resolves.toBeDefined();
  await expect(c.subscribe('sm/v1/acme/stream/beta/s1', () => {})).rejects.toThrow();
  await c.disconnect();
});
```

(The issuer already passes arbitrary extra fields? It does not — extend `issuer.mjs`'s `/token` to spread the whole request body into the JWT payload: `new SignJWT({ ...JSON.parse(body) })`, keeping sub/exp handling. Commit that with this task.)

- [ ] **Step 3: Run, record verdict — including the per-grant cost**

Run: `npx vitest run tests/02-authzgroup.test.mjs`
Record: PASS/FAIL, and the analytic note that per-*grant* groups require a SEMP write per chart grant (authorization groups are broker objects) — viable only for coarse, long-lived scopes unless proven otherwise.

- [ ] **Step 4: Commit**

```bash
git add backend/spikes/mesh-authz
git commit -m "spike(G1): candidate (b) authorization groups — verdict recorded"
```

---

### Task 4: Spike candidate (c) — tenant-coarse ACL + unguessable grant-ids, and the I5 deny fixtures

**Files:**
- Create: `backend/spikes/mesh-authz/tests/03-coarse-and-i5.test.mjs`
- Modify: `backend/spikes/mesh-authz/provision.sh` (append candidate-c provisioning)

**Interfaces:**
- Consumes: Task 1's environment.
- Produces: verdict + the four security fixtures design §14 requires, reusable as plan-002 CI fixtures.

- [ ] **Step 1: Provision**

Append: ACL profile `acl-acme` — subscribe exceptions `sm/v1/acme/notify/>` and `sm/v1/acme/stream/>`, default disallow; client username `tenant-acme` on it; a second tenant's profile/username (`acl-globex`, `tenant-globex`) with the `globex` prefixes.

- [ ] **Step 2: Write the tests — candidate (c) plus the design §14 security fixtures**

```js
it('candidate c: tenant-coarse ACL admits any stream in-tenant (the stated exposure)', async () => {
  const t = await mint({ sub: 'tenant-acme', tenant: 'acme', scopes: ['stream'] });
  const c = await connect(t);
  await expect(c.subscribe('sm/v1/acme/stream/any-grant/s1', () => {})).resolves.toBeDefined();
  await c.disconnect();
});
it('I5: tenant acme token cannot subscribe tenant globex topics', async () => {
  const t = await mint({ sub: 'tenant-acme', tenant: 'acme', scopes: ['notify'] });
  const c = await connect(t);
  await expect(c.subscribe('sm/v1/globex/notify/>', () => {})).rejects.toThrow();
  await c.disconnect();
});
it('expired ticket is refused at connect', async () => {
  // extend issuer /token to honor an expiresIn field; mint with expiresIn:'1s', wait 2s
  const t = await mint({ sub: 'tenant-acme', tenant: 'acme', expiresIn: '1s' });
  await new Promise(r => setTimeout(r, 2000));
  await expect(connect(t)).rejects.toThrow();
});
```

(`connect(t)` = the Task 2 connect call extracted to a local helper in this file. The fourth §14 fixture — notify-scope token vs stream topic — is only expressible under candidates (a)/(b); note in the README which candidate makes it enforceable, since under (c) scope separation needs two ACL profiles: provision `acl-acme-notify` with only the notify exception and test it.)

- [ ] **Step 3: Run, record verdict**

Run: `npx vitest run tests/03-coarse-and-i5.test.mjs`
Expected: all PASS. Candidate (c) always "works" — its finding is the *exposure boundary*, stated precisely.

- [ ] **Step 4: Commit**

```bash
git add backend/spikes/mesh-authz
git commit -m "spike(G1): candidate (c) + I5 deny fixtures — verdict and exposure recorded"
```

---

### Task 5: Spike — JWKS rotation, renewal-reconnect, and the written G1 result

**Files:**
- Create: `backend/spikes/mesh-authz/tests/04-rotation-renewal.test.mjs`
- Modify: `backend/spikes/mesh-authz/issuer/issuer.mjs` (add `POST /rotate`)
- Create: `docs/design/backend/spike-authz-result.md`
- Modify: `docs/design/backend/decisions.md` (changelog row linking the result)

**Interfaces:**
- Consumes: Tasks 1–4 verdicts from the spike README.
- Produces: `spike-authz-result.md` — **the artifact `BE-D5` cites at ratification**. Gate G1 closes when it exists and names a chosen mechanism.

- [ ] **Step 1: Add rotation to the issuer**

`POST /rotate`: generate a new keypair with a new `kid`, publish **both** JWKs (overlap window per `BE-D5`), sign subsequent tokens with the new key. A second call drops the old key.

- [ ] **Step 2: Write the rotation + renewal tests**

```js
it('rotation: new-key token accepted only after broker JWKS refresh; old-key token dies with the overlap', async () => {
  await fetch('http://localhost:8090/rotate', { method: 'POST' });
  const fresh = await mint({ sub: 'tenant-acme', tenant: 'acme' });
  // measure: retry connect(fresh) every 5s, record how long until the broker accepts it
  // then rotate again (drops old key) and assert an old-key token is refused
});
it('key PERSISTENCE: issuer restart keeps the key — an old unexpired token still connects', async () => {
  const t = await mint({ sub: 'tenant-acme', tenant: 'acme' });
  // restart the issuer process (kill + relaunch node issuer/issuer.mjs; keys.json reloads the same kid)
  // then assert: JWKS still serves the same kid, and the pre-restart token still connects
  await expect(connect(t)).resolves.toBeDefined();
});
it('renewal is a reconnect: updateAccessToken drops and restores the session', async () => {
  const c = await connect(await mint({ sub: 'tenant-acme', tenant: 'acme' }));
  let disconnects = 0; c.on?.('disconnected', () => disconnects++);
  await c.updateAccessToken(await mint({ sub: 'tenant-acme', tenant: 'acme' }));   // exact method name: SDK README §Authentication & Token Lifecycle
  // assert the subscription still works after renewal, and record whether disconnects > 0
});
```

The rotation test's deliverable is a **measured number** (broker JWKS refresh latency, and whether SEMP exposes a force-refresh) — check the SEMP spec for a JWKS refresh operation and record either the operation or its absence.

- [ ] **Step 3: Write `docs/design/backend/spike-authz-result.md`**

Structure (fill every section from the recorded verdicts — no TBDs):

```markdown
# G1 spike result — mesh subscribe authorization (BE-D5)
**Broker version tested:** <from Task 1> · **Date:** <run date>
## Verdicts
| Candidate | Works? | Per-grant cost | Notes |
| (a) substitution-variable ACL | … | … | exact ACL/OAuth config |
| (b) authorization groups      | … | one SEMP write per group | … |
| (c) tenant-coarse + grant-ids | PASS | none | exposure: any in-tenant principal with stream scope sees any in-tenant stream |
## Chosen mechanism
<one of a/b/c, with the deciding facts>
## JWKS rotation & key persistence
refresh latency measured: …; force-refresh operation: <exists at …/absent>; required overlap window: …;
issuer-restart persistence verified: old unexpired token connected after restart (yes/no + notes)
## Renewal
updateAccessToken behaviour observed: …; chart-seam consequence confirmed: re-backfill on renewal
## Reverse proxy
nginx timeouts required: …; anything SMF-over-ws needed beyond Upgrade headers: …
## Broker version floor
features used and the minimum version carrying them (design §16 question 1)
```

- [ ] **Step 4: Link it from the decisions changelog**

Append a row to the `decisions.md` changelog: `| <date> | **G1 spike result recorded** — [spike-authz-result.md](spike-authz-result.md); BE-D5 cites it at ratification; chosen mechanism: <x>. |`

- [ ] **Step 5: Verify links, run the full spike suite once more, commit**

Run: `npx vitest run` (all four test files) and the repo link check over `docs/design/backend/`.
Expected: suite green; links OK.

```bash
git add backend/spikes/mesh-authz docs/design/backend
git commit -m "spike(G1): rotation + renewal measured; written result closes Gate G1"
```

---

### Task 6: Gate G2 inputs — backend verbs into the D10 amendment paper

**Files:**
- Modify: `docs/design/D10-AMENDMENT-PROPOSAL.md` (append one section)

**Interfaces:**
- Consumes: design §11 ("New authorization surfaces get verbs"), `BE-D5`/`BE-D8`.
- Produces: the amendment paper carries the mesh verbs so the architect group decides **once**. Gate G2 itself is their decision — this task only completes the inputs. **Nothing in this plan waits on G2**; plan 002's auth-bearing tasks do.

- [ ] **Step 1: Append the backend section to the amendment paper**

Add a new section after §2's verb catalog (that catalog is grouped bold-label lines, not a table — the addendum carries its own small table, which is fine for an addendum):

```markdown
## Addendum (backend, 2026-08-27): mesh access verbs — BE-D5/BE-D6

Three verbs the event-mesh console path requires; deny-by-default applies as everywhere:

| Verb | Grants | High-consequence? |
|---|---|---|
| `mesh.ticket.mint` | obtain a mesh ticket (notify-scope) for one's own session | no |
| `mesh.stream.grant` | receive a chart-tail stream grant with a passing DM-D1 query | no |
| `mesh.stream.revoke` | revoke another session's active stream grant | yes — operator-on-operator action |

Bootstrap/identity/audit sections above apply unchanged; ticket minting is audited like any
privileged action once the audit log exists.
```

- [ ] **Step 2: Verify links resolve, commit**

```bash
git add docs/design/D10-AMENDMENT-PROPOSAL.md
git commit -m "G2 input: mesh access verbs (ticket mint, stream grant/revoke) added to the D10 amendment paper"
```

---

### Task 7: `contracts/events/` — the versioned event contract (envelope + catalog + checks)

Gate G3 is closed in-spec; this task makes it executable. This **is** the deliberate reviewed contract change the spec names — it adds a folder, touches nothing existing except `run-checks.sh`.

**Files:**
- Create: `contracts/events/README.md`
- Create: `contracts/events/asyncapi.yaml`
- Create: `contracts/events/envelope.schema.json`
- Create: `contracts/events/notify-events.schema.json`
- Create: `contracts/events/fixtures/health-transition.json`
- Create: `contracts/events/fixtures/config-state.json`
- Create: `contracts/events/fixtures/version-change.json`
- Create: `contracts/events/fixtures/rollout-step.json`
- Create: `contracts/events/fixtures/catalog-package.json`
- Create: `contracts/events/fixtures/quarantine.json`
- Create: `contracts/events/fixtures/expected-topics.json`
- Create: `contracts/events/negative/value-in-notify.json`
- Create: `contracts/events/negative/missing-state-version.json`
- Create: `contracts/events/negative/health-without-placement.json`
- Create: `contracts/events/negative/catalog-with-instance-id.json`
- Create: `contracts/events/negative/bad-topics.json`
- Create: `contracts/events/validate-events.py`
- Modify: `contracts/run-checks.sh` (add check 10)

**Interfaces:**
- Produces: `envelope.schema.json` (`$id: sm/events/v1/envelope`) and `notify-events.schema.json` defining the six v1 events keyed by `entity.kind` + `event` field; `validate-events.py` exit 0 = all fixtures valid AND all negatives rejected. Task 9's Java golden tests consume the **fixtures byte-for-byte**.

- [ ] **Step 1: Write `envelope.schema.json`**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "sm/events/v1/envelope",
  "title": "SM event envelope (design §3, BE-D4)",
  "type": "object",
  "required": ["eventId", "occurredAtUtc", "producer", "tenant", "entity", "stateVersion", "event", "payload"],
  "additionalProperties": false,
  "properties": {
    "eventId": { "type": "string", "format": "uuid" },
    "occurredAtUtc": { "type": "string", "format": "date-time",
      "description": "display/diagnostics ONLY — ordering uses stateVersion (B/D15)" },
    "producer": { "enum": ["cp-core", "dp-core"] },
    "tenant": { "type": "string", "minLength": 1 },
    "entity": {
      "type": "object", "required": ["kind"], "additionalProperties": false,
      "properties": {
        "kind": { "enum": ["instance", "placement", "solution"] },
        "instanceId": { "type": "string" }, "placementId": { "type": "string" },
        "solution": { "type": "string" }
      }
    },
    "stateVersion": { "type": "integer", "minimum": 0,
      "description": "monotonic per entity; config generation where one exists" },
    "event": { "enum": ["instance.health", "instance.config-state", "instance.version",
                         "instance.rollout", "catalog.package", "metrics.quarantine"] },
    "payload": { "type": "object" }
  }
}
```

- [ ] **Step 2: Write `notify-events.schema.json`** — per-event payload shapes, referencing the envelope, enforcing identifier-only payloads via `additionalProperties: false` and closed enums:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "sm/events/v1/notify-events",
  "allOf": [{ "$ref": "sm/events/v1/envelope" }],
  "oneOf": [
    { "properties": { "event": { "const": "instance.health" },
        "entity": { "required": ["kind","instanceId","placementId"], "properties": { "kind": { "const": "placement" } } },
        "payload": {
        "type": "object", "required": ["state"], "additionalProperties": false,
        "properties": { "state": { "enum": ["GREEN","RED","RESTART_EXPECTED","INACTIVE_REPLICA",
          "NOT_APPLICABLE","unreachable","not_exposed","missing","bootstrap"] } } } } },
    { "properties": { "event": { "const": "instance.config-state" },
        "entity": { "required": ["kind","instanceId"], "properties": { "kind": { "const": "instance" } },
          "not": { "required": ["placementId"] } },
        "payload": {
        "type": "object", "required": ["reached"], "additionalProperties": false,
        "properties": { "reached": { "enum": ["desired","rendered","persisted",
          "restart-observed","effective-verified"] } } } } },
    { "properties": { "event": { "const": "instance.version" },
        "entity": { "required": ["kind","instanceId"], "properties": { "kind": { "const": "instance" } },
          "not": { "required": ["placementId"] } },
        "payload": {
        "type": "object", "required": ["state"], "additionalProperties": false,
        "properties": { "state": { "enum": ["OK","VERSION_DRIFT","VERSION_UNKNOWN","VERSION_UNVERIFIED"] } } } } },
    { "properties": { "event": { "const": "instance.rollout" },
        "entity": { "required": ["kind","instanceId","placementId"], "properties": { "kind": { "const": "placement" } } },
        "payload": {
        "type": "object", "required": ["applyId","replicaId","step"], "additionalProperties": false,
        "properties": { "applyId": {"type":"string"}, "replicaId": {"type":"string"},
          "step": { "enum": ["staged","restart-requested","restart-observed"] } } } } },
    { "properties": { "event": { "const": "catalog.package" },
        "entity": { "required": ["kind","solution"], "properties": { "kind": { "const": "solution" } },
          "not": { "anyOf": [ { "required": ["instanceId"] }, { "required": ["placementId"] } ] } },
        "payload": {
        "type": "object", "required": ["solution","packageVersion"], "additionalProperties": false,
        "properties": { "solution": {"type":"string"}, "packageVersion": {} } } } },
    { "properties": { "event": { "const": "metrics.quarantine" },
        "entity": { "required": ["kind","instanceId"], "properties": { "kind": { "const": "instance" } } },
        "payload": {
        "type": "object", "required": ["count"], "additionalProperties": false,
        "properties": { "count": { "type": "integer", "minimum": 1 } } } } }
  ]
}
```

The **entity constraints are the R5 identity fix made executable** (design §3 identity scoping): health and rollout are placement-scoped (require `placementId`), config-state/version are instance-scoped invalidations (forbid `placementId`), catalog is solution-scoped (forbids instance identity). The health enum carries both the rollup keys and the four NOTFOUND sub-states, exactly as design §3; the version enum is `types.ts`'s `VersionState`. **Copy these enums from the sources, do not retype from memory** — `contracts/view-models/src/types.ts:105` and `:116`, and `STATE-VOCABULARY.md`.

- [ ] **Step 3: Write the six fixtures and three negatives**

One fixture per event, e.g. `fixtures/health-transition.json`:

```json
{
  "eventId": "3f1c9a52-0000-4000-8000-000000000001",
  "occurredAtUtc": "2026-08-27T10:00:00Z",
  "producer": "dp-core",
  "tenant": "acme",
  "entity": { "kind": "placement", "instanceId": "cache-prod-01", "placementId": "pl-eu-1" },
  "stateVersion": 41,
  "event": "instance.health",
  "payload": { "state": "RED" }
}
```

(config-state fixture: `entity.kind: "instance"`, `stateVersion` = the generation, `payload.reached: "restart-observed"`. version: `payload.state: "VERSION_DRIFT"`. rollout: placement entity + all three payload fields. catalog-package: `producer: "cp-core"`, `entity.kind: "solution"`. quarantine: `payload.count: 12`.)

Also write `fixtures/expected-topics.json` — the topic manifest, one entry per fixture (R6 finding 4: topic derivation must be tested against a declared expectation, not an embedded field the schema would reject as an unknown property):

```json
{
  "health-transition.json":  "sm/v1/acme/notify/instance/cache-prod-01/health",
  "config-state.json":       "sm/v1/acme/notify/instance/cache-prod-01/config-state",
  "version-change.json":     "sm/v1/acme/notify/instance/cache-prod-01/version",
  "rollout-step.json":       "sm/v1/acme/notify/instance/cache-prod-01/rollout/apply-7",
  "catalog-package.json":    "sm/v1/acme/notify/catalog/package",
  "quarantine.json":         "sm/v1/acme/notify/instance/cache-prod-01/quarantine"
}
```

Negatives: `value-in-notify.json` = the health fixture plus `"payload": { "state": "RED", "cpuPercent": 93 }` (must be **rejected** — the `I6` identifier-only rule as an executable check); `missing-state-version.json` = health fixture without `stateVersion`; `health-without-placement.json` = health fixture with `entity: { "kind": "instance", "instanceId": "cache-prod-01" }` (rejected — undoes the R5 placement-scoping fix); `catalog-with-instance-id.json` = catalog fixture whose entity also carries `"instanceId"` (rejected); `bad-topics.json` = a manifest of wrong expectations that the deriver must NOT produce and the prefix rule must refuse:

```json
{
  "health-transition.json": "sm/v2/acme/notify/instance/cache-prod-01/health"
}
```

- [ ] **Step 4: Write `contracts/events/asyncapi.yaml`** — the document the spec names (design §4), thin and referencing the JSON Schemas rather than duplicating them:

```yaml
asyncapi: 3.0.0
info: { title: SM mesh events, version: 1.0.0, description: "Topics per design §4; payloads per the JSON Schemas in this folder. v1 in the topic is load-bearing (BE-D4)." }
channels:
  instanceNotify:
    address: sm/v1/{tenant}/notify/instance/{instanceId}/{aspect}
    parameters: { tenant: {}, instanceId: {}, aspect: { enum: [health, config-state, version] } }
    messages: { notify: { payload: { $ref: ./notify-events.schema.json } } }
  rolloutNotify:
    address: sm/v1/{tenant}/notify/instance/{instanceId}/rollout/{applyId}
    parameters: { tenant: {}, instanceId: {}, applyId: {} }
    messages: { notify: { payload: { $ref: ./notify-events.schema.json } } }
  catalogNotify:
    address: sm/v1/{tenant}/notify/catalog/package
    parameters: { tenant: {} }
    messages: { notify: { payload: { $ref: ./notify-events.schema.json } } }
  stream:
    address: sm/v1/{tenant}/stream/{grantId}/{seriesKey}
    parameters: { tenant: {}, grantId: {}, seriesKey: {} }
    messages: { point: { payload: { type: object, description: "normalized point + series identity; schema lands with BE-D6 phase 2" } } }
```

`validate-events.py` (next step) also asserts this file parses as YAML and that every `address` starts with `sm/v1/` — keeping the document honest without needing an AsyncAPI toolchain in CI.

- [ ] **Step 5: Write `validate-events.py`**

Mirror `contracts/validation/validate-package.py`'s style (stdlib-only if that script is stdlib-only — read it first and match). Behaviour, four checks:
1. every `fixtures/*.json` **except `expected-topics.json`** validates against both schemas (must pass — `expected-topics.json` is the topic manifest, not an event; without the exemption check 1 rejects it and the suite is permanently red);
2. every `negative/*.json` **fails** schema validation — and the script fails if a negative *passes* (`bad-topics.json` is exempt from this check — it is a topic manifest, handled in 4);
3. **topic derivation**: implement design §4's rules in the script (`sm/v1/{tenant}/notify/instance/{instanceId}/{health|config-state|version|quarantine}`, `…/rollout/{applyId}`, `sm/v1/{tenant}/notify/catalog/package`), derive each fixture's topic from its body, and assert it equals the `expected-topics.json` entry **and** starts with `sm/v1/` — every fixture must have a manifest entry and vice versa;
4. for each `bad-topics.json` entry, assert the derived topic does **not** equal it (proving the deriver can't be fooled into a `sm/v2` address) — a false-green here is exactly what R6 finding 4 caught.
Also parse `asyncapi.yaml` and assert every `address` starts with `sm/v1/`. Exit non-zero on any miss, printing `gate10 <file>: <reason>` lines.

- [ ] **Step 6: Wire into `run-checks.sh`**

Read `contracts/run-checks.sh` first and copy the existing check-block pattern exactly (numbered header, PASS/FAIL echo, exit-code accumulation — the skip-must-not-pass discipline from commit `a4c1799`). Add:

```bash
echo "== 10. Event contract: fixtures valid, negatives rejected"
python3 events/validate-events.py || fail "event contract"
```

- [ ] **Step 7: Run the whole contract suite**

Run: `./contracts/run-checks.sh --strict`
Expected: ALL CHECKS PASSED including check 10. Then deliberately break a fixture (`"stateVersion": "41"` as string), rerun, expect check 10 FAIL, restore it — proving the check fires.

- [ ] **Step 8: Commit**

```bash
git add contracts/events contracts/run-checks.sh
git commit -m "contracts: v1 event envelope + notify catalog, fixtures, negatives, check 10 (Gate G3 executable)"
```

---

### Task 8: Java skeleton — Maven modules with I7 enforced by tested ArchUnit rules

**Files:**
- Create: `backend/pom.xml` (parent)
- Create: `backend/cp-api/pom.xml`, `backend/cp-api/src/main/java/com/solace/sm/cp/api/CpMarker.java`
- Create: `backend/dp-api/pom.xml`, `backend/dp-api/src/main/java/com/solace/sm/dp/api/DpMarker.java`
- Create: `backend/cp-core/pom.xml`, `backend/cp-core/src/main/java/com/solace/sm/cp/CpCoreMarker.java`
- Create: `backend/dp-core/pom.xml`, `backend/dp-core/src/main/java/com/solace/sm/dp/DpCoreMarker.java`
- Create: `backend/console-gateway/pom.xml`, `backend/console-gateway/src/main/java/com/solace/sm/gateway/GatewayMarker.java`
- Create: `backend/mesh-events/pom.xml` (Task 9 fills it)
- Create: `backend/arch-tests/pom.xml`, `backend/arch-tests/src/test/java/com/solace/sm/arch/PlaneBoundaryTest.java`
- Modify: `backend/README.md` (status line: skeleton exists, no features)

**Interfaces:**
- Produces: `mvn -f backend/pom.xml verify` green; module coordinates `com.solace.sm:{cp-api,dp-api,cp-core,dp-core,console-gateway,mesh-events,arch-tests}:0.1.0-SNAPSHOT`; package roots `com.solace.sm.cp.api`, `com.solace.sm.dp.api`, `com.solace.sm.cp`, `com.solace.sm.dp`, `com.solace.sm.gateway`, `com.solace.sm.events`. Tasks 9–10 add code under these roots.

- [ ] **Step 1: Parent POM**

Resolve the current Spring Boot 3.x version: `curl -s "https://search.maven.org/solrsearch/select?q=g:org.springframework.boot+AND+a:spring-boot-starter-parent&rows=1&wt=json" | python3 -c "import json,sys; print(json.load(sys.stdin)['response']['docs'][0]['latestVersion'])"` — pin what it returns (do not type a version from memory, `I2`). Parent: `spring-boot-starter-parent`, `<java.version>17</java.version>`, modules listed, `arch-tests` last.

> ⚠️ **Correction (found in execution, Task 8): `search.maven.org/solrsearch` serves stale index data** — it returned `3.5.3` as `latestVersion` when `3.5.16` was current. Resolve from `https://repo1.maven.org/maven2/<group-path>/<artifact>/maven-metadata.xml` instead (e.g. `.../org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml`) and read `<release>`/the last `<version>`. The command above is left in place because it is what the plan said; **do not use it.** Same `I2` failure class the step was written to prevent — a source can be authoritative-looking and still wrong.

- [ ] **Step 2: Module POMs + markers**

Dependency edges, exactly (this is `I7` in Maven form):
- `cp-core` → `cp-api`; `dp-core` → `dp-api`, `mesh-events`; `console-gateway` → `cp-api`, `dp-api` **only**; `arch-tests` (test scope) → all of them + `com.tngtech.archunit:archunit-junit5` (resolve current version the same Maven-central way).
- Each marker class is an empty `public final class` with a javadoc line naming the module's design section.
- `cp-core`, `dp-core`, `console-gateway` get `spring-boot-starter` (no web, no data — nothing speculative; YAGNI: starters are added by the plan-002 task that needs them).

- [ ] **Step 3: Write the failing ArchUnit test**

```java
@AnalyzeClasses(packages = "com.solace.sm")
class PlaneBoundaryTest {
  @ArchTest static final ArchRule cpNeverImportsDp =
    noClasses().that().resideInAPackage("com.solace.sm.cp..")
      .should().dependOnClassesThat().resideInAPackage("com.solace.sm.dp..");
  @ArchTest static final ArchRule dpNeverImportsCp =
    noClasses().that().resideInAPackage("com.solace.sm.dp..")
      .should().dependOnClassesThat().resideInAPackage("com.solace.sm.cp..");
  @ArchTest static final ArchRule gatewayUsesApisOnly =
    noClasses().that().resideInAPackage("com.solace.sm.gateway..")
      .should().dependOnClassesThat(resideInAPackage("com.solace.sm.cp..")
        .and(not(resideInAPackage("com.solace.sm.cp.api..")))
        .or(resideInAPackage("com.solace.sm.dp..").and(not(resideInAPackage("com.solace.sm.dp.api..")))));
}
```

Plus the planted-violation proof (the repo's guard discipline — a rule that cannot fire is decoration):

```java
@Test void ruleCatchesAPlantedViolation() {
  JavaClasses withViolation = new ClassFileImporter()
      .importPackages("com.solace.sm");   // includes the archfixture subpackage
  assertThrows(AssertionError.class, () -> cpNeverImportsDp.check(withViolation));
}
```

with `src/test/java/com/solace/sm/cp/archfixture/BadCpClass.java` — a test-scope class in package `com.solace.sm.cp.archfixture` (directory must match the package) referencing `DpCoreMarker`. (Adjust the package filter so the fixture is excluded from the real `@ArchTest` run — import-option excluding `..archfixture..` on the analyzed classes.)

- [ ] **Step 4: Run to verify the planted violation fires and the real rules pass**

Run: `mvn -f backend/pom.xml verify`
Expected: green — including `ruleCatchesAPlantedViolation` proving the rule bites.

- [ ] **Step 5: Update `backend/README.md` status line and commit**

Change `**Status:** no code yet.` to `**Status:** module skeleton + I7 ArchUnit enforcement (plan 001); no features.`

```bash
git add backend docs
git commit -m "backend: Maven skeleton — cp/dp api+core, gateway, mesh-events; I7 enforced by tested ArchUnit rules"
```

---

### Task 9: `mesh-events` — envelope model, golden-file tests against the contract fixtures

The Java twin of the contract. Same pattern as `UI-D15`: one fixture suite, two implementations, divergence fails a build.

**Files:**
- Create: `backend/mesh-events/src/main/java/com/solace/sm/events/EventEnvelope.java`
- Create: `backend/mesh-events/src/main/java/com/solace/sm/events/EntityRef.java`
- Create: `backend/mesh-events/src/main/java/com/solace/sm/events/NotifyTopics.java`
- Create: `backend/mesh-events/src/main/java/com/solace/sm/events/EventJson.java`
- Create: `backend/mesh-events/src/test/java/com/solace/sm/events/EnvelopeGoldenTest.java`
- Create: `backend/mesh-events/src/test/java/com/solace/sm/events/NotifyTopicsTest.java`
- Modify: `backend/mesh-events/pom.xml` (jackson-databind via Boot BOM, junit)

**Interfaces:**
- Consumes: `contracts/events/fixtures/*.json` (path from module: `../../contracts/events/fixtures`).
- Produces: `record EventEnvelope(UUID eventId, Instant occurredAtUtc, String producer, String tenant, EntityRef entity, long stateVersion, String event, Map<String,Object> payload)`; `record EntityRef(String kind, String instanceId, String placementId, String solution)`; `NotifyTopics.topicFor(EventEnvelope e) → String`; `EventJson.mapper() → ObjectMapper` and `EventJson.write(EventEnvelope) → String` (the single serialization configuration). Task 10 publishes with exactly these.

- [ ] **Step 1: Write the failing golden test**

```java
class EnvelopeGoldenTest {
  static final ObjectMapper M = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  @ParameterizedTest @MethodSource("fixtureFiles")   // every file in ../../contracts/events/fixtures
  void roundTripsEveryContractFixtureByteEquivalently(Path fixture) throws Exception {
    JsonNode expected = M.readTree(fixture.toFile());
    EventEnvelope env = M.treeToValue(expected, EventEnvelope.class);
    JsonNode actual = M.valueToTree(env);
    assertEquals(expected, actual);   // tree-equality: field set and values identical
  }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -f backend/pom.xml -pl mesh-events -am test` → FAIL (no `EventEnvelope`).

- [ ] **Step 3: Implement the records**

Plain records with Jackson annotations only where a name differs (none should — keep JSON names identical to the schema). Move the golden test's mapper configuration into `EventJson`: `public static ObjectMapper mapper()` (JavaTimeModule, ISO dates, NON_NULL) and `public static String write(EventEnvelope e)`; the golden test uses `EventJson.mapper()` so test and production serialization are one configuration. `EntityRef` fields nullable except `kind`; envelope constructor rejects null/blank `tenant`, negative `stateVersion` (`IllegalArgumentException` — the same constraints the schema enforces, so a Java-constructed event can't be invalid).

- [ ] **Step 4: Write + implement `NotifyTopics` with its test**

```java
class NotifyTopicsTest {
  @Test void healthTopicMatchesDesignSection4() {
    var env = /* build from the health fixture */;
    assertEquals("sm/v1/acme/notify/instance/cache-prod-01/health", NotifyTopics.topicFor(env));
  }
  @Test void catalogTopicHasNoInstanceSegment() { /* expect sm/v1/acme/notify/catalog/package */ }
  @Test void rolloutTopicCarriesApplyId() { /* sm/v1/acme/notify/instance/<id>/rollout/<applyId> */ }
}
```

Implementation: a switch on `event` producing exactly design §4's shapes. Reject unknown `event` values with `IllegalArgumentException`.

- [ ] **Step 5: Run all module tests** — `mvn -f backend/pom.xml -pl mesh-events -am test` → PASS, all six fixtures round-tripping.

- [ ] **Step 6: Commit**

```bash
git add backend/mesh-events
git commit -m "mesh-events: envelope records + topic derivation, golden-tested against contracts/events fixtures"
```

---

### Task 10: `mesh-bridge` publisher seam — `EventPublisher` + JCSMP implementation, compose-gated integration test

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/EventPublisher.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/JcsmpEventPublisher.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/MeshProperties.java`
- Create: `backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/JcsmpEventPublisherIT.java`
- Create: `backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/EventPublisherContractTest.java`
- Modify: `backend/dp-core/pom.xml` (add `com.solacesystems:sol-jcsmp` — resolve current version from Maven central as in Task 8, never from memory. ⚠️ **Correction (found in execution, Task 8):** `search.maven.org/solrsearch` serves stale index data (returned 3.5.3 when 3.5.16 was current) — resolve from `https://repo1.maven.org/maven2/<group-path>/<artifact>/maven-metadata.xml` instead)
- Modify: `backend/spikes/mesh-authz/provision.sh` **only if** Task 1 did not already create `sm-internal` with publish rights on `sm/v1/>` — the IT uses that identity; never create broker users outside the committed provisioning script (R6 finding 5)

**Interfaces:**
- Consumes: Task 9's `EventEnvelope`, `NotifyTopics`.
- Produces: `interface EventPublisher { void publish(EventEnvelope event); }` — **the one seam every module publishes through** (design §3: publish sites confined so the outbox upgrade stays bounded). `JcsmpEventPublisher implements EventPublisher`, direct (non-persistent) delivery, configured by `MeshProperties(String host, String vpn, String clientUsername, String password)` bound from `sm.mesh.*`. Plan 002 wires real state changes into this interface.

- [ ] **Step 1: Write the unit-level contract test (no broker)**

```java
class EventPublisherContractTest {
  @Test void serializesViaMeshEventsAndDerivesTopicFromNotifyTopics() {
    var sent = new AtomicReference<Map.Entry<String,String>>();  // topic → json
    EventPublisher p = new JcsmpEventPublisher((topic, json) ->  // transport seam receives the DERIVED topic+json
        sent.set(Map.entry(topic, json)));                       // — the publisher must do the deriving, or this fails
    EventEnvelope health = EventJson.mapper().readValue(
        Files.readString(Path.of("../../contracts/events/fixtures/health-transition.json")),
        EventEnvelope.class);
    p.publish(health);
    assertEquals("sm/v1/acme/notify/instance/cache-prod-01/health", sent.get().getKey());
    assertTrue(sent.get().getValue().contains("\"stateVersion\":41"));
  }
}
```

Structure `JcsmpEventPublisher` with a package-private constructor taking a transport `BiConsumer<String,String>` (topic, serialized body — the unit seam) and a public constructor building the real JCSMP transport from `MeshProperties`. The seam sits **after** topic derivation and serialization: `publish()` itself calls `NotifyTopics.topicFor` and `EventJson.write` and hands the results to the transport. (A seam taking the raw `EventEnvelope` would be a false-green — the test's own lambda would do the deriving and pass no matter what the publisher did.) Unit test needs no broker; integration test needs no mocks.

- [ ] **Step 2: Run to verify it fails**, then implement: serialization via `EventJson.write` (Task 9's single mapper configuration — never a second `ObjectMapper`), topic from `NotifyTopics`, JCSMP `XMLMessageProducer` sending a `TextMessage` with `DeliveryMode.DIRECT`. A publish failure logs and drops — **never throws into the caller's transaction path** (`BE-D1`: broker down degrades freshness, never correctness). Test that: transport function that throws → `publish()` returns normally.

- [ ] **Step 3: Run unit tests** — `mvn -f backend/pom.xml -pl dp-core -am test` → PASS.

- [ ] **Step 4: Write the compose-gated integration test**

`JcsmpEventPublisherIT` — JUnit tag `broker`, skipped unless `-Dbroker.host` is set. Against the Task 1 spike broker (use the SMF port the spike compose actually maps; basic auth as the `provision.sh`-created **`sm-internal`** identity — the publisher is server-side and uses basic/client-cert, never the browser OAuth path, and `sm-internal` is deliberately the only identity with publish rights): publish the health fixture, consume it with a JCSMP consumer subscribed to `sm/v1/acme/notify/>` (same identity — its ACL allows both), assert topic and byte-identical body.

Run: `docker compose -f backend/spikes/mesh-authz/docker-compose.yml up -d && mvn -pl dp-core -am verify -Dbroker.host=localhost`
Expected: IT green. Without `-Dbroker.host`: skipped, build still green.

- [ ] **Step 5: Full build + contracts check**

Run: `mvn -f backend/pom.xml verify && ./contracts/run-checks.sh --strict`
Expected: everything green — ArchUnit still passes with the new packages (`meshbridge` lives inside `com.solace.sm.dp..`, so `I7` rules cover it automatically).

- [ ] **Step 6: Commit**

```bash
git add backend/dp-core backend/mesh-events
git commit -m "mesh-bridge: EventPublisher seam + JCSMP direct publisher, unit-seamed and compose-gated IT"
```

---

### Task 11: Plan review section, verification sweep, and handoff state

**Files:**
- Modify: `docs/plans/backend/001-mesh-foundations-and-gates.md` (append Results section)
- Modify: `docs/design/backend/design.md` (§16 answers that the spike settled: questions 1, 3, 5)

**Interfaces:**
- Consumes: everything above.
- Produces: the recorded gate state plan 002 starts from.

- [ ] **Step 1: Verification sweep**

Run, and record outputs in the Results section:
- `./contracts/run-checks.sh --strict` → ALL PASSED
- `mvn -f backend/pom.xml verify` → green incl. planted-violation proof
- spike suite `npx vitest run` in `backend/spikes/mesh-authz` → green
- repo link check over `docs/` changed files → OK

- [ ] **Step 2: Fold spike answers back into the spec's open questions**

Design §16: mark question 1 (broker version floor), question 3 (TTL vs measured rotation/renewal behaviour) and question 5 (proxy findings) as **answered by [`spike-authz-result.md`](../../design/backend/spike-authz-result.md)** with one-line answers inline — do not delete the rows; append the answer to each.

- [ ] **Step 3: Append a Results section to this plan**

Gate state: G1 CLOSED (result doc link, chosen mechanism), G2 inputs delivered / decision pending with the architect group, G3 executable (check 10). What plan 002 needs: G2 outcome; then ticket endpoint + local auth + notify emission from real state + gateway `/mesh` proxy, per design §13's walking-skeleton row.

- [ ] **Step 4: Commit**

```bash
git add docs/plans/backend docs/design/backend
git commit -m "plan 001: results recorded — G1 closed, G2 pending with architect group, G3 executable"
```

---

## Results

**Plan 001 is complete.** Tasks 1–10 executed on branch `backend-design`; Task 11 is this section. The plan's
checkboxes were deliberately never used as the tracking mechanism (per Global Constraints, status lives in the
GitHub Project) — **this section is the completion record**, not the boxes.

### Verification sweep (Task 11, run on branch `backend-design`, 2026-08-28)

> **Historical — the numbers below are as of Task 11 and are superseded by the R7 and R8 notes that follow.**
> They are the record of *that* sweep and are deliberately not rewritten as the tree changes; read them as
> dated evidence, never as the current test counts.

| Command | Result |
|---|---|
| `./contracts/run-checks.sh --strict` | `== RESULT: ALL CHECKS PASSED` — exit 0, all 10 checks, including check 10: *6 fixtures validate against both schemas · 4 negatives each rejected at the constraint under test · level-safety guard rejects all 9 planted separator/wildcard values · 6 topics derive exactly as declared · deriver produces none of the 2 forbidden topics · asyncapi.yaml parses, all 4 channel addresses `sm/v1/`* |
| `mvn -f backend/pom.xml verify` | `BUILD SUCCESS` — 8 modules, **54 tests, 0 failures, 0 errors**: `mesh-events` 39 (`NotifyTopicsTest` 24 + `EnvelopeGoldenTest` 15), `dp-core` 4 (`EventPublisherContractTest`), `arch-tests` 11 (`PlaneBoundaryTest`, `I7` rules + their planted-violation proofs). Plus the compose-gated IT **visibly skipped, not silently passed**: `JcsmpEventPublisherIT` → `Tests run: 1 … Skipped: 1` |
| `cd backend/spikes/mesh-authz && npm test` | `Test Files 5 passed (5)` · `Tests 30 passed (30)` · 111.16 s (against the already-running stack; provisioning re-runs idempotently) |
| Link check — every relative markdown link in `docs/design/backend/` + `docs/plans/backend/` | 4 files, 34 relative links (final state, this section included), **0 genuinely broken**. A path-relative checker also flags the bare `spike-authz-result.md` link inside Task 5 Step 4, which is a false positive: that line is *template text* for a row to be pasted into `docs/design/backend/decisions.md`, where the link resolves (changelog row, 2026-08-27) |

**The table above is the record of the Task 11 sweep and is deliberately not restated as the tree changes.** The
post-execution **R7** review fix wave (2026-08-28, triaged in [`../../tasks/codex-review-triage.md`](../../tasks/codex-review-triage.md))
added `NotifyEventShapes` + its suite and a publisher drop counter, taking the reactor to **106 tests** (`mesh-events`
90, `dp-core` 5, `arch-tests` 11 — the IT still visibly skipped) with `contracts/run-checks.sh` gaining a step-0
Python-dependency preflight. See the landmine list below for what R7 closed.

### Gate state

**G1 — CLOSED.** [`docs/design/backend/spike-authz-result.md`](../../design/backend/spike-authz-result.md), measured
against `soltr_10.25.0.177` / SEMP 2.48. Chosen mechanism: **candidate (a), the substitution-variable ACL** —
`sm/v1/<tenant>/stream/$client-username/>` on a profile bound to the VPN's `default` client username,
**zero broker writes per grant, measured single-tenant** in the composition the recommendation actually uses
(`default` → `acl-subst` fallthrough), not merely in isolation. Candidates (a) and (b) proved **mutually exclusive,
not layered** — a pre-created client-username object beats a valid `groups` claim.

**G1 leaves two priced gaps, and both block `BE-D5` ratification** — routed to the architect group, not to plan 002:

1. **Multi-tenant composition.** Only the *username* level substitutes; the tenant level is a topic literal, and a
   VPN has exactly one `default` client username. Three routes priced; **none picked, none measured.** The
   zero-writes result is single-tenant until one is chosen.
2. **Notify-tier exception shape.** Design §4's notify topics carry no per-subject level to substitute at, so a
   substitution-scoped notify exception is not expressible. Three options priced, first recommended.

Also carried out of G1 and already folded into design §16 (questions 1, 3, 5): **rotation is not revocation**
(ticket TTL is the only lever; keep a retired key published ≥ TTL + the 60 s JWKS refresh floor, ~16 min at a
15-minute ticket); the renewal deadline is hard (`disconnectOnTokenExpirationEnabled` defaults `true`), so the
gateway re-tickets proactively; the reverse proxy needs nothing beyond `Upgrade` headers and a ~10 s timeout floor
(keepalives measured at 3.0 s, correcting Task 1's premise); broker floor is **Enterprise edition**, SEMP 2.32
quoted for candidate (b), verified at 2.48. Cold-start reproducibility is proven: **30/30 green from `down -v`**
with broker volume, TLS certs and issuer key deleted first.

**G2 — inputs delivered, decision pending.** The three mesh-access verbs — `mesh.ticket.mint`, `mesh.stream.grant`
and `mesh.stream.revoke` (the last flagged high-consequence, operator-on-operator) — are in the backend addendum of [`D10-AMENDMENT-PROPOSAL.md`](../../design/D10-AMENDMENT-PROPOSAL.md).
The decision is the architect group's (**open item 24**). Nothing in this plan waited on it, and nothing in this
plan authorizes building login, tickets or RBAC checks.

**G3 — closed in-spec and now executable.** `contracts/events/` (envelope + catalog + AsyncAPI + 6 fixtures +
negatives + topic deriver) is enforced by **check 10** of `contracts/run-checks.sh`, with the level-safety guard
running against both derivers — closed for the envelope; the catalog carries four schema-ahead-of-design items
([Known gaps](../../../contracts/events/README.md)) that land with the `BE-D4` amendment; generated client/server
types must not be consumed as settled until it does.

### What plan 002 needs before it starts

**Blocking inputs, all external to this repo's execution:**

1. **G2 outcome** — the `D10` amendment. Every auth-bearing task waits on it.
2. **The two G1 gaps decided** — multi-tenant composition and the notify-tier exception shape. `BE-D5` cannot
   ratify without them, and the ACL provisioning in the install runbook cannot be written without them.

**Then, per design §13's walking-skeleton row:** ticket endpoint · local auth (`BE-D8` v1) · notify emission from
real state changes · the gateway `/mesh` proxy. **That row names four further deliverables the list above omits, and
they are not optional extras:** the **SDK integration behind `shared/mesh`** (the browser half of the wss path —
`BE-D7`'s seam), the **polling fallback + degraded indicator** (design §9 — the console must degrade,
never lie, when the mesh is down), the **AsyncAPI and OpenAPI contracts** promoted from `contracts/events/`'s
scaffold to the full generated-types-on-both-sides shape design §14 requires, and the **install-time VPN / OAuth /
ACL provisioning runbook** — which is where the two G1 gaps physically land, so it cannot be written before they are
decided.

**Landmines recorded by the task reviews — read these before plan 002 estimates anything:**

- **reactor-netty 1.2.x is built against netty 4.1**, and this reactor pins netty **4.2.13** at the root.
  Whoever adds WebFlux must verify that combination or scope the pin down; do not assume it composes.
- **`JcsmpEventPublisher` connects eagerly and throws** on an unreachable broker. Lazy-connect /
  reconnecting-transport is an explicit plan-002 decision, not an implementation detail to improvise.
- **`MeshProperties` `sm.mesh.*` binding is untested.** No Spring context test exercises it yet.
- **No drop counter or metric** behind `EventPublisher`'s log-and-drop contract — a silent failure mode today.
  **Partly closed by R7 (F3):** `JcsmpEventPublisher.droppedCount()` now counts every swallow path — and **R8 made
  that claim checkable**: two of the three increments were reachable only from the compose-gated IT, so a mutation
  run deleted them with the build green. Both inner classes now have package-private seams and unit tests, and the
  deletion of either increment is red. **Plan 002 must still expose the counter through the metrics stack**, which
  is the half owed.
- ~~**Payload-shape conformance in Java is proven for the golden case only**~~ — **closed by R7 (F1), and the
  claim only became true in R8.** `NotifyEventShapes.validate()` mirrors all six schema branches (entity pairing,
  required/forbidden ids, closed payload key sets, closed enums) and runs on the publisher's loud/propagate side,
  golden-tested against the same `contracts/events/` fixtures and negatives the Python validator uses. **R7's
  "mirrors all six branches" was true at branch level and false at wire level**: an independent skeptical review
  measured two documents that passed the Java pipeline and the Python validator rejected, and **R8 closed both**
  — (i) a **null-valued required payload key** validated (`containsKey` is not presence) and was then *deleted* by
  `EventJson`'s `NON_NULL` **content** inclusion, emitting a document missing a required property; (ii) the
  envelope's **`producer` enum** (`[cp-core, dp-core]`) was enforced nowhere in Java, so `producer: "go-agent"`
  published. Both now fail closed, both are pinned by tests that look at the **emitted bytes** rather than at
  either component's opinion, and Known gap 4 records the one asymmetry the fix creates deliberately. R8 brings
  the tree to **126 tests** (`mvn verify`: mesh-events 101, dp-core 14, arch-tests 11) — the sweep table above
  predates this.
  **Residual:** with shape defects loud and transport failures counted, the only remaining
  silent-drop-with-committed-state case is a **shape-valid but non-serializable** payload — reachable solely
  through `catalog.package`'s deliberately untyped `packageVersion`
  ([Known gap 4](../../../contracts/events/README.md)) — and the transactional-outbox upgrade the design already
  records behind the `EventPublisher` seam is where plan 002+ closes it.
- **`backend/src/` holds two pre-existing design-era SQL files** — `src/main/resources/db/migration/V001__telemetry_schema.sql`
  and `src/test/sql/V001_constraints_test.sql` — sitting at the reactor **root**, which declares only modules and no
  sources of its own. Nothing builds them, nothing runs them, and `mvn verify` is green while ignoring them entirely.
  Plan 002 must either relocate them into the module that will own the schema (`dp-core`) and wire a migration tool,
  or delete them — an unowned migration file that looks authoritative is worse than no file.

### Review history

Every one of Tasks 1–10 passed independent adversarial review before being accepted. Fix rounds required per task:
T1 1 · T2 1 · T3 1 · T4 1 · **T5 2** · **T6 0** · T7 1 · T8 1 · T9 1 · T10 1. Deferred minors and the full
per-finding rulings are not reproduced here — they live in the session ledger and in the final whole-branch review.

### Pinned versions (for plan 002's baseline)

Spring Boot 3.5.16 · ArchUnit 1.5.0 · JCSMP (`sol-jcsmp`) 10.30.1 · netty 4.2.13, pinned at the reactor root.
