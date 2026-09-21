// G1 candidate (a) — substitution-variable ACL. Throwaway spike test, NOT product code.
//
// The question BE-D5 needs answered: can ONE ACL profile give every grant-id its own stream
// branch, so that adding a grant costs nothing on the broker? Candidate (a) says yes, via a
// substitution variable in the topic exception.
//
// GRAMMAR (I2). The plan's task text guessed `sm/v1/acme/stream/${clientUsername}/>`. That is
// not the broker's grammar. The broker's own SEMP 2.48 spec documents no substitution for
// subscribeTopicException at all, and its own CLI help for
// `acl-profile / subscribe-topic / exceptions` documents only `<topic>[1..250 chars]`. Solace's
// docs carry the real grammar, verbatim:
//   "$client-username —For this variable, the event broker will substitute the client username
//    provided by the client or retrieved from the TLS client-certificate during client login."
//   "Substitution variables must appear alone at any level in the topic exception."
//   (https://docs.solace.com/Security/Managing-Access-Control-Lists.htm,
//    https://docs.solace.com/Security/Granting-Clients-Access.htm#Using)
// So the exception under test is `sm/v1/acme/stream/$client-username/>`, and the plan's
// spelling is provisioned alongside it on `stream2/` purely as a grammar control.
//
// OBSERVABILITY. Task 1 finding 1 is that a PUBLISH denial is invisible to the SDK. A
// SUBSCRIBE denial is not: `SmfAdapter.subscribeToBroker()` calls `session.subscribe(..., true
// /* requestConfirmation */, topic /* correlationKey */, 10000)` and registers a
// SUBSCRIPTION_ERROR listener that rejects with `AuthorizationError` /
// `ErrorCode.SUBSCRIBE_ACL_DENIED` on broker subcode 16 (SUBSCRIPTION_ACL_DENIED). Verified in
// the SDK source at src/protocols/smf/smf-adapter.ts:830-897 before relying on it. Every
// negative below therefore asserts the ACL-denial code specifically — a bare rejection would
// also be produced by a timeout or a dropped session, and that is the R6 false-green class.
//
// Deviations from the task text are listed in README "Deviations from the task text".

import { describe, it, expect } from 'vitest';
// Task 1 deviation 2: the bare specifier resolves to the UMD build and imports as empty.
import SolaceWebAppSDK from 'solace-webapp-sdk/dist/solace-webapp-sdk.es.js';

const WSS = 'wss://localhost:9443/mesh'; // npm test sets NODE_TLS_REJECT_UNAUTHORIZED=0
const VPN = 'sm-spike';
const SEMP = 'http://localhost:8080/SEMP/v2/monitor';
const SEMP_AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');

// Copied from 00-connect.test.mjs on purpose — the spike stays dependency-flat (task brief).
async function mint(claims) {
  const r = await fetch('http://localhost:8090/token', { method: 'POST', body: JSON.stringify(claims) });
  return (await r.json()).token;
}

const browser = async (claims) => SolaceWebAppSDK.connect({
  url: WSS, vpn: VPN, protocol: 'smf',
  auth: { type: 'oauth', accessToken: await mint(claims) },
});

// Every live client session in the VPN, keyed by clientName, with the client username the
// broker actually resolved for it and the ACL profile that resolution selected.
async function sessions() {
  const url = `${SEMP}/msgVpns/${VPN}/clients`
    + '?select=clientName,clientUsername,originalClientUsername,aclProfileName&count=100';
  const r = await fetch(url, { headers: { Authorization: SEMP_AUTH } });
  const body = await r.json();
  return new Map((body.data ?? []).map((c) => [c.clientName, c]));
}

// The session that appeared between two snapshots. The OAuth `sub` cannot be used to find it:
// an unmatched subject is mapped to the `default` client username (Task 1 finding 3), so the
// session is not filed under its own subject name.
function newSession(before, after) {
  const added = [...after.values()].filter((c) => !before.has(c.clientName));
  expect(added.length, JSON.stringify(added)).toBe(1);
  return added[0];
}

// Subscribe and classify the outcome, so a test can assert WHY it failed rather than THAT it
// failed. 'allowed' | 'acl-denied' | anything else is a bug in the harness, not a verdict.
async function trySubscribe(client, topic) {
  try {
    const result = await client.subscribe(topic, () => {});
    return { outcome: 'allowed', result };
  } catch (err) {
    return {
      outcome: err?.code === 'SUBSCRIBE_ACL_DENIED' ? 'acl-denied' : 'other',
      name: err?.name,
      code: err?.code,
      message: err?.message,
    };
  }
}

describe('G1 candidate (a) — substitution-variable ACL', () => {
  it('candidate a: grant-111 can subscribe its own stream branch, not grant-222s', async () => {
    const c = await browser({ sub: 'grant-111', tenant: 'acme', scopes: ['stream'] });

    // subscribe() is declared Promise<void>, so it resolves to undefined — the plan's
    // `.resolves.toBeDefined()` could never pass. Resolution IS the broker's SUBSCRIPTION_OK.
    await expect(c.subscribe('sm/v1/acme/stream/grant-111/s1', () => {})).resolves.toBeUndefined();

    const denied = await trySubscribe(c, 'sm/v1/acme/stream/grant-222/s1');
    // The right red: refused by the subscribe-topic ACL, not by a timeout or a dead session.
    expect(denied, JSON.stringify(denied)).toMatchObject({
      outcome: 'acl-denied', name: 'AuthorizationError', code: 'SUBSCRIBE_ACL_DENIED',
    });
    expect(c.isConnected()).toBe(true);

    await c.disconnect();
  }, 30000);

  it('the branch follows the session, not the profile: grant-222 is the mirror image', async () => {
    // Same single ACL profile, opposite outcome — this is what proves the exception is
    // substituted per session rather than being a lucky literal match for grant-111.
    const c = await browser({ sub: 'grant-222', tenant: 'acme', scopes: ['stream'] });

    await expect(c.subscribe('sm/v1/acme/stream/grant-222/s1', () => {})).resolves.toBeUndefined();

    const denied = await trySubscribe(c, 'sm/v1/acme/stream/grant-111/s1');
    expect(denied, JSON.stringify(denied)).toMatchObject({
      outcome: 'acl-denied', name: 'AuthorizationError', code: 'SUBSCRIBE_ACL_DENIED',
    });

    await c.disconnect();
  }, 30000);

  it('a pre-created client username makes the sub its own authorization identity', async () => {
    // Two different usernames live on a session, and the difference is the whole finding.
    // The monitor spec (SEMP 2.48, MsgVpnClient) defines them as:
    //   originalClientUsername — "The original value of the client username used for Client
    //                             authentication."   (here: the OAuth `sub`)
    //   clientUsername         — "The client username of the Client used for authorization."
    //                             (here: the client-username OBJECT that selects the ACL)
    // When the object exists, the two agree and the sub selects its own ACL profile.
    const before = await sessions();
    const c = await browser({ sub: 'grant-111', tenant: 'acme', scopes: ['stream'] });
    const s = newSession(before, await sessions());

    expect(s.originalClientUsername).toBe('grant-111');
    expect(s.clientUsername).toBe('grant-111');
    expect(s.aclProfileName).toBe('acl-subst');

    await c.disconnect();
  }, 30000);

  it('grammar control: the plans ${clientUsername} spelling is inert, not a substitution', async () => {
    // `sm/v1/acme/stream2/${clientUsername}/>` is provisioned on the same profile. If the
    // broker substituted that spelling too, this subscribe would be allowed and the grammar
    // finding would be wrong.
    const c = await browser({ sub: 'grant-111', tenant: 'acme', scopes: ['stream'] });

    const denied = await trySubscribe(c, 'sm/v1/acme/stream2/grant-111/s1');
    expect(denied, JSON.stringify(denied)).toMatchObject({
      outcome: 'acl-denied', name: 'AuthorizationError', code: 'SUBSCRIBE_ACL_DENIED',
    });

    // ...and the literal path IS reachable, so the exception exists and is simply taken at
    // face value. This is the positive control that makes the denial above mean "no
    // substitution" rather than "no exception".
    await expect(
      c.subscribe('sm/v1/acme/stream2/${clientUsername}/s1', () => {})
    ).resolves.toBeUndefined();

    await c.disconnect();
  }, 30000);

  it('an UNMATCHED subject still gets its OWN branch — $client-username is the AUTHENTICATION username', async () => {
    // THE cost question for BE-D5, and the answer is the good one.
    //
    // Task 1 finding 3: an OAuth subject with no client-username object is mapped to the VPN's
    // `default` for authorization. What was unknown is which of the two usernames the broker
    // feeds to $client-username. Measured on spike/probe/$client-username/>, deliberately
    // OUTSIDE sm/v1 so acl-browser-baseline's blanket `sm/v1/>` exception cannot mask it.
    const before = await sessions();
    const c = await browser({ sub: 'grant-999', tenant: 'acme', scopes: ['stream'] });
    const s = newSession(before, await sessions());

    // No grant-999 object exists, so authorization falls to `default`...
    expect(s.clientUsername).toBe('default');
    expect(s.aclProfileName).toBe('acl-browser-baseline');
    // ...but the authentication username is still the token's own sub.
    expect(s.originalClientUsername).toBe('grant-999');

    const ownBranch = await trySubscribe(c, 'spike/probe/grant-999/x');
    const otherBranch = await trySubscribe(c, 'spike/probe/grant-111/x');
    const defaultBranch = await trySubscribe(c, 'spike/probe/default/x');
    console.log('unmatched-subject probe:', JSON.stringify(
      { session: s, ownBranch, otherBranch, defaultBranch }));

    // The substitution follows AUTHENTICATION, not authorization: an unprovisioned subject
    // gets a branch named after its own sub. Candidate (a) therefore needs no SEMP write per
    // grant — one profile on the VPN's `default` client username isolates every subject.
    expect(ownBranch, JSON.stringify(ownBranch)).toMatchObject({ outcome: 'allowed' });
    // The two controls that make "allowed" mean substitution rather than a permissive rule:
    // another grant's branch is refused...
    expect(otherBranch, JSON.stringify(otherBranch)).toMatchObject({
      outcome: 'acl-denied', name: 'AuthorizationError', code: 'SUBSCRIBE_ACL_DENIED',
    });
    // ...and so is the branch named after the AUTHORIZATION username this session resolved to,
    // which is what rules out "the broker substituted `default`".
    expect(defaultBranch, JSON.stringify(defaultBranch)).toMatchObject({
      outcome: 'acl-denied', name: 'AuthorizationError', code: 'SUBSCRIBE_ACL_DENIED',
    });

    await c.disconnect();
  }, 30000);

  it('candidate (a) grants stay subscribe-only: no publish exception exists on acl-subst', async () => {
    // Design §3 / the plan's global constraint: every browser-facing ACL profile keeps publish
    // default-disallow with NO publish exceptions. A publish denial is invisible to the client
    // (Task 1 finding 1), so this is asserted against the broker's config, which is where the
    // guarantee actually lives.
    const r = await fetch(
      'http://localhost:8080/SEMP/v2/config/msgVpns/' + VPN + '/aclProfiles/acl-subst',
      { headers: { Authorization: SEMP_AUTH } }
    );
    const profile = (await r.json()).data;
    expect(profile.publishTopicDefaultAction).toBe('disallow');
    expect(profile.subscribeTopicDefaultAction).toBe('disallow');

    const ex = await fetch(
      'http://localhost:8080/SEMP/v2/config/msgVpns/' + VPN
        + '/aclProfiles/acl-subst/publishTopicExceptions',
      { headers: { Authorization: SEMP_AUTH } }
    );
    expect((await ex.json()).data ?? []).toEqual([]);
  }, 30000);
});
