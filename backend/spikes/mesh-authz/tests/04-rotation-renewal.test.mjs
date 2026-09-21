// G1 Task 5 — JWKS rotation, key persistence, ticket renewal, and the ONE composition
// measurement Task 2 deliberately deferred. Throwaway spike test, NOT product code.
//
// Four things live here, and they are the last measurements Gate G1 needs:
//
//   1. THE COMPOSITION (deferred from Task 2, and candidate (a)'s last open cell). Task 2
//      measured the substitution mechanism and measured a substitution-only profile, but never
//      the two COMPOSED: the VPN's `default` client username bound to a profile whose only
//      exceptions are substitution-scoped. That composition is what makes candidate (a) cost
//      zero SEMP writes per grant, so it may not stay an inference. Measured here, with the
//      pre-PATCH state as its own control, and restored afterwards.
//
//   2. JWKS ROTATION. BE-D5 requires an overlap window: old and new keys published together
//      while the broker's cache catches up. Measured here — how long the broker takes to adopt
//      a new kid, what actually ends the wait, and whether evicting a key from JWKS revokes
//      the tokens it signed.
//
//   3. KEY PERSISTENCE. `BE-D5`: "A gateway restart must never invalidate every live ticket."
//      The issuer is killed and relaunched and a pre-restart token must still connect.
//
//   4. RENEWAL. BE-D6's chart seam depends on what a token renewal does to a live session.
//      Measured against the SDK's real renewal path, not a sketch of it.
//
// I2. Every broker fact asserted here was read out of the broker's OWN served SEMP 2.48 spec
// or measured against the running broker; every SDK fact was read out of the SDK's source in
// ~/Work/Development/RapidPilot/webAppSDK. Neither was recalled. In particular:
//   - `MsgVpnAuthenticationOauthProfile.endpointJwksRefreshInterval`, verbatim: "The number of
//     seconds between JWKS endpoint requests. … The default value is `86400`." The broker
//     rejects anything below 60 ("The provided number is smaller than the minimum value 60").
//   - `MsgVpnAuthenticationOauthProfile.disconnectOnTokenExpirationEnabled`, verbatim: "Enable
//     or disable the disconnection of clients when their tokens expire." Default `true`, and
//     it is `true` on this VPN.
//   - The SDK has NO public `updateAccessToken` on `SolaceClient`. It exists only on the
//     protocol adapter (`src/protocols/smf/smf-adapter.ts:733`) and is reached through the
//     `onTokenExpired` callback (`src/client.ts:183-207`). See the README deviation.
//
// FALSE-GREEN GUARDS (the R6 class), all of them live here:
//   1. `connectRetries: 0` on every connection. The SDK retries a refused login forever by
//      default (Task 3's finding), so a rotation negative would die on vitest's timeout rather
//      than on the broker's refusal — a hang is indistinguishable from a slow broker.
//   2. Every test that connects asserts the session's effective `clientUsername` and
//      `aclProfileName` from the SEMP monitor before believing any subscribe outcome. Without
//      it a subject that fell through to `default` would be judged against whatever profile
//      `default` happens to point at (Task 4's plant 4).
//   3. Every subscribe negative asserts `AuthorizationError` / `SUBSCRIBE_ACL_DENIED` and
//      every login negative asserts the measured refusal shape — never a bare rejection.
//   4. Polls CLASSIFY their outcome ('satisfied' / 'timed-out'), so a timeout can never be
//      read as a result.
//
// Deviations from the task text are listed in README "Deviations from the task text" / Task 5.

import { describe, it, expect, afterAll } from 'vitest';
import { spawn, execFileSync } from 'node:child_process';
// Task 1 deviation 2: the bare specifier resolves to the UMD build and imports as empty.
import SolaceWebAppSDK from 'solace-webapp-sdk/dist/solace-webapp-sdk.es.js';

const WSS = 'wss://localhost:9443/mesh'; // npm test sets NODE_TLS_REJECT_UNAUTHORIZED=0
const VPN = 'sm-spike';
const SEMP = 'http://localhost:8080/SEMP/v2/monitor';
const SEMP_CONFIG = 'http://localhost:8080/SEMP/v2/config';
const SEMP_AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');
const ISSUER = 'http://localhost:8090';
const SPIKE_ROOT = new URL('..', import.meta.url).pathname;

// Copied from 00/01/02/03 on purpose — the spike stays dependency-flat (task brief).
async function mint(claims) {
  const r = await fetch(`${ISSUER}/token`, { method: 'POST', body: JSON.stringify(claims) });
  return (await r.json()).token;
}

const connect = async (claims, { accessToken, auth } = {}) => SolaceWebAppSDK.connect({
  url: WSS, vpn: VPN, protocol: 'smf', connectRetries: 0,
  auth: auth ?? { type: 'oauth', accessToken: accessToken ?? await mint(claims) },
});

// `sm-internal` (basic auth, acl-internal) is the ONLY identity in the spike allowed to
// publish — design §3. Copied from 00/03; the renewal-gap measurement needs a real publisher.
const internal = () => SolaceWebAppSDK.connect({
  url: WSS, vpn: VPN, protocol: 'smf',
  auth: { type: 'basic', username: 'sm-internal', password: 'sm-internal' },
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Poll a predicate and CLASSIFY the outcome, so a timeout can never masquerade as a result.
async function waitFor(predicate, timeoutMs, stepMs = 100) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return 'satisfied';
    await sleep(stepMs);
  }
  return 'timed-out';
}

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

async function connectAndInspect(claims, opts) {
  const before = await sessions();
  const client = await connect(claims, opts);
  return { client, session: newSession(before, await sessions()) };
}

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

// Classify a LOGIN outcome, and prove a refusal left no session behind rather than merely
// being slow. Guard 1 + 3, and the same shape Task 4's file uses.
async function tryConnect(claims, opts) {
  const before = await sessions();
  try {
    const client = await connect(claims, opts);
    await client.disconnect();
    return { outcome: 'connected' };
  } catch (err) {
    const strays = [...(await sessions()).values()].filter((c) => !before.has(c.clientName));
    return {
      outcome: 'login-refused',
      name: err?.name, code: err?.code, message: err?.message,
      straySessions: strays,
    };
  }
}

async function sempPatch(path, body) {
  const r = await fetch(`${SEMP_CONFIG}/${path}`, {
    method: 'PATCH',
    headers: { Authorization: SEMP_AUTH, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const j = await r.json();
  return { code: j.meta.responseCode, error: j.meta.error };
}

async function sempGet(path) {
  const r = await fetch(`${SEMP_CONFIG}/${path}`, { headers: { Authorization: SEMP_AUTH } });
  return await r.json();
}

const issuerStats = async () => await (await fetch(`${ISSUER}/stats`)).json();
const rotateKeys = async () => await (await fetch(`${ISSUER}/rotate`, { method: 'POST' })).json();
const kidOf = (token) => JSON.parse(Buffer.from(token.split('.')[0], 'base64url').toString()).kid;

// THE mechanism that makes the broker re-read JWKS, measured rather than assumed: a login
// presenting a `kid` the broker does not hold. The broker then fetches the JWKS endpoint —
// but no more often than once per ~60 s, so an unknown kid can be refused for up to a minute
// before the fetch that would have accepted it happens. Nothing else the spike can reach
// triggers a re-read: there is no SEMP action for it (asserted below), and the background
// `endpointJwksRefreshInterval` is a day.
//
// Every poll below therefore PRESENTS a current-kid token on each attempt, which is both the
// stimulus and the observation. That is also what a real deployment does — after the gateway
// rotates, its next minted ticket is the unknown kid.
async function pokeUntil(currentToken, predicate, budgetMs) {
  const t0 = Date.now();
  while (Date.now() - t0 < budgetMs) {
    const poke = await tryConnect(null, { accessToken: currentToken });
    if (await predicate(poke)) return { outcome: 'satisfied', ms: Date.now() - t0 };
    await sleep(5000);
  }
  return { outcome: 'timed-out', ms: Date.now() - t0 };
}

const DENIED = { outcome: 'acl-denied', name: 'AuthorizationError', code: 'SUBSCRIBE_ACL_DENIED' };

// The composition test repoints the VPN's `default` client username. If an assertion throws
// mid-test the binding must still go back, or every later file measures the wrong profile.
let defaultAclRestored = true;
afterAll(async () => {
  if (!defaultAclRestored) {
    await sempPatch(`msgVpns/${VPN}/clientUsernames/default`,
      { aclProfileName: 'acl-browser-baseline' });
  }
});

describe('G1 Task 5 — composition, JWKS rotation, key persistence, renewal', () => {
  it('COMPOSITION (deferred from Task 2): default -> acl-subst isolates an UNPROVISIONED subject', async () => {
    // Candidate (a)'s zero-per-grant-write recommendation rests on exactly this binding, and
    // Task 2 left it labelled INFERRED because re-pointing `default` mid-task would have
    // changed the profile Task 1's baseline depends on. Measured here instead.
    //
    // `grant-777` is deliberately NOT provisioned. Asserted, not assumed — if a client-username
    // object existed the whole measurement would be about candidate (c) instead.
    const probe = await sempGet(`msgVpns/${VPN}/clientUsernames/grant-777`);
    expect(probe.meta.error?.status, JSON.stringify(probe.meta)).toBe('NOT_FOUND');

    const CLAIMS = { sub: 'grant-777', tenant: 'acme', scopes: ['stream'] };

    // ---- CONTROL, taken BEFORE the PATCH: the same subject on the CURRENT binding.
    // This is the built-in prove-it-fires. Under `acl-browser-baseline` the blanket `sm/v1/>`
    // exception allows everything, so if the PATCH below did nothing, the "denied" assertions
    // afterwards could not pass. Without this control, a green run would also be produced by
    // a profile that simply denies everything.
    {
      const { client, session } = await connectAndInspect(CLAIMS);
      expect(session.clientUsername).toBe('default');
      expect(session.originalClientUsername).toBe('grant-777');
      expect(session.aclProfileName).toBe('acl-browser-baseline');
      const wide = await trySubscribe(client, 'sm/v1/>');
      const other = await trySubscribe(client, 'sm/v1/acme/stream/grant-111/s1');
      console.log('composition CONTROL (default -> acl-browser-baseline):',
        JSON.stringify({ wide, other }));
      // Everything is readable. That is Task 1's baseline posture, and it is NOT isolation.
      expect(wide, JSON.stringify(wide)).toMatchObject({ outcome: 'allowed' });
      expect(other, JSON.stringify(other)).toMatchObject({ outcome: 'allowed' });
      await client.disconnect();
    }

    // ---- THE COMPOSITION: bind `default` to the substitution-only profile.
    defaultAclRestored = false;
    try {
      const patched = await sempPatch(`msgVpns/${VPN}/clientUsernames/default`,
        { aclProfileName: 'acl-subst' });
      expect(patched.code, JSON.stringify(patched)).toBe(200);

      const { client, session } = await connectAndInspect(CLAIMS);
      console.log('composition session:', JSON.stringify(session));
      // Authorization still falls through to `default` — the object does not exist...
      expect(session.clientUsername).toBe('default');
      expect(session.originalClientUsername).toBe('grant-777');
      // ...but `default` now carries the substitution-only profile, with NO blanket exception.
      expect(session.aclProfileName).toBe('acl-subst');

      const own = await trySubscribe(client, 'sm/v1/acme/stream/grant-777/s1');
      const other = await trySubscribe(client, 'sm/v1/acme/stream/grant-111/s1');
      const asDefault = await trySubscribe(client, 'sm/v1/acme/stream/default/s1');
      const wide = await trySubscribe(client, 'sm/v1/>');
      const notify = await trySubscribe(client, 'sm/v1/acme/notify/instance/i-1/health');
      // THE MULTI-TENANT QUESTION. Same session, same substituted username, OTHER tenant. The
      // exception `acl-subst` carries is `sm/v1/acme/stream/$client-username/>` — the tenant
      // level is a LITERAL, and only the username level substitutes. A VPN has exactly one
      // `default` client username, so under the zero-write recommendation there is exactly one
      // fallthrough profile carrying exactly one tenant literal — while BE-D2/design §4 put
      // MULTIPLE tenants on one dedicated VPN (Task 4 provisioned acme and globex here).
      // Whichever way this lands is recorded; it is not a pass/fail of the mechanism but the
      // measurement that prices the routes out of the constraint.
      const otherTenant = await trySubscribe(client, 'sm/v1/globex/stream/grant-777/s1');
      console.log('composition measured:',
        JSON.stringify({ own, other, asDefault, wide, notify, otherTenant }));

      // THE RESULT: an unprovisioned subject gets a branch named after its own token `sub`,
      // on a profile nobody wrote for it. Zero SEMP writes per grant.
      expect(own, JSON.stringify(own)).toMatchObject({ outcome: 'allowed' });
      // Control A — another grant's branch is refused, so "allowed" is substitution and not
      // a permissive rule.
      expect(other, JSON.stringify(other)).toMatchObject(DENIED);
      // Control B — the branch named after the AUTHORIZATION username is refused, which rules
      // out "the broker substituted `default`" (Task 2's finding, re-confirmed on the profile
      // the recommendation actually uses).
      expect(asDefault, JSON.stringify(asDefault)).toMatchObject(DENIED);
      // Control C — no blanket escape hatch on this profile.
      expect(wide, JSON.stringify(wide)).toMatchObject(DENIED);

      // THE COST, measured rather than warned about in prose: BE-D5's thin-notify tier is
      // NOT reachable from this profile. A stream-only exception set is not shippable as the
      // fallback — the console needs notify too, and design §4's notify topics have no grant
      // level to substitute at. This denial IS the finding.
      expect(notify, JSON.stringify(notify)).toMatchObject(DENIED);

      // THE MULTI-TENANT COST, same treatment. The substitution does NOT rescue the tenant
      // level: this session's own substituted branch under a different tenant is refused,
      // because `acme` is a literal in the exception and nothing substitutes it. So one
      // substitution profile serves exactly ONE tenant, and a VPN has exactly one `default`
      // client username to hang it on. The zero-broker-write claim is therefore measured
      // SINGLE-TENANT; extending it across tenants costs one of three priced routes (see
      // docs/design/backend/spike-authz-result.md). Good news inside the bad: the tenant edge
      // IS still broker-enforced here — it is enforced by the literal, not by the substitution.
      expect(otherTenant, JSON.stringify(otherTenant)).toMatchObject(DENIED);

      expect(client.isConnected()).toBe(true);
      await client.disconnect();
    } finally {
      const back = await sempPatch(`msgVpns/${VPN}/clientUsernames/default`,
        { aclProfileName: 'acl-browser-baseline' });
      expect(back.code, JSON.stringify(back)).toBe(200);
      defaultAclRestored = true;
    }

    // Restoration is asserted, not hoped for: every other file in this suite assumes
    // `default` -> acl-browser-baseline (Task 1 finding 3).
    const after = await sempGet(`msgVpns/${VPN}/clientUsernames/default`);
    expect(after.data.aclProfileName).toBe('acl-browser-baseline');
  }, 60000);

  it('SEMP exposes NO force-refresh action for JWKS — a profile config change is the only lever', async () => {
    // Design §16 question 1 territory and an install-runbook fact: if a signing key has to be
    // revoked NOW, what can an operator actually do? Read out of the broker's own served
    // ACTION spec rather than recalled.
    const r = await fetch('http://localhost:8080/SEMP/v2/action/spec',
      { headers: { Authorization: SEMP_AUTH } });
    const paths = Object.keys((await r.json()).paths);

    const oauthActions = paths.filter((p) => p.toLowerCase().includes('oauth'));
    console.log('SEMP action paths mentioning oauth:', JSON.stringify(oauthActions));

    // The only OAuth-profile actions the broker offers are stats resets. There is no
    // `refreshJwks`, no `refreshKeys` — contrast `/certAuthorities/{n}/refreshCrl`, which
    // exists and proves the broker DOES model "refresh this cached artefact now" as an action
    // where it wants to. Its absence for JWKS is therefore a deliberate gap, not an oversight
    // in the search.
    expect(paths.filter((p) => /refreshJwks|refreshKeys|jwks/i.test(p))).toEqual([]);
    expect(paths).toContain('/certAuthorities/{certAuthorityName}/refreshCrl');
    expect(oauthActions.every((p) => p.endsWith('clearStats')
      || p === '/msgVpns/{msgVpnName}/authenticationOauthProfiles'
      || p === '/msgVpns/{msgVpnName}/authenticationOauthProfiles/{oauthProfileName}'
      || p === '/msgVpns/{msgVpnName}/authenticationOauthProviders'
      || p === '/msgVpns/{msgVpnName}/authenticationOauthProviders/{oauthProviderName}'
      || p === '/oauthProfiles' || p === '/oauthProfiles/{oauthProfileName}'),
      JSON.stringify(oauthActions)).toBe(true);

    // And the background interval is a day, with a hard floor of a minute. Both read from the
    // config spec / the broker's own rejection, so BE-D5's overlap window has a real number
    // under it rather than "the broker's cache window".
    const live = await sempGet(
      `msgVpns/${VPN}/authenticationOauthProfiles/sm_spike_oauth?select=endpointJwksRefreshInterval`);
    expect(live.data.endpointJwksRefreshInterval).toBe(86400);
    const tooLow = await sempPatch(`msgVpns/${VPN}/authenticationOauthProfiles/sm_spike_oauth`,
      { endpointJwksRefreshInterval: 10 });
    console.log('endpointJwksRefreshInterval=10 ->', JSON.stringify(tooLow));
    expect(tooLow.code).toBe(400);
    expect(tooLow.error.description).toContain('smaller than the minimum value 60');
  }, 30000);

  it('ROTATION: a new kid is adopted only when the broker re-reads JWKS; the overlap keeps old tokens alive', async () => {
    // BE-D5's overlap window, measured in both directions. The two directions are NOT
    // symmetric, and that asymmetry is the finding.
    const before = await issuerStats();
    const oldToken = await mint({ sub: 'tenant-acme', tenant: 'acme', expiresIn: '45m' });
    expect(kidOf(oldToken)).toBe(before.kid);
    // Baseline: the current key works, so anything refused below is the rotation and not a
    // broken harness.
    expect((await tryConnect(null, { accessToken: oldToken })).outcome).toBe('connected');

    // ---- Rotation 1: publish a second key and sign with it. `oldToken`'s kid stays published.
    const r1 = await rotateKeys();
    console.log('rotation 1:', JSON.stringify(r1));
    expect(r1.kids).toHaveLength(2);
    expect(r1.kids).toContain(before.kid);
    const newToken = await mint({ sub: 'tenant-acme', tenant: 'acme', expiresIn: '45m' });
    expect(kidOf(newToken)).toBe(r1.kid);

    // ADOPTION LATENCY, measured. Each attempt presents the new kid, which is itself the
    // stimulus; the number recorded is how long the broker made a legitimately-minted ticket
    // wait. Budget is 90 s because the observed floor between JWKS fetches is ~60 s.
    const fetchesBefore = (await issuerStats()).jwksFetchCount;
    const adopted = await pokeUntil(newToken, (poke) => poke.outcome === 'connected', 90000);
    const fetchesAfter = await issuerStats();
    console.log(`rotation ADOPTION: new kid ${r1.kid} ${adopted.outcome} after ${adopted.ms}ms; `
      + `issuer JWKS fetches ${fetchesBefore} -> ${fetchesAfter.jwksFetchCount}, `
      + `last at ${fetchesAfter.jwksFetches.at(-1)?.at}`);
    expect(adopted.outcome, 'the broker never adopted the new signing key').toBe('satisfied');
    // The broker really re-read the endpoint — the acceptance is not the old key being reused.
    expect(fetchesAfter.jwksFetchCount).toBeGreaterThan(fetchesBefore);

    // THE OVERLAP, as a measurement: the broker has re-read a JWKS that still publishes the
    // old kid, and a token signed with it is STILL accepted. This is the property BE-D5's
    // overlap window is for, and without it a rotation would strand every live ticket.
    const oldDuringOverlap = await tryConnect(null, { accessToken: oldToken });
    console.log('old-key token DURING the overlap:', JSON.stringify(oldDuringOverlap));
    expect(oldDuringOverlap.outcome, JSON.stringify(oldDuringOverlap)).toBe('connected');

    // ---- Rotation 2 evicts the old kid. The overlap is over; the token must die.
    const r2 = await rotateKeys();
    console.log('rotation 2:', JSON.stringify(r2));
    expect(r2.kids).not.toContain(kidOf(oldToken));
    const evictedToken = await mint({ sub: 'tenant-acme', tenant: 'acme' });
    expect(kidOf(evictedToken)).toBe(r2.kid);

    // REVOCATION LATENCY, and note WHAT drives it. The evicted key is still in the broker's
    // cache, and a token signed with it still SUCCEEDS — so it generates no unknown-kid
    // stimulus and would never prompt a re-read on its own. What ends its life is other
    // traffic: the first login carrying the post-rotation kid. Each poll iteration therefore
    // presents the new kid (the poke) and then re-tests the evicted one.
    const died = await pokeUntil(
      evictedToken,
      async () => (await tryConnect(null, { accessToken: oldToken })).outcome === 'login-refused',
      120000);
    const finalRefusal = await tryConnect(null, { accessToken: oldToken });
    console.log(`old-key token AFTER eviction: ${died.outcome} after ${died.ms}ms — `
      + JSON.stringify(finalRefusal));
    expect(died.outcome, JSON.stringify(finalRefusal)).toBe('satisfied');
    // The right red, measured not assumed: an unknown signing key produces the SAME shape as
    // an expired ticket (Task 4's fourth refusal shape) — 401 / AuthenticationError, carrying
    // no reason. A client cannot tell "your key was rotated out" from "your ticket expired".
    expect(finalRefusal.name).toBe('AuthenticationError');
    expect(finalRefusal.code).toBe('AUTH_FAILED');
    expect(finalRefusal.message).toContain('Unauthorized');
    // A refusal that still produced a session would mean the broker let it in.
    expect(finalRefusal.straySessions, JSON.stringify(finalRefusal.straySessions)).toEqual([]);

    // ...and the current key still works, so the suite is left in a usable state and the
    // refusal above means "this key was evicted" rather than "OAuth is broken now".
    const current = await tryConnect({ sub: 'tenant-acme', tenant: 'acme' });
    expect(current.outcome, JSON.stringify(current)).toBe('connected');
  }, 300000);

  it('KEY PERSISTENCE: an issuer restart keeps the kid, and a pre-restart token still connects', async () => {
    // BE-D5, verbatim: "A gateway restart must never invalidate every live ticket." The spike
    // issuer persists to issuer/keys.json; this is the test that the persistence is real and
    // survives the ROTATED state rather than only the first-boot state.
    const before = await issuerStats();
    const token = await mint({ sub: 'tenant-acme', tenant: 'acme' });
    expect(kidOf(token)).toBe(before.kid);
    expect((await tryConnect(null, { accessToken: token })).outcome).toBe('connected');

    // Kill it and bring it back. `pkill -f` matches the issuer's own command line only; the
    // vitest process is `node .../vitest`.
    try {
      execFileSync('pkill', ['-f', 'issuer/issuer.mjs']);
    } catch {
      // pkill exits 1 when nothing matched — that would itself be a failure, caught below.
    }
    // Liveness is probed on /stats, never on /jwks.json: the issuer counts every JWKS fetch,
    // and a probe that hit that endpoint would spend the very counter the assertion below
    // reads. Measurement scaffolding must not perturb the measurement.
    const alive = async () => await fetch(`${ISSUER}/stats`).then((r) => r.ok).catch(() => false);
    const down = await waitFor(async () => !(await alive()), 10000, 200);
    expect(down, 'the issuer never went down, so this test would prove nothing').toBe('satisfied');

    spawn('node', ['issuer/issuer.mjs'],
      { cwd: SPIKE_ROOT, detached: true, stdio: 'ignore' }).unref();
    const up = await waitFor(alive, 20000, 200);
    expect(up, 'the issuer did not come back').toBe('satisfied');

    // Same key material, reloaded from keys.json — not a fresh keypair.
    const after = await issuerStats();
    console.log('issuer restart:', JSON.stringify({ before: { kid: before.kid, kids: before.kids },
      after: { kid: after.kid, kids: after.kids } }));
    expect(after.kid).toBe(before.kid);
    expect(after.kids).toEqual(before.kids);
    // The counters reset with the process; that is how we know it really is a new process and
    // not a request that happened to hit the old one.
    expect(after.jwksFetchCount).toBe(0);

    // THE ASSERTION BE-D5 CARES ABOUT: a ticket minted before the restart still logs in. Note
    // that nothing was re-fetched to make this work — the broker's cached key is unchanged
    // because the issuer republished the identical JWK set.
    const stillGood = await tryConnect(null, { accessToken: token });
    expect(stillGood.outcome, JSON.stringify(stillGood)).toBe('connected');
    // And the restarted issuer signs with the same key, so new tickets work too.
    const fresh = await tryConnect({ sub: 'tenant-acme', tenant: 'acme' });
    expect(fresh.outcome, JSON.stringify(fresh)).toBe('connected');
  }, 120000);

  it('RENEWAL is a reconnect: the broker session is REPLACED, and the SDK does not tell the app', async () => {
    // The SDK has no public updateAccessToken (see the header note). The real renewal path is
    // the `onTokenExpired` callback, and this test drives exactly that path: a 70 s ticket with
    // refreshBeforeExpirySeconds 60 fires the renewal about 10 s after connect.
    const before = await sessions();
    let renewed = 0, disconnects = 0, reconnects = 0, minted = 0;
    const client = await SolaceWebAppSDK.connect({
      url: WSS, vpn: VPN, protocol: 'smf', connectRetries: 0,
      auth: {
        type: 'oauth',
        accessToken: await mint({ sub: 'tenant-acme', tenant: 'acme', expiresIn: '70s' }),
        refreshBeforeExpirySeconds: 60,
        onTokenExpired: async () => {
          minted++;
          return await mint({ sub: 'tenant-acme', tenant: 'acme', expiresIn: '15m' });
        },
      },
    });
    client.on('auth:tokenRefreshed', () => renewed++);
    client.on('disconnected', () => disconnects++);
    client.on('reconnected', () => reconnects++);

    const first = newSession(before, await sessions());
    expect(first.clientUsername).toBe('tenant-acme');
    expect(first.aclProfileName).toBe('acl-acme');

    // A live subscription, and a numbered stream through it, so the renewal's effect on
    // DELIVERY is measured rather than assumed. Await the subscribe before publishing — the
    // broker confirms subscriptions asynchronously (Task 1 deviation 7).
    const heard = [];
    await client.subscribe('sm/v1/acme/stream/renewal/s1',
      (m) => heard.push({ seq: m.payload?.seq, at: Date.now() }));
    const pub = await internal();
    // 10 ms cadence: the reconnect has to be measured against a stream fine-grained enough to
    // resolve it. At 50 ms this run lost nothing at all, which says more about the sampling
    // rate than about the seam.
    let seq = 0, publishing = true;
    const pump = (async () => {
      while (publishing) {
        await pub.publish('sm/v1/acme/stream/renewal/s1', { seq: seq++ });
        await sleep(10);
      }
    })();

    const fired = await waitFor(async () => renewed > 0, 45000, 250);
    // Let the restored subscription settle so the "after" side of the gap is real traffic.
    await sleep(2000);
    publishing = false;
    await pump;

    const after = await sessions();
    const secondName = [...after.values()]
      .filter((c) => c.originalClientUsername === 'tenant-acme' && !before.has(c.clientName))
      .map((c) => c.clientName);

    const got = heard.filter((h) => typeof h.seq === 'number').sort((a, b) => a.seq - b.seq);
    const received = got.map((h) => h.seq);
    // Missing sequence numbers = messages published while the session was being replaced.
    const missing = [];
    for (let i = 1; i < received.length; i++) {
      for (let s = received[i - 1] + 1; s < received[i]; s++) missing.push(s);
    }
    // And the OUTAGE itself, in milliseconds: the largest hole between two consecutive
    // arrivals. On a 10 ms pump anything much above ~10 ms is the reconnect.
    let outageMs = 0, outageAt = null;
    for (let i = 1; i < got.length; i++) {
      const d = got[i].at - got[i - 1].at;
      if (d > outageMs) { outageMs = d; outageAt = [got[i - 1].seq, got[i].seq]; }
    }
    console.log('renewal measured:', JSON.stringify({
      renewedEvents: renewed, mintCallbacks: minted,
      appVisibleDisconnects: disconnects, appVisibleReconnects: reconnects,
      firstClientName: first.clientName, clientNamesAfter: secondName,
      published: seq, received: received.length,
      firstSeq: received[0], lastSeq: received.at(-1),
      missingCount: missing.length, missing: missing.slice(0, 20),
      longestArrivalGapMs: outageMs, aroundSeqs: outageAt,
    }));

    expect(fired, 'the SDK never renewed the token').toBe('satisfied');
    expect(renewed).toBe(1);
    expect(minted).toBe(1);

    // THE MECHANISM: renewal is a reconnect. The broker session that was alive before the
    // renewal is gone and a DIFFERENT one is serving the same client — read from the broker,
    // not from an SDK event, because the SDK does not report it.
    expect(after.has(first.clientName),
      `the pre-renewal session ${first.clientName} survived — renewal was not a reconnect`).toBe(false);
    expect(secondName.length, JSON.stringify(secondName)).toBe(1);
    expect(secondName[0]).not.toBe(first.clientName);

    // THE CONSEQUENCE FOR BE-D6's CHART SEAM: the subscription is restored by the SDK and
    // traffic resumes, but the reconnect is a hole in the stream. Both halves asserted.
    expect(received.length, 'nothing was received at all — the pump or the subscription is broken')
      .toBeGreaterThan(0);
    expect(received.at(-1), 'no traffic arrived AFTER the renewal, so the restore did not work')
      .toBeGreaterThan(received[0]);
    // THE HOLE, as a measurement rather than a warning: messages published while the session
    // was being replaced are simply gone. Direct messaging has no replay, so a data-bearing
    // chart tail MUST re-backfill across a renewal — the SDK's subscription restore brings the
    // subscription back, never the messages.
    //
    // Reproducibility, because a loss assertion has to be honest about its own flakiness:
    // measured 2 lost on each of three consecutive runs at this 10 ms cadence, with the
    // longest arrival gap (~30 ms) landing on the same seams. At the 50 ms cadence this test
    // first used, ZERO were lost — the outage is real but short, so the sampling rate has to
    // resolve it. If this ever goes green-with-zero, the outage got faster, not absent: the
    // session-replacement assertion above is the one that cannot be sampled away.
    expect(missing.length,
      `no message was lost across the renewal (published ${seq}, received ${received.length}) — `
      + 'either the reconnect outage is now shorter than the 10ms pump, or the pump stalled')
      .toBeGreaterThan(0);

    // THE OBSERVABILITY FINDING: the app is told 'auth:tokenRefreshed' and nothing else. No
    // 'disconnected', no 'reconnected' — even though the broker session really was replaced.
    // A console cannot detect the seam from SDK events; it has to re-backfill on renewal by
    // policy, on its own clock.
    expect(disconnects).toBe(0);

    publishing = false;
    await Promise.all([client.disconnect(), pub.disconnect()]);
  }, 120000);

  it('the broker DISCONNECTS a live session when its ticket expires — renewal is a hard deadline', async () => {
    // `disconnectOnTokenExpirationEnabled` is `true` by default and true on this VPN, so
    // BE-D5's "renewable over REST" is not a convenience: miss the deadline and the mesh
    // session is gone, mid-chart. Measured rather than read off the property description.
    const live = await sempGet(
      `msgVpns/${VPN}/authenticationOauthProfiles/sm_spike_oauth?select=disconnectOnTokenExpirationEnabled`);
    expect(live.data.disconnectOnTokenExpirationEnabled).toBe(true);

    const lifetimeSec = 30;
    const { client, session } = await connectAndInspect(
      { sub: 'tenant-acme', tenant: 'acme', expiresIn: `${lifetimeSec}s` });
    expect(session.aclProfileName).toBe('acl-acme');
    await client.subscribe('sm/v1/acme/stream/expiry/s1', () => {});
    const t0 = Date.now();

    const gone = await waitFor(async () => !(await sessions()).has(session.clientName),
      lifetimeSec * 1000 + 60000, 1000);
    const elapsed = Math.round((Date.now() - t0) / 1000);
    console.log(`session dropped at ticket expiry: ${gone} after ~${elapsed}s `
      + `(ticket lifetime ${lifetimeSec}s)`);
    expect(gone, `the session outlived its ticket by more than 60s`).toBe('satisfied');
    // It really was expiry and not an early failure: the session must survive well past the
    // connect and die only near `exp`. Without this the test would also pass if the broker had
    // refused the login outright.
    expect(elapsed).toBeGreaterThanOrEqual(lifetimeSec - 5);

    await client.disconnect().catch(() => {});
  }, 180000);
});
