// Round 3 MINOR — THE "OTHER VPN" PATH. Throwaway spike test, NOT product code.
//
// nginx /mesh forwards the SMF WebSocket for ANY Message VPN name the client puts in its login.
// A broker's built-in `default` Message VPN is enabled, with web transport on and basic
// authentication type `none` (stock state — printed by provision.sh from SEMP and re-derived
// here). Through the SAME browser wss endpoint a browser can therefore connect to that VPN with
// any username/password. No SM data lives in that VPN, so this is not an SM scope leak in the
// spike; on a customer's shared broker the gateway proxy exposes EVERY VPN with web transport on.
// Solace: authentication type "none" — "No client authentication is performed for the client.
// Solace strongly recommends against using no client authentication." (Client Authentication
// Overview, https://docs.solace.com/Security/Client-Authentication-Overview.htm, fetched
// 2026-09-30). The pages for "how a client selects a Message VPN" and "the default VPN" could not
// be fetched (docs.solace.com 404 on three guessed URLs); both are MEASURED here instead.
//
// This file: (20a) re-opens the stock state and measures the hole; asserts the install check
// FIRES on it; (20b) measures three mitigations one at a time, each restored; (20c) applies the
// provisioned mitigation, and shows the install check is clean and the organizations unaffected.

import { describe, it, expect, afterAll } from 'vitest';
import {
  SolaceWebAppSDK, WSS, BR, oauthClient, connectObserved, tryConnect, trySubscribe, record, sleep, waitFor,
  sempConfig, sempMonitor, sempWrite, sempAll, sessions, rawLogin, allVpns, otherVpnViolations,
} from './lib/common.mjs';

const STOCK = { enabled: true, serviceWebPlainTextEnabled: true, serviceWebTlsEnabled: true, authenticationBasicEnabled: true, authenticationBasicType: 'none' };
const MITIGATED = { serviceWebPlainTextEnabled: false, serviceWebTlsEnabled: false };
const setDefaultVpn = async (patch) => {
  const r = await sempWrite('PATCH', '/msgVpns/default', patch);
  expect(r.ok, JSON.stringify(r.error)).toBe(true);
  await sleep(800);
};
const defaultVpnNow = async () => (await sempConfig('/msgVpns/default')).data;

/** One browser-path login to VPN `default` as anyone/anything. Resolves the evidence object. */
async function browserLoginToDefault() {
  const before = new Set(((await sempMonitor('/msgVpns/default/clients?count=100')).data ?? []).map((c) => c.clientName));
  const r = await tryConnect(() => SolaceWebAppSDK.connect({ url: WSS, vpn: 'default', protocol: 'smf', auth: { type: 'basic', username: 'anyone', password: 'anything' } }), { timeoutMs: 6000 });
  if (!r.connected) {
    await sleep(300);
    const appeared = ((await sempMonitor('/msgVpns/default/clients?count=100')).data ?? []).filter((c) => !before.has(c.clientName));
    return { connected: false, error: r.error, newSessionsOnBroker: appeared.length };
  }
  const got = [];
  const sub = await trySubscribe(r.client, '>', (m) => got.push(m.topic));
  await r.client.publish('probe/anything', { x: 1 });
  await waitFor(() => got.includes('probe/anything'), { timeoutMs: 3000 });
  const now = ((await sempMonitor('/msgVpns/default/clients?select=clientName,clientUsername,aclProfileName,clientProfileName&count=100')).data ?? []).filter((c) => !before.has(c.clientName));
  await r.client.disconnect();
  return { connected: true, subscribeAll: sub.outcome, selfDelivered: got, session: now };
}

afterAll(async () => {
  // whatever happened, leave the broker in the PROVISIONED (mitigated) state
  try { await sempWrite('PATCH', '/msgVpns/default', { enabled: true, authenticationBasicType: 'none', ...MITIGATED }); } catch { /* best effort */ }
});

describe('round 3 MINOR 3 — the browser wss endpoint reaches every Message VPN with web transport on', () => {
  it('20a: STOCK `default` VPN — a browser logs in as anyone/anything, subscribes `>`, publishes and receives its own message; the install check FIRES on this state', async () => {
    await setDefaultVpn({ ...STOCK });
    const state = await defaultVpnNow();
    for (const [k, v] of Object.entries(STOCK)) expect(state[k], `stock ${k}`).toBe(v);
    const users = (await sempAll('config', '/msgVpns/default/clientUsernames')).data.map((u) => ({ clientUsername: u.clientUsername, enabled: u.enabled, acl: u.aclProfileName, clientProfile: u.clientProfileName }));
    const acl = (await sempConfig('/msgVpns/default/aclProfiles/default')).data;
    const violations = await otherVpnViolations();
    expect(violations.map((v) => v.name), 'the install check must report the stock default VPN (else the check is vacuous)').toContain('default');

    const r = await browserLoginToDefault();
    record({
      leg: '20a', attempted: 'browser wss path (wss://localhost:9444/mesh), vpn=default, basic anyone/anything; subscribe `>`; publish probe/anything',
      expected: 'MEASURED HOLE in the stock configuration (no SM data in this VPN; the exposure is the reachability)',
      observed: { defaultVpn: Object.fromEntries(Object.keys(STOCK).map((k) => [k, state[k]])), clientUsernames: users, defaultAclProfile: { publish: acl.publishTopicDefaultAction, subscribe: acl.subscribeTopicDefaultAction, connect: acl.clientConnectDefaultAction }, browserLogin: r, installCheckReports: violations.map((v) => v.name) },
      pass: false, note: 'stock default VPN reachable from the browser transport; the install check flags it',
    });
    expect(r.connected, JSON.stringify(r)).toBe(true);
    expect(r.subscribeAll).toBe('allowed');
    expect(r.selfDelivered).toContain('probe/anything');
  }, 90000);

  it('20b: three mitigations, each applied ALONE to the stock state, each measured, each restored: VPN disabled | web transport off | anonymous (type none) authentication off', async () => {
    const mitigations = [
      ['M1 VPN disabled', { enabled: false }],
      ['M2 web transport off (plain + TLS)', { ...MITIGATED }],
      ['M3 basic authentication type none -> internal', { authenticationBasicType: 'internal' }],
    ];
    const rows = [];
    for (const [label, patch] of mitigations) {
      await setDefaultVpn({ ...STOCK });
      const open = await browserLoginToDefault();
      expect(open.connected, `${label}: control — the stock state is open`).toBe(true);
      await setDefaultVpn(patch);
      const after = await browserLoginToDefault();
      const violations = (await otherVpnViolations()).map((v) => v.name);
      // wire-level corroboration from the broker's own login failure (plaintext SMF port, VPN by name)
      const wire = await rawLogin('default', 'anyone', 'anything', { timeoutMs: 5000 });
      rows.push({ mitigation: label, patch, stockControlConnected: open.connected, afterMitigation: after, rawSmfLogin: wire, installCheckReports: violations });
      expect(after.connected, `${label}: ${JSON.stringify(after)}`).toBe(false);
      expect(after.newSessionsOnBroker ?? 0).toBe(0);
    }
    await setDefaultVpn({ ...STOCK });
    record({
      leg: '20b', attempted: 'for each of three mitigations: re-open the stock state (control: login succeeds), apply ONE mitigation, log in again via the browser wss path and via a raw SMF login',
      expected: 'the control login succeeds; after each mitigation the browser-path login fails and no session appears on the broker (M3 is recorded whatever the outcome — see observed)',
      observed: rows, pass: rows.every((r) => r.afterMitigation.connected === false),
    });
  }, 180000);

  it('20c: the PROVISIONED mitigation (web transport off on every VPN that is not an organization\'s own) — install check clean, `default` VPN unreachable, both organizations unaffected', async () => {
    await setDefaultVpn({ enabled: true, authenticationBasicType: 'none', ...MITIGATED });
    const vpns = await allVpns();
    const violations = await otherVpnViolations();
    const refused = await browserLoginToDefault();
    const raw = await rawLogin('default', 'anyone', 'anything', { timeoutMs: 5000 });
    // organizations unaffected: a browser-grammar ticket still lands on its own VPN
    const a = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    const b = await connectObserved('orgB', () => oauthClient('orgB', BR.STREAM_A));
    const orgs = { orgA: a.session.aclProfileName, orgB: b.session.aclProfileName };
    await Promise.all([a.client.disconnect(), b.client.disconnect()]);
    record({
      leg: '20c', attempted: 'enumerate every VPN (paged), apply the provisioned mitigation, log in to `default` again, log in to orgA and orgB with tickets',
      expected: 'install check reports no other browser-reachable VPN; default login refused; orgA/orgB logins unaffected',
      observed: { vpns, installCheckReports: violations.map((v) => v.name), browserLoginToDefault: refused, rawSmfLoginToDefault: raw, organizationsStillWork: orgs },
      pass: violations.length === 0 && refused.connected === false,
    });
    expect(violations).toEqual([]);
    expect(refused.connected).toBe(false);
    expect(orgs).toEqual({ orgA: 'acl-orgA-su1-dev', orgB: 'acl-orgB-stream-subst' });
    expect(vpns.map((v) => v.name).sort()).toEqual(expect.arrayContaining(['default', 'orgA', 'orgB']));
  }, 90000);
});
