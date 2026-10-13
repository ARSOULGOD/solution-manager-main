// G1 candidate (b) — authorization groups from token claims. Throwaway spike test, NOT
// product code.
//
// The question BE-D5 needs answered: can the OAuth profile's groups-claim mapping bind a token
// to a broker AUTHORIZATION GROUP that carries an ACL profile — i.e. authorize by claim rather
// than by client-username object?
//
// I2. Both SEMP halves were read out of the broker's own served SEMP 2.48 spec before being
// scripted (see provision.sh's Task 3 block for the fetch and the verbatim property text):
//   /msgVpns/{v}/authorizationGroups — MsgVpnAuthorizationGroup{authorizationGroupName,
//     aclProfileName, clientProfileName, enabled, orderBefore…, orderAfter…}
//   MsgVpnAuthenticationOauthProfile.authorizationGroupsClaimName — "If non-empty, the
//     specified claim will be used to determine groups for authorization."
// Solace's docs state the default claim is `groups` and that claim values must be strings or
// arrays of strings, but say NOTHING about what happens when the claim is absent or matches no
// configured group. Tests 4 and 5 below measure those instead of asserting a remembered answer.
//
// TWO OAUTH PROFILES, and the reason matters for reading every assertion here.
// authorizationGroupsClaimName is a per-PROFILE switch with no per-token opt-out, so turning it
// on for `sm_spike_oauth` refuses every token Tasks 1-2 mint (measured: 3 of 8 tests fail).
// Candidate (b) therefore runs on `sm_spike_oauth_b`, selected per connection by the
// client-supplied `issuerIdentifier`. Test 3 is the control that proves the selection is real
// and that Task 1-2's profile is genuinely unaffected.
//
// FALSE-GREEN GUARDS (the R6 class). Two hazards are specific to this candidate:
//   1. `acl-browser-baseline` carries a blanket `sm/v1/>` subscribe exception, so a session that
//      silently fell through to the VPN's `default` client username would be allowed EVERYTHING
//      under sm/v1 — the alpha positive would pass for entirely the wrong reason. Every test
//      that connects therefore asserts the session's effective `clientUsername` and
//      `aclProfileName` from the SEMP monitor (Task 2 test 5's pattern) before believing any
//      subscribe outcome, and the negatives include `sm/v1/acme/notify/…`, which only the
//      blanket profile would allow.
//   2. A bare `rejects.toThrow()` is also what a timeout or a dropped session produces. Every
//      subscribe negative asserts `AuthorizationError` / `SUBSCRIBE_ACL_DENIED`, and every login
//      negative asserts the broker's own 403 text.
//
// Deviations from the task text are listed in README "Deviations from the task text" / Task 3.

import { describe, it, expect } from 'vitest';
// Task 1 deviation 2: the bare specifier resolves to the UMD build and imports as empty.
import SolaceWebAppSDK from 'solace-webapp-sdk/dist/solace-webapp-sdk.es.js';

const WSS = 'wss://localhost:9443/mesh'; // npm test sets NODE_TLS_REJECT_UNAUTHORIZED=0
const VPN = 'sm-spike';
const SEMP = 'http://localhost:8080/SEMP/v2/monitor';
const SEMP_CONFIG = 'http://localhost:8080/SEMP/v2/config';
const SEMP_AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');

// Candidate (b)'s OAuth profile selector — the value provision.sh puts in that profile's
// `issuer` field. NOT the `iss` claim: both profiles still require iss=sm-spike-issuer.
const PROFILE_B = 'sm-spike-oauth-b';

// Copied from 00-connect.test.mjs / 01-substitution.test.mjs on purpose — the spike stays
// dependency-flat (task brief). Extended in Task 3: the issuer now signs the whole body, so
// `groups` reaches the token.
async function mint(claims) {
  const r = await fetch('http://localhost:8090/token', { method: 'POST', body: JSON.stringify(claims) });
  return (await r.json()).token;
}

// `connectRetries: 0` is what makes the login negatives deterministic. Left at the default the
// SDK retries a 403 roughly once a second forever, so connect() never settles and the test dies
// on vitest's timeout — which is indistinguishable from a hung broker. With retries off the
// rejection carries the broker's own refusal text, which is what tests 4/5 assert.
const connect = async (claims, { issuerIdentifier } = {}) => {
  const auth = { type: 'oauth', accessToken: await mint(claims) };
  if (issuerIdentifier) auth.issuerIdentifier = issuerIdentifier;
  return SolaceWebAppSDK.connect({ url: WSS, vpn: VPN, protocol: 'smf', connectRetries: 0, auth });
};

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

// Connect and report the session the broker created for it, so a test can assert WHICH
// authorization path won before it believes any subscribe outcome.
async function connectAndInspect(claims, opts) {
  const before = await sessions();
  const client = await connect(claims, opts);
  return { client, session: newSession(before, await sessions()) };
}

// Subscribe and classify the outcome, so a test can assert WHY it failed rather than THAT it
// failed. Copied from 01-substitution.test.mjs — helpers are copied per file on purpose.
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
// behind rather than merely being slow.
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
      // A refusal that still produced a session would mean the broker let it in and something
      // else failed; that must not be read as "authorization refused".
      straySessions: appeared,
    };
  }
}

const ALPHA = 'sm/v1/acme/stream/alpha/s1';
const BETA = 'sm/v1/acme/stream/beta/s1';
// Only `acl-browser-baseline`'s blanket `sm/v1/>` exception allows this one. It is the canary
// for "the session quietly fell through to the VPN's default client username".
const NOTIFY = 'sm/v1/acme/notify/instance/i-1/health';

describe('G1 candidate (b) — authorization groups from token claims', () => {
  it('candidate b: groups claim grants exactly the group ACL', async () => {
    // `op-2` has NO client-username object, so under Task 1 finding 3 it would authorize as the
    // VPN's `default`. The groups claim is the only thing that can put it on acl-alpha.
    const { client: c, session: s } = await connectAndInspect(
      { sub: 'op-2', tenant: 'acme', scopes: ['stream'], groups: ['g-stream-alpha'] },
      { issuerIdentifier: PROFILE_B }
    );
    console.log('candidate (b) session:', JSON.stringify(s));

    // THE mechanism assertion. Without it the subscribe outcomes below could be produced by
    // acl-browser-baseline's blanket exception instead.
    expect(s.aclProfileName).toBe('acl-alpha');
    // Note the delta from Task 1 finding 3: the group binding also supplies the AUTHORIZATION
    // username, so this is `op-2` and not `default`.
    expect(s.clientUsername).toBe('op-2');
    expect(s.originalClientUsername).toBe('op-2');

    // subscribe() is declared Promise<void>, so it resolves to undefined — the task text's
    // `.resolves.toBeDefined()` could never pass. Resolution IS the broker's SUBSCRIPTION_OK.
    await expect(c.subscribe(ALPHA, () => {})).resolves.toBeUndefined();

    const beta = await trySubscribe(c, BETA);
    expect(beta, JSON.stringify(beta)).toMatchObject({
      outcome: 'acl-denied', name: 'AuthorizationError', code: 'SUBSCRIBE_ACL_DENIED',
    });

    // The canary: acl-alpha's ONE exception is the alpha branch, so the notify tier is refused
    // too. If this were allowed, the session would be on the blanket baseline profile.
    const notify = await trySubscribe(c, NOTIFY);
    expect(notify, JSON.stringify(notify)).toMatchObject({
      outcome: 'acl-denied', name: 'AuthorizationError', code: 'SUBSCRIBE_ACL_DENIED',
    });

    expect(c.isConnected()).toBe(true);
    await c.disconnect();
  }, 30000);

  it('precedence: a pre-created client username BEATS the groups claim', async () => {
    // The precedence question BE-D5 has to know the answer to before it can combine candidates.
    // Same profile, same valid group, but `grant-111` IS a client-username object (Task 2), and
    // it wins: the session lands on acl-subst and never sees acl-alpha at all.
    const { client: c, session: s } = await connectAndInspect(
      { sub: 'grant-111', tenant: 'acme', scopes: ['stream'], groups: ['g-stream-alpha'] },
      { issuerIdentifier: PROFILE_B }
    );
    console.log('precedence session:', JSON.stringify(s));

    expect(s.aclProfileName).toBe('acl-subst');
    expect(s.clientUsername).toBe('grant-111');

    // Candidate (a)'s substitution branch still works...
    await expect(c.subscribe('sm/v1/acme/stream/grant-111/s1', () => {})).resolves.toBeUndefined();
    // ...and the group's branch is refused, which is what makes "acl-subst won" a measurement
    // of enforcement rather than of a monitor field.
    const alpha = await trySubscribe(c, ALPHA);
    expect(alpha, JSON.stringify(alpha)).toMatchObject({
      outcome: 'acl-denied', name: 'AuthorizationError', code: 'SUBSCRIBE_ACL_DENIED',
    });

    await c.disconnect();
  }, 30000);

  it('coexistence control: the SAME token on the default OAuth profile ignores `groups`', async () => {
    // Identical claims to test 1, no issuerIdentifier — so the VPN's
    // authenticationOauthDefaultProfileName (`sm_spike_oauth`, groups mapping OFF) handles it.
    // Two things at once: it proves the profile selector is what did the work in test 1, and it
    // proves Task 1-2's profile still behaves exactly as they measured it.
    const { client: c, session: s } = await connectAndInspect(
      { sub: 'op-2', tenant: 'acme', scopes: ['stream'], groups: ['g-stream-alpha'] }
    );
    console.log('default-profile control session:', JSON.stringify(s));

    expect(s.aclProfileName).toBe('acl-browser-baseline');
    expect(s.clientUsername).toBe('default');           // Task 1 finding 3, unchanged
    expect(s.originalClientUsername).toBe('op-2');

    // And the blanket exception is exactly the hazard the guards above exist for: on THIS
    // profile the same token reaches the beta branch it was denied in test 1.
    await expect(c.subscribe(BETA, () => {})).resolves.toBeUndefined();

    await c.disconnect();
  }, 30000);

  it('the groups claim is MANDATORY on a profile that enables it — absent or empty is refused', async () => {
    // This is candidate (b)'s coexistence cost, measured rather than argued. The docs do not
    // state it; the broker does.
    for (const claims of [
      { sub: 'op-6', tenant: 'acme', scopes: ['stream'] },              // claim absent
      { sub: 'op-4', tenant: 'acme', scopes: ['stream'], groups: [] },  // claim present, empty
    ]) {
      const r = await tryConnect(claims, { issuerIdentifier: PROFILE_B });
      expect(r.outcome, JSON.stringify(r)).toBe('login-refused');
      expect(r.straySessions, JSON.stringify(r.straySessions)).toEqual([]);
      // The right red: the broker's own authorization-group refusal, not a TLS or JWKS failure.
      expect(r.message).toContain('No Authorization Groups were retrieved from the Authorizing Server');
      expect(r.message).toContain("responseCode: 403");
      expect(r.message).toContain("errorSubcode: 'LOGIN_FAILURE (13)'");
    }

    // Control: the SAME claim-less token connects fine on the default profile, so what refused
    // it above is the groups mapping and nothing else about the token.
    const control = await tryConnect({ sub: 'op-6', tenant: 'acme', scopes: ['stream'] });
    expect(control.outcome, JSON.stringify(control)).toBe('connected');
  }, 30000);

  it('an unknown group name FAILS CLOSED at login — it does not fall back to the client username', async () => {
    // The operationally important one. A token naming a group the broker does not have is not
    // downgraded to `default`; the whole connection is refused, with a DIFFERENT broker message
    // from the absent-claim case above. Deleting or disabling an authorization group therefore
    // locks out every holder of a live token that names it.
    const r = await tryConnect(
      { sub: 'op-3', tenant: 'acme', scopes: ['stream'], groups: ['g-nope'] },
      { issuerIdentifier: PROFILE_B }
    );
    console.log('unknown-group login:', JSON.stringify({ outcome: r.outcome, message: r.message }));
    expect(r.outcome, JSON.stringify(r)).toBe('login-refused');
    expect(r.straySessions, JSON.stringify(r.straySessions)).toEqual([]);
    expect(r.message).toContain(
      'No matching configured Authorization Group was found (or all such matching groups are disabled)'
    );
    expect(r.message).toContain("errorSubcode: 'LOGIN_FAILURE (13)'");

    // Control: identical token, default profile — connects, because the claim is simply ignored
    // there. So "refused" above is the group lookup, not a malformed claim.
    const control = await tryConnect({ sub: 'op-3', tenant: 'acme', scopes: ['stream'], groups: ['g-nope'] });
    expect(control.outcome, JSON.stringify(control)).toBe('connected');
  }, 30000);

  it('a STRING groups claim works under authorizationGroupsClaimStringFormat "single"', async () => {
    // The spec says the format setting applies "when the claim is a string": "single" - the
    // whole string is one group. provision.sh sets it explicitly, so this asserts provisioned
    // config rather than a default, and it confirms the spec text on this broker.
    const { client: c, session: s } = await connectAndInspect(
      { sub: 'op-5', tenant: 'acme', scopes: ['stream'], groups: 'g-stream-alpha' },
      { issuerIdentifier: PROFILE_B }
    );
    expect(s.aclProfileName).toBe('acl-alpha');
    await expect(c.subscribe(ALPHA, () => {})).resolves.toBeUndefined();
    await c.disconnect();
  }, 30000);

  it('candidate (b) grants stay subscribe-only: no publish exception exists on acl-alpha', async () => {
    // Design §3 / the plan's global constraint: every browser-facing ACL profile keeps publish
    // default-disallow with NO publish exceptions. A publish denial is invisible to the client
    // (Task 1 finding 1), so this is asserted against the broker's config, which is where the
    // guarantee actually lives.
    const r = await fetch(`${SEMP_CONFIG}/msgVpns/${VPN}/aclProfiles/acl-alpha`,
      { headers: { Authorization: SEMP_AUTH } });
    const profile = (await r.json()).data;
    expect(profile.publishTopicDefaultAction).toBe('disallow');
    expect(profile.subscribeTopicDefaultAction).toBe('disallow');

    const ex = await fetch(`${SEMP_CONFIG}/msgVpns/${VPN}/aclProfiles/acl-alpha/publishTopicExceptions`,
      { headers: { Authorization: SEMP_AUTH } });
    expect((await ex.json()).data ?? []).toEqual([]);

    // ...and the authorization group really is bound to it, so the profile asserted here is the
    // profile the group hands out.
    const g = await fetch(`${SEMP_CONFIG}/msgVpns/${VPN}/authorizationGroups/g-stream-alpha`,
      { headers: { Authorization: SEMP_AUTH } });
    expect((await g.json()).data).toMatchObject({
      authorizationGroupName: 'g-stream-alpha', aclProfileName: 'acl-alpha', enabled: true,
    });
  }, 30000);
});
