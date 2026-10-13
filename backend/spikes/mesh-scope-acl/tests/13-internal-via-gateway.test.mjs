// Round 4 MINOR 3 — INTERNAL IDENTITIES THROUGH THE BROWSER-FACING GATEWAY. Throwaway spike test.
//
// Round 3 argued that internal usernames are "never reachable through the issuer" and that "the
// grammar is the barrier". That holds for OAuth. It does NOT cover BASIC authentication: the
// browser web endpoint (wss://localhost:9444/mesh -> the broker's web transport) also accepts a
// basic login, which never touches the issuer. Reviewer probe P6: basic login as
// `sm-internal-orgA` / `sm-internal-orgA` through the gateway produced a session on `acl-internal` +
// `sm-internal` (subscribe and publish `sm/v1/>`, guaranteed everything). The only barrier was the
// password staying secret — and the spike commits password = username.
//
// This file MEASURES (a) the hole (21a), (b) whether restricting the ACL profile's CLIENT CONNECT
// exceptions to the backend's source address stops the gateway path while the backend path keeps
// working (21b/21c, with positive controls from the backend path and from a real browser ticket),
// (c) that the harness is restored (21d). The restriction is applied TEMPORARILY and reverted:
// the suite's own publishers ('internal()') log in through the gateway, so leaving it in place
// would break every other file — this is a measurement, not the provisioned state. It is NOT
// applied by provision.sh; see the result document for what it does and does not establish.
//
// Solace semantics used (docs.solace.com, fetched 2026-09-30, quoted in the result document):
// an ACL profile's client-connect default action plus connect exceptions; the exception is an
// IP address/netmask in CIDR form (broker's own served SEMP spec: MsgVpnAclProfileClientConnectException).

import { describe, it, expect, afterAll } from 'vitest';
import {
  BR, rawBasic, rawOAuth, mint, tryTicket, PRINCIPAL, sessions, clientDetail, sempWrite, sempConfig, sempAll, record, sleep, waitFor,
  internal, trySubscribe, healthTopic, browserAclViolations, SolaceWebAppSDK,
} from './lib/common.mjs';

const PROD = { org: 'orgA', su: 'su1', env: 'prod' };
const TARGETS = [
  { vpn: 'orgA', user: 'sm-internal-orgA', pass: 'sm-internal-orgA', acl: 'acl-internal', profile: 'sm-internal' },
  { vpn: 'orgA', user: 'orgA-abl-pub-send-off', pass: 'orgA-abl-pub-send-off', acl: 'acl-internal', profile: 'sm-abl-send-off' },
  { vpn: 'orgB', user: 'sm-internal-orgB', pass: 'sm-internal-orgB', acl: 'acl-internal', profile: 'sm-internal' },
];
const host = (addr) => String(addr).replace(/:\d+$/, '');
const enc = encodeURIComponent;

/** login (kept), read what the broker resolved it to, incl. the source address it saw, then close. */
async function login(vpn, how) {
  const before = new Set((await sessions(vpn)).map((c) => c.clientName));
  const r = await how();
  await sleep(300);
  const added = (await sessions(vpn)).filter((c) => !before.has(c.clientName));
  let session = null;
  if (added.length === 1) {
    const d = await clientDetail(vpn, added[0].clientName);
    session = { clientUsername: added[0].clientUsername, acl: added[0].aclProfileName, clientProfile: added[0].clientProfileName, sourceAddress: host(d.clientAddress) };
  }
  return { connected: r.connected, event: r.event, info: r.info, session, newSessions: added.length, close: r.close };
}

const applied = [];   // vpns where the restriction is currently in place
async function restore() {
  for (const vpn of [...applied]) {
    const ex = (await sempAll('config', `/msgVpns/${vpn}/aclProfiles/acl-internal/clientConnectExceptions`)).data;
    for (const e of ex) await sempWrite('DELETE', `/msgVpns/${vpn}/aclProfiles/acl-internal/clientConnectExceptions/${enc(e.clientConnectExceptionAddress)}`);
    await sempWrite('PATCH', `/msgVpns/${vpn}/aclProfiles/acl-internal`, { clientConnectDefaultAction: 'allow' });
    applied.splice(applied.indexOf(vpn), 1);
  }
}
afterAll(restore);

describe('round 4 MINOR 3 — basic auth as an internal identity through the browser-facing gateway', () => {
  let backendAddr; let gatewayAddr;

  it('21a (MEASURED HOLE, unrestricted): basic login as each password-holding internal username THROUGH THE BROWSER WSS ENDPOINT succeeds and resolves to acl-internal — subscribe sm/v1/> is allowed, the real prod event is received, and a forged prod publish reaches a real prod subscriber', async () => {
    const rows = [];
    for (const t of TARGETS) {
      const x = await login(t.vpn, () => rawBasic(t.vpn, t.user, t.pass, { path: 'gateway' }));
      rows.push({ vpn: t.vpn, user: t.user, connected: x.connected, event: x.event, session: x.session });
      expect(x.connected, JSON.stringify(x)).toBe(true);
      expect(x.session).toMatchObject({ clientUsername: t.user, acl: t.acl, clientProfile: t.profile });
      gatewayAddr = x.session.sourceAddress;
      x.close();
    }
    // what an attacker holding the password does through the gateway (the P6 escalation), with the SDK the browser would use
    const witness = await internal('orgA'); const legit = []; await witness.subscribe('sm/v1/orgA/su1/prod/notify/>', (m) => legit.push(m.payload));
    const pub = await internal('orgA');
    const atk = await internal('orgA'); const heard = [];
    const sub = await trySubscribe(atk, 'sm/v1/>', (m) => heard.push(m.payload));
    await pub.publish(healthTopic(PROD), { real: true });
    await waitFor(() => heard.some((p) => p?.real));
    await atk.publish(healthTopic(PROD), { forged: true });
    const forgedSeen = await waitFor(() => legit.some((p) => p?.forged));
    await Promise.all([witness.disconnect(), pub.disconnect(), atk.disconnect()]);
    record({ leg: '21a', attempted: 'basic login (username = password, as provisioned in the spike) as sm-internal-orgA, orgA-abl-pub-send-off (acl-internal, sm-abl-send-off) and sm-internal-orgB through wss://localhost:9444/mesh; then, as sm-internal-orgA: subscribe sm/v1/>, receive a real prod event, publish a forged prod event', expected: 'MEASURED HOLE: connects; acl-internal; sm/v1/> allowed; forged publish reaches a real subscriber', observed: { logins: rows, brokerSawGatewaySourceAddress: gatewayAddr, subscribeAll: sub, attackerReceivedRealProdEvent: heard.some((p) => p?.real), realProdSubscriberReceivedForgedEvent: forgedSeen }, pass: false, note: 'a hole is measured here, not a pass: no issuer is involved in a basic login; the only barrier is the password' });
    expect(sub).toMatchObject({ outcome: 'allowed' });
    expect(forgedSeen).toBe(true);
  }, 90000);

  it('21b: the source address the broker sees differs between the browser-facing path (the gateway/proxy) and a direct backend connection to the broker — the fact a connect-address restriction depends on', async () => {
    const back = await login('orgA', () => rawBasic('orgA', 'sm-internal-orgA', 'sm-internal-orgA', { path: 'backend' }));
    expect(back.connected, JSON.stringify(back)).toBe(true);
    backendAddr = back.session.sourceAddress;
    back.close();
    const gw = await login('orgA', () => rawBasic('orgA', 'sm-internal-orgA', 'sm-internal-orgA', { path: 'gateway' }));
    expect(gw.connected).toBe(true);
    gatewayAddr = gw.session.sourceAddress;
    gw.close();
    record({ leg: '21b', attempted: 'same basic login as sm-internal-orgA through the gateway (wss :9444 -> nginx) and directly to the broker (ws://127.0.0.1:8009, the published SMF web port, i.e. the backend path in this fixture); read clientAddress from the broker', expected: 'two different source addresses', observed: { gatewaySeenBrokerAs: gatewayAddr, backendSeenBrokerAs: backendAddr }, pass: !!gatewayAddr && !!backendAddr && gatewayAddr !== backendAddr });
    expect(gatewayAddr).not.toBe(backendAddr);
  }, 60000);

  it('21c: with acl-internal restricted (client-connect default DISALLOW + exception = the backend source address /32), basic login as every internal username through the GATEWAY is refused by the broker and OAuth-with-a-raw-token as sm-internal-orgA is refused too; the BACKEND path (positive control) still connects, subscribes sm/v1/> and publishes; a real browser ticket still connects', async () => {
    expect(backendAddr, '21b must have run').toBeTruthy();
    const refused = [];
    let backendRows; let oauthRow; let browserRow; let backendFunctional;
    try {
      for (const vpn of ['orgA', 'orgB']) {
        applied.push(vpn);
        const p = await sempWrite('PATCH', `/msgVpns/${vpn}/aclProfiles/acl-internal`, { clientConnectDefaultAction: 'disallow' });
        expect(p.ok, JSON.stringify(p.error)).toBe(true);
        const e = await sempWrite('POST', `/msgVpns/${vpn}/aclProfiles/acl-internal/clientConnectExceptions`, { msgVpnName: vpn, aclProfileName: 'acl-internal', clientConnectExceptionAddress: `${backendAddr}/32` });
        expect(e.ok, JSON.stringify(e.error)).toBe(true);
      }
      const stored = (await sempConfig('/msgVpns/orgA/aclProfiles/acl-internal')).data;
      const storedEx = (await sempAll('config', '/msgVpns/orgA/aclProfiles/acl-internal/clientConnectExceptions')).data.map((e) => e.clientConnectExceptionAddress);
      await sleep(500);
      for (const t of TARGETS) {
        const x = await login(t.vpn, () => rawBasic(t.vpn, t.user, t.pass, { path: 'gateway' }));
        refused.push({ vpn: t.vpn, user: t.user, connected: x.connected, event: x.event, info: x.info, newSessions: x.newSessions });
        x.close();
      }
      // the round-2 route: an OAuth ticket whose sub names the internal identity (raw endpoint) — connect exceptions apply regardless of scheme
      const o = await login('orgA', () => mintRaw('orgA', 'sm-internal-orgA'));
      oauthRow = { connected: o.connected, event: o.event, info: o.info, newSessions: o.newSessions };
      o.close();
      // positive controls: the backend path still works for every internal identity ...
      backendRows = [];
      for (const t of TARGETS) {
        const x = await login(t.vpn, () => rawBasic(t.vpn, t.user, t.pass, { path: 'backend' }));
        backendRows.push({ vpn: t.vpn, user: t.user, connected: x.connected, session: x.session });
        x.close();
      }
      // ... and the backend path still does the job: with the Web App SDK over the plaintext SMF web port (not the gateway), subscribe sm/v1/> and publish
      const be = await SolaceWebAppSDK.connect({ url: 'ws://127.0.0.1:8009', vpn: 'orgA', protocol: 'smf', auth: { type: 'basic', username: 'sm-internal-orgA', password: 'sm-internal-orgA' } });
      const beHeard = [];
      const beSub = await trySubscribe(be, 'sm/v1/>', (m) => beHeard.push(m.topic));
      await be.publish(healthTopic(PROD), { backend: 'still-works' });
      await waitFor(() => beHeard.length >= 1, { timeoutMs: 3000 });
      await be.disconnect();
      backendFunctional = { subscribeAll: beSub, receivedOwnPublish: beHeard.includes(healthTopic(PROD)) };
      // ... and a real browser (a genuine /ticket) still connects through the gateway
      const bt = await login('orgA', () => rawOAuthTicket());
      browserRow = { connected: bt.connected, session: bt.session };
      bt.close();
      record({
        leg: '21c', attempted: `acl-internal in orgA and orgB: clientConnectDefaultAction disallow + one clientConnectException ${backendAddr}/32 (the backend source address from 21b); basic login through the gateway as sm-internal-orgA, orgA-abl-pub-send-off, sm-internal-orgB; OAuth login through the gateway with a raw token sub=sm-internal-orgA; controls: the same basic logins on the backend path (+ subscribe sm/v1/> and publish over it); a genuine browser /ticket through the gateway`,
        expected: 'every gateway login as an internal identity refused by the broker with no session; every backend login connects; the browser ticket connects',
        observed: { storedAcl: { clientConnectDefaultAction: stored.clientConnectDefaultAction }, storedConnectExceptions: storedEx, gatewayBasic: refused, gatewayOAuthRawInternalSub: oauthRow, backendControl: backendRows, backendFunctionalControl: backendFunctional, browserTicketControl: browserRow },
        pass: refused.every((r) => !r.connected && r.newSessions === 0) && !oauthRow.connected && backendRows.every((r) => r.connected) && backendFunctional.receivedOwnPublish && browserRow.connected,
      });
      for (const r of refused) { expect(r.connected, JSON.stringify(r)).toBe(false); expect(r.newSessions).toBe(0); }
      expect(oauthRow.connected, JSON.stringify(oauthRow)).toBe(false);
      expect(oauthRow.newSessions).toBe(0);
      for (const r of backendRows) expect(r.connected, JSON.stringify(r)).toBe(true);
      expect(backendFunctional).toEqual({ subscribeAll: { outcome: 'allowed' }, receivedOwnPublish: true });
      expect(browserRow.connected).toBe(true);
      expect(browserRow.session).toMatchObject({ clientUsername: PRINCIPAL.DEV.sub, clientProfile: 'sm-browser' });
    } finally {
      await restore();
    }
  }, 120000);

  it('21d: the restriction is removed again — the state is what provision.sh provisions (acl-internal connect default allow, no connect exceptions), a gateway login as an internal identity works again (so 21c\'s refusal was the restriction), and the browser-facing ACL check is clean', async () => {
    for (const vpn of ['orgA', 'orgB']) {
      const a = (await sempConfig(`/msgVpns/${vpn}/aclProfiles/acl-internal`)).data;
      const ex = (await sempAll('config', `/msgVpns/${vpn}/aclProfiles/acl-internal/clientConnectExceptions`)).data;
      expect(a.clientConnectDefaultAction, vpn).toBe('allow');
      expect(ex, vpn).toEqual([]);
    }
    await sleep(500);
    const x = await login('orgA', () => rawBasic('orgA', 'sm-internal-orgA', 'sm-internal-orgA', { path: 'gateway' }));
    x.close();
    const v = [...await browserAclViolations('orgA'), ...await browserAclViolations('orgB')];
    record({ leg: '21d', attempted: 'read back acl-internal (both VPNs) after 21c; basic login as sm-internal-orgA through the gateway; run the browser-facing ACL check', expected: 'connect default allow, no connect exceptions; login works again; check clean', observed: { gatewayLoginAfterRestore: { connected: x.connected, session: x.session }, aclCheckViolations: v }, pass: x.connected && v.length === 0 });
    expect(x.connected).toBe(true);
    expect(v).toEqual([]);
  }, 60000);
});

// -- helpers that need the issuer (kept at the bottom so the test bodies read top-down) --
async function mintRaw(org, sub) {
  const token = await mint({ org, sub }, { raw: true });
  return rawOAuth(org, { accessToken: token });
}
async function rawOAuthTicket() {
  const t = await tryTicket(PRINCIPAL.DEV.cred);
  return rawOAuth('orgA', { accessToken: t.token });
}
