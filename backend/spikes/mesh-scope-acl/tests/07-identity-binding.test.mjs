// Round 2 (2026-09-30) — IDENTITY BINDING and ORGANIZATION BINDING. Throwaway spike test, NOT
// product code. Closes the round-2 review's BLOCKER and MAJOR:
//
//  BLOCKER  The round-1 issuer only rejected CHARACTERS in `sub`. A `sub` equal to a NON-browser
//           client username ('sm-internal-orgA', 'orgA-su1-dev-ctl', 'orgA-abl-*') passed the
//           rule, and because every provisioned client username is reachable over OAuth (Solace
//           documents no way to restrict a client username to one authentication scheme) the
//           ticket became an INTERNAL identity: subscribe sm/v1/>, publish anywhere, bind queues.
//           The fix has two halves, and this file measures both plus the reason each is needed:
//             (i)  the issuer mints `sub` only from the reserved grammar ^br-[0-9a-f]{16}$;
//             (ii) provisioning guarantees every grammar-matching username is `sm-browser` and no
//                  internal / control / ablation username matches it (asserted from SEMP here).
//  MAJOR    A ticket was not bound to an organization: both VPNs trusted the same iss+aud, so an
//           orgA ticket could be presented to VPN orgB. Each VPN's OAuth profile now requires its
//           OWN audience (sm-mesh-acl-<org>) and the issuer stamps it.
//
// Every "refused" below is confirmed from the broker (no session appeared in SEMP monitor), and
// every refusal is paired with the control that shows the refusal is what stops the escalation.

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  BR, NON_BROWSER, SolaceWebAppSDK, WSS, rawLogin, internal, oauthClient, connectObserved, tryConnect, tryMint, mint,
  trySubscribe, attempt, record, sleep, waitFor, sempConfig, sempMonitor, sempWrite, sessions,
  healthTopic, brokerTx, settledDelta, sempAll, browserAclViolations,
} from './lib/common.mjs';

const GRAMMAR = /^br-[0-9a-f]{16}$/;
const VPNS = ['orgA', 'orgB'];
const PROD = { org: 'orgA', su: 'su1', env: 'prod' };
const PROD_B = { org: 'orgB', su: 'su1', env: 'prod' };

async function clientUsernames(vpn) {
  return (await sempAll('config', `/msgVpns/${vpn}/clientUsernames`)).data; // follows SEMP paging (round 3)
}

/** Numeric VPN-level counters that mention login/auth/fail/denied, so a refusal can be
 *  corroborated by the BROKER's own counters, not only by the absence of a session. */
async function vpnCounters(vpn) {
  const d = (await sempMonitor(`/msgVpns/${vpn}`)).data ?? {};
  return Object.fromEntries(Object.entries(d).filter(([k, v]) => typeof v === 'number' && /login|auth|fail|denied|discard/i.test(k)));
}
const counterDelta = (a, b) => Object.fromEntries(Object.entries(b).filter(([k, v]) => v !== a[k]).map(([k, v]) => [k, v - (a[k] ?? 0)]));

/** Attempt a login and confirm from the broker that it was REFUSED: no session for that
 *  original client username appeared on the VPN. Returns the evidence object. */
async function refusedLogin(vpn, connectFn, sub) {
  const before = new Set((await sessions(vpn)).map((c) => c.clientName));
  const c0 = await vpnCounters(vpn);
  const r = await tryConnect(connectFn, { timeoutMs: 5000 });
  await sleep(300);
  const after = await sessions(vpn);
  const appeared = after.filter((c) => !before.has(c.clientName));
  const delta = counterDelta(c0, await vpnCounters(vpn));
  if (r.connected) await r.client.disconnect();
  return { vpn, sub, sdkConnected: r.connected, sdkError: r.error, newSessionsOnBroker: appeared.map((c) => ({ clientUsername: c.clientUsername, original: c.originalClientUsername, acl: c.aclProfileName })), brokerCounterDelta: delta, refused: !r.connected && appeared.length === 0 };
}

/** OAuth refusal, corroborated TWICE: (1) the Web App SDK login must not produce a session, and
 *  (2) a raw solclientjs OAUTH2 login with the SAME kind of ticket must end in the broker's own
 *  CONNECT_FAILED_ERROR (Unauthorized), not a client-side timeout. Both must show no new session
 *  in SEMP monitor. */
async function refusedOAuth(vpn, sub, o = {}) {
  const viaSdk = await refusedLogin(vpn, () => oauthClient(vpn, sub, o), sub);
  const before = new Set((await sessions(vpn)).map((c) => c.clientName));
  const token = await mint({ org: o.tokenOrg ?? vpn, sub, ...(o.aud ? { aud: o.aud } : {}) }, { raw: o.raw });
  const wire = await rawLogin(vpn, undefined, undefined, { accessToken: token });
  await sleep(300);
  const appeared = (await sessions(vpn)).filter((c) => !before.has(c.clientName));
  return { ...viaSdk, wire: { ...wire, newSessionsOnBroker: appeared.map((c) => c.originalClientUsername) }, refused: viaSdk.refused && !wire.connected && wire.event === 'CONNECT_FAILED_ERROR' && appeared.length === 0 };
}

let pubA, pubB;
beforeAll(async () => { pubA = await internal('orgA'); pubB = await internal('orgB'); }, 60000);
afterAll(async () => { await Promise.all([pubA, pubB].map((c) => c?.disconnect())); });

// =============================================================================================
describe('round 2 BLOCKER, half (ii) — provisioning guarantees, ASSERTED from SEMP (16-config)', () => {
  it('every client username in EVERY VPN: grammar-matching => sm-browser + no publish; sm-internal / ablation profiles => never grammar-matching; `default` pinned; audience per organization', async () => {
    const rows = [];
    for (const vpn of VPNS) {
      const users = await clientUsernames(vpn);
      const prof = (await sempConfig(`/msgVpns/${vpn}/authenticationOauthProfiles/sm_scope_oauth`)).data;
      expect(prof.resourceServerRequiredAudience, `${vpn} OAuth required audience`).toBe(`sm-mesh-acl-${vpn}`);
      expect(prof.resourceServerValidateAudienceEnabled).toBe(true);
      for (const u of users) {
        const grammar = GRAMMAR.test(u.clientUsername);
        rows.push({ vpn, clientUsername: u.clientUsername, grammar, clientProfile: u.clientProfileName, acl: u.aclProfileName, enabled: u.enabled });
        if (grammar) {
          expect(u.clientProfileName, `${vpn}/${u.clientUsername} matches the browser grammar`).toBe('sm-browser');
          const acl = (await sempConfig(`/msgVpns/${vpn}/aclProfiles/${u.aclProfileName}`)).data;
          const pex = (await sempConfig(`/msgVpns/${vpn}/aclProfiles/${u.aclProfileName}/publishTopicExceptions`)).data ?? [];
          expect(acl.publishTopicDefaultAction).toBe('disallow');
          expect(pex).toEqual([]);
        } else if (u.clientUsername === 'default') {
          expect(u.enabled, `${vpn} default enabled`).toBe(true);
          expect(u.clientProfileName).toBe('sm-browser');
          expect(u.aclProfileName).toBe(`acl-${vpn}-stream-subst`);
        } else {
          expect(u.clientProfileName, `${vpn}/${u.clientUsername} is not grammar-matching and must not be a browser profile`).not.toBe('sm-browser');
        }
      }
      for (const cp of ['sm-internal', 'sm-abl-create-off', 'sm-abl-recv-off', 'sm-abl-send-off']) {
        expect(users.filter((u) => u.clientProfileName === cp && GRAMMAR.test(u.clientUsername)), `${vpn}: no ${cp} username matches the browser grammar`).toEqual([]);
      }
    }
    // round 3: the same assertion, extended to the ACL behind every browser-facing username (default included) and its subscribe exceptions
    for (const vpn of VPNS) expect(await browserAclViolations(vpn), `${vpn} browser-facing ACL violations`).toEqual([]);
    const audA = (await sempConfig('/msgVpns/orgA/authenticationOauthProfiles/sm_scope_oauth')).data.resourceServerRequiredAudience;
    const audB = (await sempConfig('/msgVpns/orgB/authenticationOauthProfiles/sm_scope_oauth')).data.resourceServerRequiredAudience;
    expect(audA).not.toBe(audB);
    record({
      leg: '16-config', attempted: 'enumerate every client username in orgA and orgB from SEMP config; assert the identity-binding invariants',
      expected: 'grammar-matching => sm-browser and no publish; internal/control/ablation never match the grammar; default pinned on BOTH VPNs; distinct audience per VPN',
      observed: { audiences: { orgA: audA, orgB: audB }, clientUsernames: rows }, pass: true,
    });
  }, 60000);
});

// =============================================================================================
describe('round 2 BLOCKER, half (i) — the ruled /token refuses every non-browser username; the raw endpoint shows the refusal is load-bearing', () => {
  it('16a: /token with sub = EACH non-browser client username of EACH VPN (enumerated from SEMP), for both organizations\' audiences, is HTTP 400 with no token', async () => {
    const names = new Set(['default']);
    for (const vpn of VPNS) for (const u of await clientUsernames(vpn)) if (!GRAMMAR.test(u.clientUsername)) names.add(u.clientUsername);
    expect([...names]).toEqual(expect.arrayContaining(['sm-internal-orgA', 'sm-internal-orgB', NON_BROWSER.CTL, 'orgA-abl-create-off', 'orgA-abl-recv-off', 'orgA-abl-send-off', 'orgA-abl-pub-send-off', 'default']));
    const rows = [];
    for (const sub of names) for (const org of VPNS) {
      const m = await tryMint({ org, sub });
      rows.push({ sub, org, status: m.status, gotToken: !!m.token, reason: m.reason });
      expect(m.status, `/token sub=${sub} org=${org}`).toBe(400);
      expect(m.token).toBeUndefined();
    }
    record({ leg: '16a', attempted: `POST /token for ${names.size} non-browser usernames x 2 organizations (${rows.length} requests)`, expected: 'every request HTTP 400, no token', observed: { usernames: [...names], requests: rows.length, allRefused: true, sample: rows.slice(0, 3) }, pass: true });
  }, 60000);

  it('16b (LOAD-BEARING): the SAME subs through the measurement-only raw endpoint DO escalate — sm-internal-orgA over OAuth subscribes sm/v1/>, receives the prod event and PUBLISHES a forged event a real prod subscriber receives', async () => {
    const prodTopic = healthTopic(PROD);
    const legit = [];
    const witness = await internal('orgA');
    await witness.subscribe('sm/v1/orgA/su1/prod/notify/>', (m) => legit.push(m.payload));

    const { client: atk, session } = await connectObserved('orgA', () => oauthClient('orgA', 'sm-internal-orgA', { raw: true }));
    expect(session.clientUsername).toBe('sm-internal-orgA');
    expect(session.aclProfileName).toBe('acl-internal');
    expect(session.clientProfileName).toBe('sm-internal');
    const heard = [];
    const sub = await trySubscribe(atk, 'sm/v1/>', (m) => heard.push(m.payload));
    await pubA.publish(prodTopic, { real: true });
    await waitFor(() => heard.some((p) => p?.real));
    await atk.publish(prodTopic, { forged: true });
    const forgedSeen = await waitFor(() => legit.some((p) => p?.forged));
    record({
      leg: '16b', attempted: 'raw (issuer-rule-free) token sub=sm-internal-orgA on VPN orgA; subscribe sm/v1/>; publish a forged event into the prod notify topic',
      expected: 'ESCALATION MEASURED (this is what the grammar refusal in 16a prevents)',
      observed: { session, subscribe: sub, attackerReceivedRealProdEvent: heard.some((p) => p?.real), realProdSubscriberReceivedForgedEvent: forgedSeen },
      pass: false, note: 'escalation measured through the raw endpoint; 16a shows the ruled endpoint never mints this sub',
    });
    expect(sub).toMatchObject({ outcome: 'allowed' });
    expect(heard.some((p) => p?.real)).toBe(true);
    expect(forgedSeen).toBe(true);
    await Promise.all([atk.disconnect(), witness.disconnect()]);
  }, 60000);

  it('16b (queue): raw sub = the control username (sm-internal) binds a prod-subscribed queue and receives the prod event', async () => {
    const Q = 'sm-q-idbind';
    await sempWrite('DELETE', `/msgVpns/orgA/queues/${Q}`);
    const q = await sempWrite('POST', '/msgVpns/orgA/queues', { msgVpnName: 'orgA', queueName: Q, owner: 'sm-internal-orgA', permission: 'consume', ingressEnabled: true, egressEnabled: true, accessType: 'non-exclusive' });
    expect(q.ok, JSON.stringify(q.error)).toBe(true);
    await sempWrite('POST', `/msgVpns/orgA/queues/${Q}/subscriptions`, { msgVpnName: 'orgA', queueName: Q, subscriptionTopic: 'sm/v1/orgA/su1/prod/notify/>' });
    try {
      await pubA.publishGuaranteed(healthTopic(PROD), { tag: 'idbind' });
      const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', NON_BROWSER.CTL, { raw: true }));
      const got = [];
      const r = await attempt(() => client.consume(Q, (m) => got.push(m.payload), { reconnectAttempts: 0, acknowledgeMode: 'auto' }));
      await waitFor(() => got.length >= 1, { timeoutMs: 5000 });
      record({ leg: '16b-queue', attempted: `raw token sub=${NON_BROWSER.CTL}; consume prod-subscribed queue`, expected: 'ESCALATION MEASURED', observed: { session, bind: r, delivered: got }, pass: false, note: 'prevented by 16a (ruled /token refuses this sub)' });
      expect(session.clientProfileName).toBe('sm-internal');
      expect(got.length).toBe(1);
      await client.disconnect();
    } finally { await sempWrite('DELETE', `/msgVpns/orgA/queues/${Q}`); }
  }, 60000);

  it('16b (system username): the broker\'s built-in `#client-username` (present in every VPN) is ALSO reachable over OAuth — measured with a raw token — and lands on the built-in `#acl-profile`; the ruled /token refuses it (16a enumerates it)', async () => {
    const acl = (await sempConfig('/msgVpns/orgA/aclProfiles/%23acl-profile')).data;
    const legit = [];
    const witness = await internal('orgA');
    await witness.subscribe('sm/v1/orgA/su1/prod/notify/>', (m) => legit.push(m.payload));
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', '#client-username', { raw: true }));
    const sub = await trySubscribe(client, 'sm/v1/>');
    await client.publish(healthTopic(PROD), { forged: 'system-username' });
    const forgedSeen = await waitFor(() => legit.some((p) => p?.forged === 'system-username'), { timeoutMs: 3000 });
    const ruled = await tryMint({ org: 'orgA', sub: '#client-username' });
    record({
      leg: '16b-system', attempted: 'raw token sub="#client-username" on orgA: resolved profiles, subscribe sm/v1/>, publish a forged prod event; ruled /token for the same sub',
      expected: 'measured (no expected value); ruled /token must refuse',
      observed: { session, builtInAclProfile: { connect: acl?.clientConnectDefaultAction, publish: acl?.publishTopicDefaultAction, subscribe: acl?.subscribeTopicDefaultAction }, subscribeAll: sub, forgedPublishReachedRealSubscriber: forgedSeen, ruledTokenStatus: ruled.status },
      pass: ruled.status === 400,
    });
    expect(ruled.status).toBe(400);
    await Promise.all([client.disconnect(), witness.disconnect()]);
  }, 60000);

  it('16c: every ticket the RULED issuer can mint resolves to client profile sm-browser (provisioned grants, unprovisioned subjects, issuer-generated ids)', async () => {
    const subs = [BR.GRANT, BR.CATALOG, BR.COMP1, BR.COMP2, BR.STREAM_A, undefined, undefined, undefined, 'br-0123456789abcdef', 'br-ffffffffffffffff', 'br-0000000000000000'];
    const rows = [];
    for (const sub of subs) {
      const token = await mint({ org: 'orgA', ...(sub ? { sub } : {}) });
      const claimed = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString()).sub;
      const { client, session } = await connectObserved('orgA', () => SolaceWebAppSDK.connect({ url: WSS, vpn: 'orgA', protocol: 'smf', auth: { type: 'oauth', accessToken: token } }));
      rows.push({ sub: claimed, generated: !sub, clientUsername: session.clientUsername, acl: session.aclProfileName, clientProfile: session.clientProfileName });
      expect(GRAMMAR.test(claimed)).toBe(true);
      expect(session.clientProfileName, `sub ${claimed}`).toBe('sm-browser');
      const canPublishGuaranteed = (client.adapter?.session?._capabilities ?? {})[11] === true;
      expect(canPublishGuaranteed).toBe(false);
      await client.disconnect();
    }
    record({ leg: '16c', attempted: `mint via /token and log in ${subs.length} times (5 provisioned/known, 3 generated, 3 arbitrary grammar values)`, expected: 'every session resolved to client profile sm-browser; none advertised guaranteed publish', observed: rows, pass: true });
  }, 120000);

  it('16d: basic authentication (raw solclientjs on the wire, INCLUDING an empty password) as a browser-grammar username, as an unprovisioned grammar name and as `default` is REFUSED BY THE BROKER — the browser grammar is not a password login and `default` is not reachable without a ticket', async () => {
    const cases = [
      ['grant, EMPTY password', BR.GRANT, ''], ['grant, password = username', BR.GRANT, BR.GRANT], ['grant, password "x"', BR.GRANT, 'x'],
      ['catalog grant, EMPTY password', BR.CATALOG, ''], ['composed grant, EMPTY password', BR.COMP1, ''],
      ['unprovisioned grammar name, empty password', 'br-00000000deadbeef', ''], ['unprovisioned grammar name, password "x"', 'br-00000000deadbeef', 'x'],
      ['default, EMPTY password', 'default', ''], ['default, password "default"', 'default', 'default'],
    ];
    // positive control: the same raw helper DOES log in with valid credentials (so a refusal is the broker's, not the helper's)
    const ctl = await rawLogin('orgA', 'sm-internal-orgA', 'sm-internal-orgA');
    expect(ctl.connected, JSON.stringify(ctl)).toBe(true);
    const rows = [];
    for (const [label, username, password] of cases) {
      const before = new Set((await sessions('orgA')).map((c) => c.clientName));
      const r = await rawLogin('orgA', username, password);
      await sleep(300);
      const appeared = (await sessions('orgA')).filter((c) => !before.has(c.clientName)).map((c) => ({ clientUsername: c.clientUsername, original: c.originalClientUsername }));
      rows.push({ label, username, ...r, newSessionsOnBroker: appeared, refused: !r.connected && appeared.length === 0 });
    }
    record({ leg: '16d', attempted: `${cases.length} raw-wire basic logins on orgA (valid-credential control first)`, expected: 'control connects; every case refused with a broker login failure, no session on the broker', observed: { control: ctl, cases: rows }, pass: rows.every((r) => r.refused) });
    for (const r of rows) expect(r.refused, JSON.stringify(r)).toBe(true);
  }, 120000);
});

// =============================================================================================
describe('round 2 MAJOR — a ticket is bound to ONE organization (credential portability, not message routing)', () => {
  const SUBS_A = [
    ['benign grammar sub', BR.STREAM_A, {}],
    ['the [orgA,su1,dev] grant sub', BR.GRANT, {}],
    ['sub naming an orgB username (raw)', 'sm-internal-orgB', { raw: true }],
    ['sub naming an orgA username (raw)', 'sm-internal-orgA', { raw: true }],
  ];

  it('17a: orgA-issued tickets (benign, grant, and subs naming internal usernames) presented to VPN orgB are REFUSED; orgB\'s session table is unchanged', async () => {
    const rows = [];
    for (const [label, sub, o] of SUBS_A) rows.push({ label, ...(await refusedOAuth('orgB', sub, { ...o, tokenOrg: 'orgA' })) });
    record({ leg: '17a', attempted: 'orgA-audience tickets, presented to VPN orgB', expected: 'all refused', observed: rows, pass: rows.every((r) => r.refused) });
    for (const r of rows) expect(r.refused, JSON.stringify(r)).toBe(true);
  }, 120000);

  it('17b: the reverse direction — orgB-issued tickets presented to VPN orgA are REFUSED', async () => {
    const rows = [];
    for (const [label, sub, o] of [['benign grammar sub', BR.STREAM_B, {}], ['sub naming an orgA username (raw)', 'sm-internal-orgA', { raw: true }], ['the [orgA,su1,dev] grant sub', BR.GRANT, {}]]) {
      rows.push({ label, ...(await refusedOAuth('orgA', sub, { ...o, tokenOrg: 'orgB' })) });
    }
    record({ leg: '17b', attempted: 'orgB-audience tickets, presented to VPN orgA', expected: 'all refused', observed: rows, pass: rows.every((r) => r.refused) });
    for (const r of rows) expect(r.refused, JSON.stringify(r)).toBe(true);
  }, 120000);

  it('17c: the round-1 shared audience is refused by BOTH VPNs (a ticket from the old configuration no longer works anywhere)', async () => {
    const rows = [];
    for (const vpn of VPNS) rows.push(await refusedOAuth(vpn, BR.STREAM_A, { raw: true, aud: 'sm-mesh-acl' }));
    record({ leg: '17c', attempted: 'ticket with aud=sm-mesh-acl (round 1) at orgA and orgB', expected: 'refused on both', observed: rows, pass: rows.every((r) => r.refused) });
    for (const r of rows) expect(r.refused, JSON.stringify(r)).toBe(true);
  }, 60000);

  it('17d (POSITIVE CONTROLS — attribute the refusals to the audience alone): the same subs with the HOME audience connect; an orgB ticket on orgB lands on orgB\'s stream route and is confined to its own orgB branch', async () => {
    // raw-wire OAuth control: the same helper that shows the refusals in 17a-c DOES connect with the home audience
    const wireCtlA = await rawLogin('orgA', undefined, undefined, { accessToken: await mint({ org: 'orgA', sub: BR.STREAM_A }) });
    const wireCtlB = await rawLogin('orgB', undefined, undefined, { accessToken: await mint({ org: 'orgB', sub: BR.STREAM_A }) });
    expect(wireCtlA.connected, JSON.stringify(wireCtlA)).toBe(true);
    expect(wireCtlB.connected, JSON.stringify(wireCtlB)).toBe(true);
    const home = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    expect(home.session.clientUsername).toBe(BR.GRANT);
    expect(home.session.aclProfileName).toBe('acl-orgA-su1-dev');

    const b = await connectObserved('orgB', () => oauthClient('orgB', BR.STREAM_A));
    expect(b.session.clientUsername).toBe('default');
    expect(b.session.aclProfileName).toBe('acl-orgB-stream-subst');
    expect(b.session.clientProfileName).toBe('sm-browser');
    expect(b.session.originalClientUsername).toBe(BR.STREAM_A);
    const got = [];
    const own = await trySubscribe(b.client, `sm/v1/orgB/stream/${BR.STREAM_A}/s1`, (m) => got.push(m.topic));
    const other = await trySubscribe(b.client, `sm/v1/orgB/stream/${BR.STREAM_B}/s1`);
    const crossOrg = await trySubscribe(b.client, `sm/v1/orgA/stream/${BR.STREAM_A}/s1`);
    const txB = await brokerTx('orgB', b.session.clientName);
    await pubB.publish(`sm/v1/orgB/stream/${BR.STREAM_A}/s1`, { for: 'B-own' });
    await pubB.publish(`sm/v1/orgB/stream/${BR.STREAM_B}/s1`, { for: 'B-other' });
    await waitFor(() => got.length >= 1);
    await sleep(400);
    const brokerToB = await settledDelta('orgB', b.session.clientName, txB, 1);
    record({
      leg: '17d', attempted: 'orgA ticket (grant sub) at orgA; orgB ticket (benign sub) at orgB, subscribe own / another / orgA-side stream branch',
      expected: 'both connect; the orgB session is confined to its own orgB branch',
      observed: { rawWireControls: { orgA: wireCtlA, orgB: wireCtlB }, orgAHome: home.session, orgBSession: b.session, own, other, crossOrg, delivered: got, brokerDataTxMsgCountDelta: brokerToB, publishedInOrgB: 2 },
      pass: own.outcome === 'allowed' && other.outcome === 'acl-denied' && crossOrg.outcome === 'acl-denied' && got.length === 1 && brokerToB === 1,
    });
    expect(brokerToB, '17d: the broker transmitted exactly the one own-branch message to the orgB session').toBe(1);
    expect(own).toMatchObject({ outcome: 'allowed' });
    expect(other).toMatchObject({ outcome: 'acl-denied' });
    expect(crossOrg).toMatchObject({ outcome: 'acl-denied' });
    expect(got).toEqual([`sm/v1/orgB/stream/${BR.STREAM_A}/s1`]);
    await Promise.all([home.client.disconnect(), b.client.disconnect()]);
  }, 60000);

  it('17e (LOAD-BEARING): relax orgB\'s required audience to orgA\'s and the round-2 review\'s leak REAPPEARS — an orgA ticket lands on orgB, and a raw sub naming sm-internal-orgB becomes an orgB internal identity that reads an orgB prod event; restored and re-verified afterwards', async () => {
    const path = '/msgVpns/orgB/authenticationOauthProfiles/sm_scope_oauth';
    const setAud = async (aud) => {
      let r = await sempWrite('PATCH', path, { resourceServerRequiredAudience: aud });
      if (!r.ok) {
        // some OAuth-profile attributes are writable only while the profile is disabled
        await sempWrite('PATCH', path, { enabled: false });
        r = await sempWrite('PATCH', path, { resourceServerRequiredAudience: aud });
        await sempWrite('PATCH', path, { enabled: true });
      }
      expect(r.ok, JSON.stringify(r.error)).toBe(true);
      expect((await sempConfig(path)).data.resourceServerRequiredAudience).toBe(aud);
      await sleep(1500);
    };
    let observed;
    try {
      await setAud('sm-mesh-acl-orgA');
      const benign = await connectObserved('orgB', () => oauthClient('orgB', BR.STREAM_A, { tokenOrg: 'orgA' }));
      const heard = [];
      const esc = await connectObserved('orgB', () => oauthClient('orgB', 'sm-internal-orgB', { raw: true, tokenOrg: 'orgA' }));
      const sub = await trySubscribe(esc.client, 'sm/v1/>', (m) => heard.push(m.topic));
      await pubB.publish(healthTopic(PROD_B), { orgB: 'prod-secret' });
      const leaked = await waitFor(() => heard.includes(healthTopic(PROD_B)));
      observed = { relaxedAudience: 'sm-mesh-acl-orgA', orgATicketBenignSubOnOrgB: benign.session, orgATicketInternalSubOnOrgB: esc.session, subscribeAll: sub, orgBProdEventReadByOrgATicket: leaked };
      expect(benign.session.aclProfileName).toBe('acl-orgB-stream-subst');
      expect(esc.session.clientProfileName).toBe('sm-internal');
      expect(leaked).toBe(true);
      await Promise.all([benign.client.disconnect(), esc.client.disconnect()]);
    } finally {
      await setAud('sm-mesh-acl-orgB');
    }
    const again = await refusedOAuth('orgB', BR.STREAM_A, { tokenOrg: 'orgA' });
    record({ leg: '17e', attempted: 'temporarily set orgB required audience = sm-mesh-acl-orgA; present orgA tickets to orgB; then restore', expected: 'LEAK MEASURED while relaxed (audience is load-bearing); refused again after restore', observed: { ...observed, afterRestoreRefused: again.refused }, pass: again.refused, note: 'the audience binding, not a stock default client username, is what carries organization isolation' });
    expect(again.refused).toBe(true);
  }, 180000);
});
