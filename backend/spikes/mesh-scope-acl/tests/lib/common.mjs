// Shared helpers for the §11.3 spike tests. Throwaway harness, NOT product code.
//
// Everything a leg needs to (a) act as a browser identity, (b) read the broker's own view of
// what happened (SEMP monitor/config), and (c) leave a machine-readable record of the outcome.
// Round 1 review MINOR 7: legs 1-10 used to print nothing — `record()` is how every leg now
// logs {attempted, expected, observed, pass} to evidence/legs.jsonl AND stdout.

import { appendFileSync, mkdirSync, realpathSync } from 'node:fs';
import { createRequire } from 'node:module';
import SolaceWebAppSDK from 'solace-webapp-sdk/dist/solace-webapp-sdk.es.js';

export { SolaceWebAppSDK };

export const WSS = 'wss://localhost:9444/mesh';
export const ISSUER = 'http://127.0.0.1:8091';
const SEMP_BASE = 'http://127.0.0.1:8081/SEMP/v2';
const SEMP_AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function waitFor(pred, { timeoutMs = 4000, stepMs = 100 } = {}) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    if (await pred()) return true;
    await sleep(stepMs);
  }
  return !!(await pred());
}

// ------------------------------------------------------------------ evidence record
const LEGS_FILE = new URL('../../evidence/legs.jsonl', import.meta.url).pathname;
try { mkdirSync(new URL('../../evidence/', import.meta.url).pathname, { recursive: true }); } catch { /* exists */ }

/** Log one leg's outcome. `entry` = {leg, attempted, expected, observed, pass, ...extra}. */
export function record(entry) {
  const line = JSON.stringify({ at: new Date().toISOString(), ...entry });
  try { appendFileSync(LEGS_FILE, line + '\n'); } catch { /* evidence dir absent: stdout still has it */ }
  console.log('LEG ' + line);
}

// ------------------------------------------------------------------ browser identities (round 2)
// The issuer mints `sub` only from  ^br-[0-9a-f]{16}$  (issuer.mjs BROWSER_SUB). These are the
// provisioned browser-facing client usernames (provision.sh) and the unprovisioned subjects the
// stream tests use (no client-username object: they fall through to the VPN's `default`).
export const BR = {
  GRANT: 'br-000000000000d001',      // the [orgA,su1,dev] notify grant   (was 'orgA-su1-dev')
  CATALOG: 'br-000000000000c001',    // grant + exact catalog exception   (was 'orgA-su1-dev-catalog')
  COMP1: 'br-000000000000c101',      // composed: notify su1/dev + stream substitution
  COMP2: 'br-000000000000c102',      // composed: notify su1/prod + stream substitution
  STREAM_A: 'br-00000000000000a1',   // (was 'stream-A') no dedicated object
  STREAM_B: 'br-00000000000000b1',   // (was 'stream-B')
  STREAM_Z: 'br-00000000000000f1',   // (was 'stream-Z')
  W1: 'br-000000000000e001',         // (was 'usr-w1')
  W2: 'br-000000000000e002',         // (was 'usr-w2')
  W3: 'br-000000000000e003',
};
// NON-browser usernames: NEVER mintable through the ruled /token. Tests reach them only via the
// measurement-only /raw-token (`{ raw: true }`), which is exactly the escalation the grammar closes.
export const NON_BROWSER = {
  CTL: 'orgA-su1-dev-ctl',           // acl-orgA-su1-dev + client profile sm-internal (positive control)
};

// ------------------------------------------------------------------ tokens
/** Ask the spike ticket issuer for a token. `raw` uses the measurement-only, rule-free endpoint.
 *  `claims.org` selects the audience (required by /token). */
export async function tryMint(claims, { raw = false } = {}) {
  const r = await fetch(`${ISSUER}/${raw ? 'raw-token' : 'token'}`, { method: 'POST', body: JSON.stringify(claims) });
  const body = await r.json().catch(() => ({}));
  return { ok: r.ok, status: r.status, token: body.token, error: body.error, reason: body.reason };
}

export async function mint(claims, opts) {
  const m = await tryMint(claims, opts);
  if (!m.ok) throw new Error(`issuer refused ${JSON.stringify(claims)}: ${m.status} ${m.reason}`);
  return m.token;
}

// ------------------------------------------------------------------ SDK connections
/** Connect to VPN `vpn` with a ticket for `sub`. `opts.tokenOrg` mints the ticket for a DIFFERENT
 *  organization than the VPN being logged in to (the cross-organization credential-portability
 *  legs); `opts.aud` (raw only) forces the audience. */
export async function oauthClient(vpn, sub, opts = {}) {
  const { tokenOrg, aud, raw } = opts;
  return SolaceWebAppSDK.connect({
    url: WSS, vpn, protocol: 'smf',
    auth: { type: 'oauth', accessToken: await mint({ org: tokenOrg ?? vpn, sub, ...(aud ? { aud } : {}) }, { raw }) },
  });
}

/** Try to log in; resolve {connected, client?, error?}. A refused login makes the SDK retry, so
 *  the attempt is bounded and always torn down. The CALLER must confirm from SEMP that no session
 *  appeared (a refusal is only real if the broker shows no session). */
export async function tryConnect(connectFn, { timeoutMs = 7000 } = {}) {
  let timer;
  const attemptP = connectFn().then((client) => ({ connected: true, client }), (err) => ({ connected: false, error: `${err?.name}: ${err?.message}` }));
  const timeout = new Promise((r) => { timer = setTimeout(() => r({ connected: false, error: `no login within ${timeoutMs} ms` }), timeoutMs); });
  const res = await Promise.race([attemptP, timeout]);
  clearTimeout(timer);
  if (!res.connected) attemptP.then((late) => late.client?.disconnect()).catch(() => {});
  return res;
}

// ------------------------------------------------------------------ raw solclientjs login
// The Web App SDK refuses to send a basic login with an EMPTY password ("Basic auth requires
// username and password") before anything reaches the broker, so it cannot show what the BROKER
// does with one. This drives solclientjs (the SDK's own dependency) directly over the plaintext
// SMF WebSocket the container publishes on 127.0.0.1:8009. Outcome is the library's session event.
let solclient;
export function solclientjs() {
  if (solclient) return solclient;
  const sdkDir = realpathSync(new URL('../../node_modules/solace-webapp-sdk', import.meta.url).pathname);
  const lib = createRequire(sdkDir + '/package.json')('solclientjs');
  const f = new lib.SolclientFactoryProperties();
  f.profile = lib.SolclientFactoryProfiles.version10;
  lib.SolclientFactory.init(f);
  lib.SolclientFactory.setLogLevel(lib.LogLevel.FATAL);
  return (solclient = lib);
}

/** One raw SMF login attempt, bounded. Resolves {connected, event, info}; never throws. */
export function rawLogin(vpn, userName, password, { timeoutMs = 6000, accessToken } = {}) {
  const lib = solclientjs();
  return new Promise((resolve) => {
    let session;
    let done = false;
    const finish = (r) => { if (done) return; done = true; clearTimeout(t); try { session?.disconnect(); session?.dispose(); } catch { /* ignore */ } resolve(r); };
    const t = setTimeout(() => finish({ connected: false, event: 'TIMEOUT', info: `no session event within ${timeoutMs} ms` }), timeoutMs);
    try {
      session = lib.SolclientFactory.createSession({ url: accessToken ? 'wss://localhost:9444/mesh' : 'ws://127.0.0.1:8009', vpnName: vpn, connectRetries: 0, reconnectRetries: 0,
        // OAuth2 requires a secure transport in solclientjs, so it goes through the spike's own nginx wss endpoint
        ...(accessToken ? { authenticationScheme: lib.AuthenticationScheme.OAUTH2, accessToken, sslValidateCertificate: false } : { userName, password }) });
      session.on(lib.SessionEventCode.UP_NOTICE, () => finish({ connected: true, event: 'UP_NOTICE', info: '' }));
      session.on(lib.SessionEventCode.CONNECT_FAILED_ERROR, (e) => finish({ connected: false, event: 'CONNECT_FAILED_ERROR', info: `${e.infoStr ?? ''} (subcode ${e.subcode})` }));
      session.on(lib.SessionEventCode.DISCONNECTED, () => finish({ connected: false, event: 'DISCONNECTED', info: '' }));
      session.connect();
    } catch (err) { finish({ connected: false, event: 'EXCEPTION', info: String(err?.message ?? err) }); }
  });
}

/** ROUND 4: a raw solclientjs login whose session is KEPT open on success so the caller can read
 *  what the broker resolved it to; call `close()` afterwards. `props` are solclientjs session
 *  properties (url, userName/password or authenticationScheme + tokens). Resolves
 *  {connected, event, info, close}; never throws. */
export function rawKeep(vpn, props, { timeoutMs = 7000 } = {}) {
  const lib = solclientjs();
  return new Promise((resolve) => {
    let session; let done = false;
    const close = () => { try { session?.disconnect(); session?.dispose(); } catch { /* ignore */ } };
    const finish = (r) => { if (done) return; done = true; clearTimeout(t); if (!r.connected) close(); resolve({ ...r, close }); };
    const t = setTimeout(() => finish({ connected: false, event: 'TIMEOUT', info: `no session event within ${timeoutMs} ms` }), timeoutMs);
    try {
      session = lib.SolclientFactory.createSession({ vpnName: vpn, connectRetries: 0, reconnectRetries: 0, ...props });
      session.on(lib.SessionEventCode.UP_NOTICE, () => finish({ connected: true, event: 'UP_NOTICE', info: '' }));
      session.on(lib.SessionEventCode.CONNECT_FAILED_ERROR, (e) => finish({ connected: false, event: 'CONNECT_FAILED_ERROR', info: `${e.infoStr ?? ''} (subcode ${e.subcode})` }));
      session.on(lib.SessionEventCode.DISCONNECTED, () => finish({ connected: false, event: 'DISCONNECTED', info: '' }));
      session.connect();
    } catch (err) { finish({ connected: false, event: 'EXCEPTION', info: String(err?.message ?? err) }); }
  });
}

/** An OAuth SMF login through the browser-facing wss endpoint with everything the caller wants on
 *  the wire: access token, ID token, and a client-supplied SMF `userName` next to them. */
export function rawOAuth(vpn, { accessToken, idToken, userName, timeoutMs = 7000 } = {}) {
  const lib = solclientjs();
  return rawKeep(vpn, { url: 'wss://localhost:9444/mesh', authenticationScheme: lib.AuthenticationScheme.OAUTH2, sslValidateCertificate: false,
    ...(accessToken ? { accessToken } : {}), ...(idToken ? { idToken } : {}), ...(userName ? { userName } : {}) }, { timeoutMs });
}

/** A basic-auth SMF login, session kept. `path` 'gateway' = the browser-facing wss endpoint (nginx);
 *  'backend' = the plaintext SMF WebSocket the container publishes on 127.0.0.1:8009, i.e. NOT through the gateway. */
export function rawBasic(vpn, userName, password, { path = 'gateway', timeoutMs = 7000 } = {}) {
  return rawKeep(vpn, path === 'gateway' ? { url: 'wss://localhost:9444/mesh', sslValidateCertificate: false, userName, password } : { url: 'ws://127.0.0.1:8009', userName, password }, { timeoutMs });
}

/** A basic-auth identity 'sm-internal-<vpn>': ACL sm/v1/> both ways, client profile sm-internal. */
export const internal = (vpn) => SolaceWebAppSDK.connect({
  url: WSS, vpn, protocol: 'smf',
  auth: { type: 'basic', username: `sm-internal-${vpn}`, password: `sm-internal-${vpn}` },
});

export async function trySubscribe(client, topic, handler = () => {}) {
  try {
    await client.subscribe(topic, handler);
    return { outcome: 'allowed' };
  } catch (err) {
    return {
      outcome: err?.code === 'SUBSCRIBE_ACL_DENIED' ? 'acl-denied' : 'other',
      name: err?.name, code: err?.code, message: err?.message,
    };
  }
}

export const ACL_DENIED = { outcome: 'acl-denied', name: 'AuthorizationError', code: 'SUBSCRIBE_ACL_DENIED' };

/** Run fn(); report {outcome:'resolved'|'rejected', ...error detail}. Never throws. */
export async function attempt(fn) {
  try {
    const value = await fn();
    return { outcome: 'resolved', value };
  } catch (err) {
    return { outcome: 'rejected', name: err?.name, code: err?.code, message: err?.message };
  }
}

// ------------------------------------------------------------------ SEMP
export async function semp(kind, path) {
  const r = await fetch(`${SEMP_BASE}/${kind}${path}`, { headers: { Authorization: SEMP_AUTH } });
  const body = await r.json().catch(() => ({}));
  return { status: r.status, code: body?.meta?.responseCode, data: body.data, error: body?.meta?.error, body };
}
export const sempConfig = (path) => semp('config', path);
export const sempMonitor = (path) => semp('monitor', path);

/** Every object of a SEMP collection, FOLLOWING PAGING (round 3 MINOR: the earlier reads used a
 *  fixed count=200 and no cursor, so anything past the first page would have been silently
 *  skipped; the broker's own default page is small). `pageSize` is exposed so a test can force
 *  several pages. Returns {data, pages}. */
export async function sempAll(kind, path, { pageSize = 100 } = {}) {
  const sep = path.includes('?') ? '&' : '?';
  let url = `${SEMP_BASE}/${kind}${path}${sep}count=${pageSize}`;
  const data = [];
  let pages = 0;
  while (url) {
    const r = await fetch(url, { headers: { Authorization: SEMP_AUTH } });
    const body = await r.json().catch(() => ({}));
    if (body?.meta?.responseCode !== 200) throw new Error(`sempAll ${kind}${path}: ${JSON.stringify(body?.meta?.error ?? body?.meta)}`);
    data.push(...(body.data ?? []));
    pages += 1;
    url = body.meta?.paging?.nextPageUri ?? null;
    if (pages > 500) throw new Error('sempAll: runaway paging');
  }
  return { data, pages };
}

/** SEMP action API (e.g. disconnect a client). Counted as a write when the broker answers 200. */
export async function sempAction(path, body = {}) {
  const r = await fetch(`${SEMP_BASE}/action${path}`, {
    method: 'PUT', headers: { Authorization: SEMP_AUTH, 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  const j = await r.json().catch(() => ({}));
  const ok = j?.meta?.responseCode === 200;
  if (ok) writes += 1;
  return { ok, status: r.status, error: j?.meta?.error };
}

let writes = 0;
/** Mutating SEMP call; counts every one that the broker answered 200 (measured write count). */
export async function sempWrite(method, path, body) {
  const r = await fetch(`${SEMP_BASE}/config${path}`, {
    method, headers: { Authorization: SEMP_AUTH, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const j = await r.json().catch(() => ({}));
  const ok = j?.meta?.responseCode === 200;
  if (ok) writes += 1;
  return { ok, status: r.status, error: j?.meta?.error, data: j.data };
}
export const writeCount = () => writes;
export const resetWriteCount = () => { writes = 0; };

/** Sessions on a VPN as the broker's monitor API reports them. */
export async function sessions(vpn) {
  const sel = 'clientName,clientUsername,originalClientUsername,aclProfileName,clientProfileName';
  const r = await sempMonitor(`/msgVpns/${vpn}/clients?select=${sel}&count=100`);
  return r.data ?? [];
}

/** The single session that appeared between two snapshots. */
export function addedSession(before, after) {
  const known = new Set(before.map((c) => c.clientName));
  const added = after.filter((c) => !known.has(c.clientName));
  if (added.length !== 1) throw new Error(`expected exactly 1 new session, got ${JSON.stringify(added)}`);
  return added[0];
}

/** Connect via `connectFn` and return {client, session} where session is the broker's view of it. */
export async function connectObserved(vpn, connectFn) {
  const before = await sessions(vpn);
  const client = await connectFn();
  const session = addedSession(before, await sessions(vpn));
  return { client, session };
}

export async function sessionNow(vpn, clientName) {
  return (await sessions(vpn)).find((c) => c.clientName === clientName);
}

/** Full monitor object for one session: carries the broker's own deny/bind counters. */
export async function clientDetail(vpn, clientName) {
  const r = await sempMonitor(`/msgVpns/${vpn}/clients/${encodeURIComponent(clientName)}`);
  return r.data;
}

/** The subscriptions the broker actually holds for one session (not what the SDK believes). */
export async function subscriptionsOf(vpn, clientName) {
  const r = await sempMonitor(`/msgVpns/${vpn}/clients/${encodeURIComponent(clientName)}/subscriptions?count=100`);
  return (r.data ?? []).map((x) => x.subscriptionTopic).sort();
}

// ------------------------------------------------------------------ hostile-client simulation
// solclientjs refuses guaranteed operations LOCALLY when the capabilities the broker advertised at
// login say the client may not do them ("Consumer is not supported by the broker for this
// client" / "Sending guaranteed message is not allowed by the broker for this client" — both
// thrown from solclientjs itself, before anything is sent). An attacker is not bound by the
// library. So every client-profile leg is ALSO run with the capability table forced to "yes",
// which makes the library put the bind / persistent publish / endpoint create ON THE WIRE, and
// the broker's own decision (plus its own counters) is what is observed.
// Capability numbers are solclientjs's CapabilityType enum (lib/solclientjs-debug.js):
//   9 GUARANTEED_MESSAGE_CONSUME, 10 TEMPORARY_ENDPOINT, 11 GUARANTEED_MESSAGE_PUBLISH,
//   12 GUARANTEED_MESSAGE_BROWSE, 13 ENDPOINT_MGMT, 25 TRANSACTED_SESSION, 28 SHARED_SUBSCRIPTIONS
const CAP = { CONSUME: 9, TEMP_ENDPOINT: 10, PUBLISH: 11, BROWSE: 12, ENDPOINT_MGMT: 13, TRANSACTED: 25, SHARED: 28 };

/** The capability table the broker advertised to this session (measured, not assumed). */
export function advertisedCapabilities(client) {
  const caps = client.adapter?.session?._capabilities ?? {};
  return Object.fromEntries(Object.entries(CAP).map(([name, n]) => [name, caps[n] === true]));
}

/** Force the capability table to "capable" so the library sends the operation to the broker. */
export function spoofCapabilities(client) {
  const caps = client.adapter?.session?._capabilities;
  if (!caps) throw new Error('spoofCapabilities: solclientjs session internals not reachable');
  for (const n of [CAP.CONSUME, CAP.TEMP_ENDPOINT, CAP.PUBLISH, CAP.BROWSE, CAP.ENDPOINT_MGMT]) caps[n] = true;
  // The persistent-publish gate is a second, separate local latch set at login
  // (SessionFSM._gmSendDisallowed, solclientjs-debug.js ~13288); clear it as well.
  const fsm = client.adapter.session._sessionFSM;
  if (fsm) fsm._gmSendDisallowed = null;
}

// ------------------------------------------------------------------ the 8-leaf fixture
export const ORGS = ['orgA', 'orgB'];
export const SUBS = ['su1', 'su2'];
export const ENVS = ['dev', 'prod'];

export const leaves = (org) => SUBS.flatMap((su) => ENVS.map((env) => ({ org, su, env })));
export const healthTopic = ({ org, su, env }) =>
  `sm/v1/${org}/${su}/${env}/notify/solution/s1/instance/i-${org}-${su}-${env}/health`;
export const placementTopic = ({ org, su, env }) =>
  `sm/v1/${org}/${su}/${env}/notify/solution/s1/instance/i-${org}-${su}-${env}/placement/p1/health`;
export const catalogTopic = (org) => `sm/v1/${org}/notify/catalog/package`;

/** Every topic the fixture publishes in one organization: 4 leaves x (health + placement) + catalog. */
export const orgTopics = (org) =>
  [...leaves(org).flatMap((l) => [healthTopic(l), placementTopic(l)]), catalogTopic(org)];

/** Publish the full fixture for one organization through its own internal publisher. */
export async function publishOrgFixture(pub, org, tag) {
  for (const t of orgTopics(org)) await pub.publish(t, { tag, topic: t });
}


// ================================================================== ROUND 3
// ------------------------------------------------------------------ browser grammar (shared)
// ROUND 4 (MINOR 2): the browser-facing ACL / identity check now lives in ONE SDK-free module,
// ./acl-check.mjs, which is what `provision.sh` runs at install time (via tools/acl-check.mjs) AND
// what the suite asserts (12A-all, 16-config) AND what tests/11 plants violations against. The
// previous round had a second, weaker Python re-implementation in provision.sh.
export { GRAMMAR, browserAclViolations, browserAclReport } from './acl-check.mjs';

// ------------------------------------------------------------------ the /ticket issuer (principal-bound)
// The spike issuer's /ticket endpoint is the PRODUCT-SHAPED path: the caller authenticates as a
// principal (stand-in: a static bearer credential — the spike does NOT measure real
// authentication/RBAC) and the issuer takes `sub`, the organization/audience and the lifetime
// ONLY from that principal's server-side grant record. The request carries none of them.
export const PRINCIPAL = {
  DEV:    { cred: 'cred-p-dev',    id: 'p-dev',    org: 'orgA', sub: 'br-000000000000d001', acl: 'acl-orgA-su1-dev',          note: 'notify grant [orgA,su1,dev]' },
  PROD:   { cred: 'cred-p-prod',   id: 'p-prod',   org: 'orgA', sub: 'br-000000000000c102', acl: 'acl-br-000000000000c102',   note: 'composed grant [orgA,su1,prod] + stream' },
  STREAM: { cred: 'cred-p-stream', id: 'p-stream', org: 'orgA', sub: 'br-00000000000000a1', acl: 'acl-orgA-stream-subst',     note: 'stream-only, no dedicated username (default fallthrough)' },
  ORGB:   { cred: 'cred-p-orgb',   id: 'p-orgb',   org: 'orgB', sub: 'br-00000000000000b2', acl: 'acl-orgB-stream-subst',     note: 'orgB stream-only' },
};
export const TICKET_TTL_S = 900;

/** Ask /ticket as principal `cred` (Bearer). `body` is what the caller ATTEMPTS to send; the
 *  product rule is that it is empty. `query` is appended to the URL. */
export async function tryTicket(cred, body, { query = '' } = {}) {
  const r = await fetch(`${ISSUER}/ticket${query}`, {
    method: 'POST',
    headers: { ...(cred ? { Authorization: `Bearer ${cred}` } : {}), 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const j = await r.json().catch(() => ({}));
  return { ok: r.ok, status: r.status, token: j.token, error: j.error, reason: j.reason };
}
export const decodeJwt = (token) => JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());

// ------------------------------------------------------------------ broker-side delivery evidence
// Round 3 MINOR: the Web App SDK only hands a message to a handler whose LOCAL pattern matches
// the topic (smf-adapter topicMatches) and drops the handler when a subscribe fails, so
// "got == []" is true by construction at the client. The broker's own per-client counter
// dataTxMsgCount ("The number of client data messages transmitted to the Client" — the broker's
// served SEMP monitor spec, MsgVpnClient) counts what the broker actually put on the wire.
export async function brokerTx(vpn, clientName) {
  const d = await clientDetail(vpn, clientName);
  return d.dataTxMsgCount;
}

/** Broker-side delivery delta since `base`. Waits until `expected` messages are counted (or the
 *  timeout), then waits `quietMs` and re-reads, so a late extra delivery is still caught. */
export async function settledDelta(vpn, clientName, base, expected, { timeoutMs = 6000, quietMs = 900 } = {}) {
  await waitFor(async () => (await brokerTx(vpn, clientName)) - base >= expected, { timeoutMs: expected > 0 ? timeoutMs : 0 });
  await sleep(quietMs);
  return (await brokerTx(vpn, clientName)) - base;
}

/** Every Message VPN on the broker, following paging, with the fields the browser web transport
 *  depends on. `browserReachable` = enabled AND a web-transport service on. */
export async function allVpns({ pageSize = 100 } = {}) {
  return (await sempAll('config', '/msgVpns', { pageSize })).data.map((x) => ({
    name: x.msgVpnName, enabled: x.enabled, webPlain: x.serviceWebPlainTextEnabled, webTls: x.serviceWebTlsEnabled,
    basicType: x.authenticationBasicType, browserReachable: x.enabled === true && (x.serviceWebPlainTextEnabled === true || x.serviceWebTlsEnabled === true),
  }));
}
/** Round 3 MINOR 3: VPNs the browser wss endpoint can reach that are not an organization's own. */
export async function otherVpnViolations() {
  return (await allVpns()).filter((x) => !ORGS.includes(x.name) && x.browserReachable);
}

// ------------------------------------------------------------------ temporary endpoints
// Round 3 MINOR 4. The SDK's session is built on its BUNDLED solclientjs copy, so a topic
// destination for addSubscription must come from that copy (the node_modules copy fails the
// library's instanceof validation: "Parameter topic failed validation").
import { readdirSync } from 'node:fs';
let sdkLib;
export async function sdkSolclient() {
  if (sdkLib) return sdkLib;
  const dist = realpathSync(new URL('../../node_modules/solace-webapp-sdk', import.meta.url).pathname) + '/dist';
  const file = readdirSync(dist).find((f) => /^solclient-.*\.js$/.test(f));
  if (!file) throw new Error('bundled solclient not found in the SDK dist');
  sdkLib = (await import(`${dist}/${file}`)).s.default;
  return sdkLib;
}

/** Create a NON-DURABLE queue or topic endpoint on `client`'s session and observe it.
 *  kind 'queue': then add each of `addSubs` (topic strings) to the temporary queue.
 *  kind 'topicEndpoint': the endpoint's topic is `endpointTopic`.
 *  Resolves {up, connectFailed, subs:{topic:'OK'|'ERROR <info>'}, got:[topics]}; never throws. */
export async function tempEndpoint(client, kind, { endpointTopic, addSubs = [], timeoutMs = 7000 } = {}) {
  const lib = await sdkSolclient();
  const E = lib.MessageConsumerEventName;
  const sess = client.adapter.session;
  return new Promise((resolve) => {
    const out = { up: false, connectFailed: null, subs: {}, got: [] };
    let mc; let done = false;
    const finish = () => { if (done) return; done = true; clearTimeout(t); resolve({ ...out, mc }); };
    const t = setTimeout(finish, timeoutMs);
    const maybeDone = () => { if (Object.keys(out.subs).length >= addSubs.length && out.up) setTimeout(finish, 400); };
    try {
      const props = kind === 'queue'
        ? { queueDescriptor: { type: lib.QueueType.QUEUE, durable: false }, connectAttempts: 1 }
        : { queueDescriptor: { type: lib.QueueType.TOPIC_ENDPOINT, durable: false }, topicEndpointSubscription: lib.SolclientFactory.createTopicDestination(endpointTopic), connectAttempts: 1 };
      mc = sess.createMessageConsumer(props);
      mc.on(E.MESSAGE, (m) => { out.got.push(m.getDestination().getName()); try { m.acknowledge(); } catch { /* auto-ack */ } });
      mc.on(E.CONNECT_FAILED_ERROR, (e) => { out.connectFailed = String(e?.infoStr ?? e?.message ?? 'CONNECT_FAILED_ERROR'); finish(); });
      mc.on(E.SUBSCRIPTION_OK, (e) => { out.subs[String(e.correlationKey)] = 'OK'; maybeDone(); });
      mc.on(E.SUBSCRIPTION_ERROR, (e) => { out.subs[String(e.correlationKey)] = `ERROR ${e?.infoStr ?? ''}`.trim(); maybeDone(); });
      mc.on(E.UP, () => {
        out.up = true;
        if (kind === 'queue') for (const tp of addSubs) { try { mc.addSubscription(lib.SolclientFactory.createTopicDestination(tp), tp, 4000); } catch (e) { out.subs[tp] = `THREW ${e.message}`; } }
        maybeDone();
        if (kind !== 'queue' || addSubs.length === 0) setTimeout(finish, 1200);
      });
      mc.connect();
    } catch (e) { out.connectFailed = `THREW ${e.message}`; finish(); }
  });
}


// ------------------------------------------------------------------ durable topic endpoint (round 4 MINOR 5)
/** Bind to an existing DURABLE topic endpoint by name on `client`'s session (raw consumer on the SDK's
 *  bundled solclientjs, as tempEndpoint does). `subscription` is the topic the client states for the
 *  endpoint (solclientjs requires it for a durable topic endpoint unless creation is allowed; a
 *  topic endpoint's subscription is set by a bind, not by SEMP). Resolves {up, connectFailed,
 *  got:[topics], mc}; never throws. */
export async function bindDurableTopicEndpoint(client, name, subscription, { timeoutMs = 7000, settleMs = 1500, createIfMissing = false } = {}) {
  const lib = await sdkSolclient();
  const E = lib.MessageConsumerEventName;
  const sess = client.adapter.session;
  return new Promise((resolve) => {
    const out = { up: false, connectFailed: null, got: [] };
    let mc; let done = false;
    const finish = () => { if (done) return; done = true; clearTimeout(t); resolve({ ...out, mc }); };
    const t = setTimeout(finish, timeoutMs);
    try {
      mc = sess.createMessageConsumer({ queueDescriptor: { type: lib.QueueType.TOPIC_ENDPOINT, name, durable: true }, ...(subscription ? { topicEndpointSubscription: lib.SolclientFactory.createTopicDestination(subscription) } : {}), ...(createIfMissing ? { createIfMissing: true } : {}), connectAttempts: 1 });
      mc.on(E.MESSAGE, (m) => { out.got.push(m.getDestination().getName()); try { m.acknowledge(); } catch { /* auto-ack */ } });
      mc.on(E.CONNECT_FAILED_ERROR, (e) => { out.connectFailed = String(e?.infoStr ?? e?.message ?? 'CONNECT_FAILED_ERROR'); finish(); });
      mc.on(E.UP, () => { out.up = true; setTimeout(finish, settleMs); });
      mc.connect();
    } catch (e) { out.connectFailed = `THREW ${e.message}`; finish(); }
  });
}
