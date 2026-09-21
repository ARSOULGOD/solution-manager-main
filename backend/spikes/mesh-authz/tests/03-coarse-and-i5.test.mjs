// G1 candidate (c) — tenant-coarse ACL + unguessable grant-ids, plus the design §14 security
// fixtures. Throwaway spike test, NOT product code.
//
// Candidate (c) does not try to authorize per grant at the broker at all: one ACL profile per
// TENANT, whose exceptions cover that tenant's whole notify and stream namespace, and the
// isolation BETWEEN grants of the same tenant comes from grant-ids being unguessable. So it
// always "works", and a green run here is not the finding. The finding is the EXPOSURE
// BOUNDARY — exactly what a legitimately-issued token can reach beyond what it was issued for
// — and tests 1 and 2 state it by measurement rather than by argument.
//
// This file also carries THREE of the six security fixtures design §14 requires (§14's other
// three are token-lifecycle fixtures and live in tests/04), plus two extras, which the
// mesh-provisioning plan lifts into CI. Test numbers are this file's `it(` order:
//   F1  I5 cross-tenant deny            — tests 4 and 5 (both directions)   [§14 fixture 1]
//   F1b I5 at DELIVERY: a live receiving session gets in-tenant payloads and is refused a
//       cross-tenant widen on the same session — test 2                     [beyond §14's list]
//   F2  expired ticket refused at login — test 6 (control at test 7)        [§14 fixture 3]
//   F3  notify-vs-stream scope separation — test 8                          [§14 fixture 2]
//   F4  browser identities stay subscribe-only — test 9 (asserted against SEMP config, because
//       a publish denial is invisible to the client — Task 1 finding 1)     [beyond §14's list]
//
// I2. No new SEMP resource or property is used here: `aclProfiles`,
// `aclProfiles/{n}/subscribeTopicExceptions` and `clientUsernames` with exactly the property
// sets provision.sh writes were re-read out of the broker's own served SEMP 2.48 spec for this
// task (see the README's Task 4 SEMP evidence).
//
// FALSE-GREEN GUARDS (the R6 class). Three hazards, all of them live in this file:
//   1. `acl-browser-baseline` carries a blanket `sm/v1/>` subscribe exception, and any subject
//      with no client-username object falls through to the VPN's `default`, which is bound to
//      it (Task 1 finding 3). A candidate-(c) positive would then pass against the blanket
//      profile rather than against `acl-acme`. `tenant-acme`/`tenant-globex`/`tenant-acme-notify`
//      ARE pre-created, but "should be" is not a measurement: every test that connects asserts
//      the session's effective `clientUsername` and `aclProfileName` from the SEMP monitor
//      before believing any subscribe outcome.
//   2. A bare `rejects.toThrow()` is also what a timeout or a dropped session produces. Every
//      subscribe negative asserts `AuthorizationError` / `SUBSCRIBE_ACL_DENIED`.
//   3. The SDK retries a refused login forever by default, so the expired-ticket negative would
//      die on vitest's timeout instead of on the broker's refusal. `connectRetries: 0`
//      everywhere (Task 3's finding), and the refusal is asserted to have left no session.
//
// Deviations from the task text are listed in README "Deviations from the task text" / Task 4.

import { describe, it, expect } from 'vitest';
// Task 1 deviation 2: the bare specifier resolves to the UMD build and imports as empty.
import SolaceWebAppSDK from 'solace-webapp-sdk/dist/solace-webapp-sdk.es.js';

const WSS = 'wss://localhost:9443/mesh'; // npm test sets NODE_TLS_REJECT_UNAUTHORIZED=0
const VPN = 'sm-spike';
const SEMP = 'http://localhost:8080/SEMP/v2/monitor';
const SEMP_CONFIG = 'http://localhost:8080/SEMP/v2/config';
const SEMP_AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');

// Copied from 00/01/02 on purpose — the spike stays dependency-flat (task brief).
async function mint(claims) {
  const r = await fetch('http://localhost:8090/token', { method: 'POST', body: JSON.stringify(claims) });
  return (await r.json()).token;
}

// Candidate (c) runs on the VPN's DEFAULT OAuth profile (`sm_spike_oauth`), so no
// issuerIdentifier: authorization is by client-username object, which is candidate (c)'s whole
// mechanism. `connectRetries: 0` is what makes the expired-ticket negative deterministic.
// `accessToken` lets a caller supply a token minted EARLIER — the expired-ticket fixture has to
// mint, wait for the token to age out, and only then connect.
const connect = async (claims, { accessToken } = {}) => SolaceWebAppSDK.connect({
  url: WSS, vpn: VPN, protocol: 'smf', connectRetries: 0,
  auth: { type: 'oauth', accessToken: accessToken ?? await mint(claims) },
});

// `sm-internal` (basic auth, acl-internal) is the ONLY identity in the spike allowed to
// publish — design §3, and every browser-facing profile keeps publish default-disallow. Copied
// from 00-connect.test.mjs; the exposure-delivery test needs a real publisher.
const internal = () => SolaceWebAppSDK.connect({
  url: WSS, vpn: VPN, protocol: 'smf',
  auth: { type: 'basic', username: 'sm-internal', password: 'sm-internal' },
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Poll a predicate and CLASSIFY the outcome, so a timeout can never masquerade as a result.
// A test that just `await sleep(n)` and then asserts cannot tell "it arrived" from "the wait
// happened to be long enough" — and, worse, its failure mode reads identically to a broker
// that is simply slow.
async function waitFor(predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return 'satisfied';
    await sleep(50);
  }
  return 'timed-out';
}

// Every live client session in the VPN, keyed by clientName, with the client username the
// broker actually resolved for it and the ACL profile that resolution selected.
async function sessions() {
  const url = `${SEMP}/msgVpns/${VPN}/clients`
    + '?select=clientName,clientUsername,originalClientUsername,aclProfileName&count=100';
  const r = await fetch(url, { headers: { Authorization: SEMP_AUTH } });
  return new Map(((await r.json()).data ?? []).map((c) => [c.clientName, c]));
}

function newSession(before, after) {
  const added = [...after.values()].filter((c) => !before.has(c.clientName));
  expect(added.length, JSON.stringify(added)).toBe(1);
  return added[0];
}

// Connect and report the session the broker created, so a test can assert WHICH authorization
// path won before it believes any subscribe outcome. Guard 1 above.
async function connectAndInspect(claims) {
  const before = await sessions();
  const client = await connect(claims);
  return { client, session: newSession(before, await sessions()) };
}

// Subscribe and classify the outcome, so a test can assert WHY it failed rather than THAT it
// failed. Guard 2 above.
async function trySubscribe(client, topic) {
  try {
    const result = await client.subscribe(topic, () => {});
    return { outcome: 'allowed', result };
  } catch (err) {
    return {
      outcome: err?.code === 'SUBSCRIBE_ACL_DENIED' ? 'acl-denied' : 'other',
      name: err?.name, code: err?.code, message: err?.message,
    };
  }
}

// Same idea one layer down: classify a LOGIN outcome, and prove a refusal left no session
// behind rather than merely being slow. Guard 3 above.
async function tryConnect(claims, opts) {
  const before = await sessions();
  try {
    const client = await connect(claims, opts);
    const session = newSession(before, await sessions());
    await client.disconnect();
    return { outcome: 'connected', session };
  } catch (err) {
    const appeared = [...(await sessions()).values()]
      .filter((c) => !before.has(c.clientName) && c.originalClientUsername === claims.sub);
    return {
      outcome: 'login-refused',
      name: err?.name, code: err?.code, message: err?.message,
      straySessions: appeared,
    };
  }
}

const DENIED = { outcome: 'acl-denied', name: 'AuthorizationError', code: 'SUBSCRIBE_ACL_DENIED' };

describe('G1 candidate (c) — tenant-coarse ACL, and the design §14 security fixtures', () => {
  it('candidate c: tenant-coarse ACL admits any stream in-tenant (the stated exposure)', async () => {
    const { client: c, session: s } = await connectAndInspect(
      { sub: 'tenant-acme', tenant: 'acme', scopes: ['stream'] }
    );
    console.log('candidate (c) session:', JSON.stringify(s));

    // THE mechanism assertion — without it every subscribe below could be acl-browser-baseline's
    // blanket `sm/v1/>` exception answering instead of acl-acme.
    expect(s.clientUsername).toBe('tenant-acme');
    expect(s.originalClientUsername).toBe('tenant-acme');
    expect(s.aclProfileName).toBe('acl-acme');

    // The task text's topic. subscribe() is declared Promise<void> so it resolves to undefined
    // (Task 2 deviation 2); resolution IS the broker's SUBSCRIPTION_OK.
    await expect(c.subscribe('sm/v1/acme/stream/any-grant/s1', () => {})).resolves.toBeUndefined();

    // THE EXPOSURE, measured rather than asserted from the config. None of these grant-ids has
    // ever been issued to this session — two are arbitrary strings invented here, one is another
    // candidate's grant object, one is a whole-tier wildcard. All are admitted, because the
    // exception has no grant level at all.
    for (const topic of [
      'sm/v1/acme/stream/some-other-grant/s1',      // never issued to anyone
      'sm/v1/acme/stream/grant-111/s1',             // issued to a DIFFERENT identity (Task 2)
      'sm/v1/acme/stream/>',                        // the entire stream tier in one subscription
      'sm/v1/acme/notify/instance/i-1/health',      // and the whole notify tier as well
    ]) {
      const r = await trySubscribe(c, topic);
      expect(r, `${topic}: ${JSON.stringify(r)}`).toMatchObject({ outcome: 'allowed' });
    }

    expect(c.isConnected()).toBe(true);
    await c.disconnect();
  }, 30000);

  it('candidate (c) exposure DELIVERED: one wildcard subscription enumerates other grants and receives their payloads', async () => {
    // Test 1 measures that the wildcard and other-grant subscriptions are ADMITTED. Admission is
    // not receipt. Enumeration ("which grant-ids are live?") and payload disclosure both need
    // traffic to actually FLOW to a wildcard subscriber, and nothing above measures that — so
    // without this test the exposure headline would be one inference step past the evidence.
    // Here the traffic is real: `sm-internal` publishes to two distinct grant streams and the
    // in-tenant wildcard session recovers both grant-ids and both payloads.
    const { client: c, session: s } = await connectAndInspect(
      { sub: 'tenant-acme', tenant: 'acme', scopes: ['stream'] }
    );
    expect(s.clientUsername).toBe('tenant-acme');
    expect(s.aclProfileName).toBe('acl-acme');

    const heard = [];
    // Await the subscribe before publishing: the broker confirms subscriptions asynchronously,
    // so publishing straight afterwards is a race, not a test (Task 1 deviation 7).
    await c.subscribe('sm/v1/acme/stream/>', (m) => heard.push({ topic: m.topic, payload: m.payload }));

    const pub = await internal();
    // Neither grant belongs to this session. `grant-111` is Task 2's client-username object —
    // a DIFFERENT identity's grant; `grant-other` was never issued to anybody.
    await pub.publish('sm/v1/acme/stream/grant-111/s1', { v: 11 });
    await pub.publish('sm/v1/acme/stream/grant-other/s2', { v: 22 });
    // Cross-tenant control, published into the same window: the tenant edge must hold for
    // DELIVERY too, not only for subscription admission.
    await pub.publish('sm/v1/globex/stream/grant-g/s9', { v: 99 });

    const outcome = await waitFor(() => heard.length >= 2, 10000);
    console.log('exposure delivery:', outcome, JSON.stringify(heard));
    // Classified, so a timeout is reported as a timeout rather than as an empty result.
    expect(outcome, JSON.stringify(heard)).toBe('satisfied');
    // Give the cross-tenant message every chance to show up before asserting that it did not.
    await sleep(1000);

    // ENUMERATION, as a fact: two distinct grant-ids recovered from the received topic strings
    // by a session that was never told either of them.
    const grantIds = [...new Set(heard.map((h) => h.topic.split('/')[4]))].sort();
    expect(grantIds, JSON.stringify(heard)).toEqual(['grant-111', 'grant-other']);

    // PAYLOAD RECEIPT, as a fact: not just the topic names — the message bodies too.
    const byTopic = Object.fromEntries(heard.map((h) => [h.topic, h.payload]));
    expect(byTopic['sm/v1/acme/stream/grant-111/s1']).toEqual({ v: 11 });
    expect(byTopic['sm/v1/acme/stream/grant-other/s2']).toEqual({ v: 22 });

    // The tenant edge holds at delivery too — but state the reason precisely, because the two
    // halves are not the same fact:
    //   (i) nothing from globex reached this subscription. On its own that is weak: the
    //       subscription is `sm/v1/acme/stream/>`, so a topic FILTER would produce it even with
    //       no ACL at all. (Measured: planting a `sm/v1/globex/stream/>` exception onto acl-acme
    //       does NOT make this assertion fire — see the README's plant C.) Kept as a sanity
    //       check, with the two acme messages as its positive control.
    expect(heard.filter((h) => h.topic.startsWith('sm/v1/globex/')), JSON.stringify(heard)).toEqual([]);
    //   (ii) and the reason it cannot be widened: this session may not create the cross-tenant
    //        subscription that would catch that traffic. THIS is the ACL doing the work, on the
    //        same live session that just received the in-tenant messages — so the edge is
    //        enforced, not merely unexercised.
    const widen = await trySubscribe(c, 'sm/v1/globex/stream/>');
    expect(widen, JSON.stringify(widen)).toMatchObject(DENIED);

    await Promise.all([c.disconnect(), pub.disconnect()]);
  }, 30000);

  it('candidate (c): the `scopes` claim is INERT at the broker — a notify-scope token still reads streams', async () => {
    // The sharpest statement of the exposure, and the reason fixture F3 needs a second ACL
    // profile. The broker authorizes off the client-username object; nothing in the OAuth
    // profile reads `scopes` (provision.sh sets resourceServerValidateScopeEnabled: false, and
    // even enabled it would gate the LOGIN, not per-topic subscribe authorization). So a ticket
    // minted for notify only is, at the mesh, indistinguishable from a stream ticket.
    const { client: c, session: s } = await connectAndInspect(
      { sub: 'tenant-acme', tenant: 'acme', scopes: ['notify'] }
    );
    expect(s.aclProfileName).toBe('acl-acme');

    const stream = await trySubscribe(c, 'sm/v1/acme/stream/any-grant/s1');
    console.log('notify-scope token vs stream topic on acl-acme:', JSON.stringify(stream));
    // Allowed — and that is the finding, not a failure of this test.
    expect(stream, JSON.stringify(stream)).toMatchObject({ outcome: 'allowed' });

    await c.disconnect();
  }, 30000);

  it('I5: tenant acme token cannot subscribe tenant globex topics', async () => {
    // Design §14 fixture F1. The `globex` namespace is REALLY configured (acl-globex carries
    // both exceptions, provision.sh [12/13]), so this denies a namespace the broker would
    // otherwise serve — not one nobody set up.
    const { client: c, session: s } = await connectAndInspect(
      { sub: 'tenant-acme', tenant: 'acme', scopes: ['notify'] }
    );
    expect(s.clientUsername).toBe('tenant-acme');
    expect(s.aclProfileName).toBe('acl-acme');

    for (const topic of [
      'sm/v1/globex/notify/>',                      // the task text's topic
      'sm/v1/globex/notify/instance/i-9/health',
      'sm/v1/globex/stream/any-grant/s1',
      'sm/v1/>',                                    // and the cross-tenant wildcard
    ]) {
      const r = await trySubscribe(c, topic);
      expect(r, `${topic}: ${JSON.stringify(r)}`).toMatchObject(DENIED);
    }

    // Positive control in the same session: its OWN tenant is still reachable, so the denials
    // above mean "cross-tenant" and not "this session cannot subscribe to anything".
    await expect(c.subscribe('sm/v1/acme/notify/instance/i-1/health', () => {})).resolves.toBeUndefined();

    expect(c.isConnected()).toBe(true);
    await c.disconnect();
  }, 30000);

  it('I5 mirror: tenant globex token cannot subscribe tenant acme topics', async () => {
    // The mirror is what makes F1 a property of the isolation rather than of one profile being
    // narrower than the other. Same shape, opposite direction.
    const { client: c, session: s } = await connectAndInspect(
      { sub: 'tenant-globex', tenant: 'globex', scopes: ['notify', 'stream'] }
    );
    expect(s.clientUsername).toBe('tenant-globex');
    expect(s.aclProfileName).toBe('acl-globex');

    for (const topic of ['sm/v1/acme/notify/>', 'sm/v1/acme/stream/any-grant/s1']) {
      const r = await trySubscribe(c, topic);
      expect(r, `${topic}: ${JSON.stringify(r)}`).toMatchObject(DENIED);
    }
    await expect(c.subscribe('sm/v1/globex/stream/any-grant/s1', () => {})).resolves.toBeUndefined();

    await c.disconnect();
  }, 30000);

  it('expired ticket is refused at connect', async () => {
    // Design §14 fixture F2. The issuer honours an `expiresIn` minting directive (Task 4's
    // issuer change); default stays '15m', so Tasks 1-3's tokens are unchanged.
    const claims = { sub: 'tenant-acme', tenant: 'acme', expiresIn: '1s' };
    const accessToken = await mint(claims);
    await new Promise((res) => setTimeout(res, 2000)); // let it age past `exp`
    const r = await tryConnect(claims, { accessToken });
    console.log('expired-ticket login:', JSON.stringify({ outcome: r.outcome, name: r.name, code: r.code, message: r.message }));

    expect(r.outcome, JSON.stringify(r)).toBe('login-refused');
    // A refusal that still produced a session would mean the broker let it in.
    expect(r.straySessions, JSON.stringify(r.straySessions)).toEqual([]);
    // The right red, MEASURED not assumed. An expired access token is a FOURTH distinct broker
    // refusal shape, different from Task 3's three authorization-group messages: the broker
    // answers `Subcode: 13 respCode: 401 respString: Unauthorized` — 401, not 403 — and the SDK
    // maps it to AuthenticationError / AUTH_FAILED. It carries no reason, so "expired" is not
    // distinguishable at the client from any other token-validation failure.
    expect(r.name).toBe('AuthenticationError');
    expect(r.code).toBe('AUTH_FAILED');
    expect(r.message).toContain('Unauthorized');
  }, 30000);

  it('expired-ticket CONTROL: the same short-lived minting path connects while still valid', async () => {
    // Without this the test above proves only "a token minted with expiresIn is refused", which
    // an issuer bug would also produce. Same code path, same claims, used before it expires.
    const ok = await tryConnect({ sub: 'tenant-acme', tenant: 'acme', expiresIn: '30s' });
    expect(ok.outcome, JSON.stringify(ok)).toBe('connected');
    expect(ok.session.aclProfileName).toBe('acl-acme');
  }, 30000);

  it('§14 F3 scope separation: under (c) it takes a SECOND ACL profile, not a scope claim', async () => {
    // Design §14's second fixture — "a notify-scope ticket must not reach a stream topic" — is
    // expressible under candidates (a)/(b) against ONE identity. Under (c) it is not
    // expressible by the token at all (test 3 above), so the only way to get it is a separate
    // `sub` bound to a separate ACL profile carrying only the notify exception. That profile is
    // `acl-acme-notify`, and this test measures both halves of the trade.
    const { client: c, session: s } = await connectAndInspect(
      { sub: 'tenant-acme-notify', tenant: 'acme', scopes: ['notify'] }
    );
    console.log('notify-scope identity session:', JSON.stringify(s));
    expect(s.clientUsername).toBe('tenant-acme-notify');
    expect(s.aclProfileName).toBe('acl-acme-notify');

    // Notify is reachable...
    await expect(c.subscribe('sm/v1/acme/notify/instance/i-1/health', () => {})).resolves.toBeUndefined();

    // ...and the stream tier is refused, which is the fixture.
    for (const topic of ['sm/v1/acme/stream/any-grant/s1', 'sm/v1/acme/stream/>']) {
      const r = await trySubscribe(c, topic);
      expect(r, `${topic}: ${JSON.stringify(r)}`).toMatchObject(DENIED);
    }

    // The cost, asserted rather than asserted-in-prose: the separation lives in a broker OBJECT
    // pair (profile + client username), one per tenant x scope, and NOT in the ticket.
    const p = await fetch(`${SEMP_CONFIG}/msgVpns/${VPN}/aclProfiles/acl-acme-notify/subscribeTopicExceptions`,
      { headers: { Authorization: SEMP_AUTH } });
    expect(((await p.json()).data ?? []).map((e) => e.subscribeTopicException))
      .toEqual(['sm/v1/acme/notify/>']);
    const u = await fetch(`${SEMP_CONFIG}/msgVpns/${VPN}/clientUsernames/tenant-acme-notify`,
      { headers: { Authorization: SEMP_AUTH } });
    expect((await u.json()).data).toMatchObject({
      clientUsername: 'tenant-acme-notify', aclProfileName: 'acl-acme-notify', enabled: true,
    });

    await c.disconnect();
  }, 30000);

  it('§14 F4: every candidate (c) profile stays subscribe-only — no publish exception exists', async () => {
    // Design §3 / the plan's global constraint: browsers never publish. A publish denial is
    // invisible to the client (Task 1 finding 1), so the guarantee is asserted where it lives —
    // the broker's config — for all three profiles this task added.
    for (const prof of ['acl-acme', 'acl-globex', 'acl-acme-notify']) {
      const r = await fetch(`${SEMP_CONFIG}/msgVpns/${VPN}/aclProfiles/${prof}`,
        { headers: { Authorization: SEMP_AUTH } });
      const profile = (await r.json()).data;
      expect(profile.publishTopicDefaultAction, prof).toBe('disallow');
      expect(profile.subscribeTopicDefaultAction, prof).toBe('disallow');

      const ex = await fetch(`${SEMP_CONFIG}/msgVpns/${VPN}/aclProfiles/${prof}/publishTopicExceptions`,
        { headers: { Authorization: SEMP_AUTH } });
      expect((await ex.json()).data ?? [], prof).toEqual([]);
    }
  }, 30000);
});
