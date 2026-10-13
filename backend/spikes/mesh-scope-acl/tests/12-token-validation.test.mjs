// Round 4 MINOR 4 — BROKER-SIDE TOKEN VALIDATION, measured. Throwaway spike test, NOT product code.
//
// The round-3 document listed token expiry as "not measured" and left several broker-side
// token-validation facts unstated although they are cheap to measure. The reviewer's probes
// (each with a valid-token control that connects) are legs here:
//   22a  a ticket signed by the issuer whose `exp` is already 60 s past          -> refused at login
//   22b  an `alg: none` token (unsigned), with and without the issuer's `kid`   -> refused
//   22c  an RS256 token signed by a FOREIGN key under the issuer's `kid`         -> refused
//   22d  a genuine /ticket token with its `sub` swapped to the prod grant       -> refused
//   22e  the ID-token path: an orgA ticket presented to orgB as an ID token       -> refused (and see the note on combined tokens)
//   22f  a client-supplied SMF `userName` next to an OAuth token                  -> ignored; the session is the token's `sub`
//   22g  expiry DURING a live session (disconnectOnTokenExpirationEnabled)        -> measured
// Every refusal is the BROKER's: solclientjs is driven directly (rawOAuth) so the outcome is the
// library's CONNECT_FAILED_ERROR ("Unauthorized"), not a client-side timeout, and SEMP must show
// no new session. Tokens are presented through the browser-facing wss endpoint, as a browser would.

import { describe, it, expect } from 'vitest';
import { generateKeyPairSync, createSign } from 'node:crypto';
import { BR, PRINCIPAL, rawOAuth, mint, tryTicket, decodeJwt, sessions, record, sleep, waitFor, trySubscribe, SolaceWebAppSDK, WSS, connectObserved } from './lib/common.mjs';

const b64 = (o) => Buffer.from(typeof o === 'string' ? o : JSON.stringify(o)).toString('base64url');
const nowS = () => Math.floor(Date.now() / 1000);
const sessionKeys = async (vpn) => new Set((await sessions(vpn)).map((c) => c.clientName));
const claimsFor = (sub, org = 'orgA', ttl = 900) => ({ iss: 'sm-spike-acl-issuer', sub, aud: `sm-mesh-acl-${org}`, iat: nowS(), exp: nowS() + ttl, scopes: [] });

/** Present `creds` at `vpn`; report the broker's answer and any session that appeared. Keeps a
 *  connected session open only long enough to read what it resolved to. */
async function present(vpn, creds) {
  const before = await sessionKeys(vpn);
  const r = await rawOAuth(vpn, creds);
  await sleep(300);
  const appeared = (await sessions(vpn)).filter((c) => !before.has(c.clientName)).map((c) => ({ clientUsername: c.clientUsername, original: c.originalClientUsername, acl: c.aclProfileName, clientProfile: c.clientProfileName }));
  r.close();
  return { connected: r.connected, event: r.event, info: r.info, newSessionsOnBroker: appeared, refused: !r.connected && appeared.length === 0 };
}
const brokerRefusal = (x) => x.refused && x.event === 'CONNECT_FAILED_ERROR' && /Unauthorized/i.test(x.info);

describe('round 4 MINOR 4 — the broker validates the token', () => {
  it('22-control: a genuine /ticket token connects through the same helper (so the refusals below are attributable to the token, not to the helper)', async () => {
    const t = await tryTicket(PRINCIPAL.DEV.cred);
    const x = await present('orgA', { accessToken: t.token });
    record({ leg: '22-control', attempted: 'raw solclientjs OAuth login through wss://localhost:9444/mesh with a genuine /ticket token (p-dev)', expected: 'connects; session = br-...d001 / acl-orgA-su1-dev / sm-browser', observed: x, pass: x.connected && x.newSessionsOnBroker[0]?.clientUsername === PRINCIPAL.DEV.sub });
    expect(x.connected, JSON.stringify(x)).toBe(true);
    expect(x.newSessionsOnBroker).toEqual([{ clientUsername: PRINCIPAL.DEV.sub, original: PRINCIPAL.DEV.sub, acl: 'acl-orgA-su1-dev', clientProfile: 'sm-browser' }]);
  }, 30000);

  it('22a: an issuer-signed ticket whose exp is 60 s in the PAST is refused at login by the broker (a same-second, otherwise identical ticket connects)', async () => {
    const expired = await mint({ org: 'orgA', sub: BR.GRANT, expiresIn: nowS() - 60 }, { raw: true });   // absolute NumericDate in the past; /raw-token only to be able to back-date exp
    const c = decodeJwt(expired);
    expect(c.exp).toBeLessThan(nowS());
    const x = await present('orgA', { accessToken: expired });
    const control = await present('orgA', { accessToken: await mint({ org: 'orgA', sub: BR.GRANT }, { raw: true }) });
    record({ leg: '22a', attempted: `issuer-signed ticket for ${BR.GRANT} with exp = now-60 s (exp ${c.exp}, now ${nowS()}), presented at orgA via wss; control: the same sub with a normal lifetime`, expected: 'expired: CONNECT_FAILED_ERROR Unauthorized, no session; control connects', observed: { expired: x, control }, pass: brokerRefusal(x) && control.connected });
    expect(brokerRefusal(x), JSON.stringify(x)).toBe(true);
    expect(control.connected).toBe(true);
  }, 30000);

  it('22b: an alg:none token (valid claims, empty signature), with and without the issuer\'s kid, is refused by the broker', async () => {
    const cl = claimsFor(BR.GRANT);
    const rows = [];
    for (const [label, header] of [['alg none', { alg: 'none', typ: 'JWT' }], ['alg none + issuer kid', { alg: 'none', typ: 'JWT', kid: 'acl-spike-1' }]]) {
      rows.push({ label, ...(await present('orgA', { accessToken: `${b64(header)}.${b64(cl)}.` })) });
    }
    const control = await present('orgA', { accessToken: await mint({ org: 'orgA', sub: BR.GRANT }, { raw: true }) });
    record({ leg: '22b', attempted: 'unsigned tokens {"alg":"none"} carrying the claims of a valid ticket for the [orgA,su1,dev] grant', expected: 'both CONNECT_FAILED_ERROR Unauthorized, no session; control connects', observed: { rows, control }, pass: rows.every(brokerRefusal) && control.connected });
    for (const r of rows) expect(brokerRefusal(r), JSON.stringify(r)).toBe(true);
    expect(control.connected).toBe(true);
  }, 30000);

  it('22c: an RS256 token signed by a FOREIGN key but carrying the issuer\'s kid (and otherwise valid claims) is refused by the broker', async () => {
    const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
    const h = b64({ alg: 'RS256', kid: 'acl-spike-1', typ: 'JWT' });
    const p = b64(claimsFor(BR.GRANT));
    const sig = createSign('RSA-SHA256').update(`${h}.${p}`).sign(privateKey).toString('base64url');
    const x = await present('orgA', { accessToken: `${h}.${p}.${sig}` });
    // an internal grammar-free target too: the forged token names the internal identity the whole barrier protects
    const h2 = b64({ alg: 'RS256', kid: 'acl-spike-1', typ: 'JWT' }); const p2 = b64(claimsFor('sm-internal-orgA'));
    const x2 = await present('orgA', { accessToken: `${h2}.${p2}.${createSign('RSA-SHA256').update(`${h2}.${p2}`).sign(privateKey).toString('base64url')}` });
    const control = await present('orgA', { accessToken: await mint({ org: 'orgA', sub: BR.GRANT }, { raw: true }) });
    record({ leg: '22c', attempted: 'RS256 token signed with a freshly generated foreign key under kid acl-spike-1, sub = the grant, and again with sub = sm-internal-orgA', expected: 'both refused by the broker (signature not verifiable with the issuer JWKS); control connects', observed: { grantSub: x, internalSub: x2, control }, pass: brokerRefusal(x) && brokerRefusal(x2) && control.connected });
    expect(brokerRefusal(x), JSON.stringify(x)).toBe(true);
    expect(brokerRefusal(x2), JSON.stringify(x2)).toBe(true);
    expect(control.connected).toBe(true);
  }, 30000);

  it('22d: a genuine /ticket token whose payload `sub` is swapped to the PROD grant (signature kept) is refused by the broker', async () => {
    const t = (await tryTicket(PRINCIPAL.DEV.cred)).token;
    const [h, p, s] = t.split('.');
    const payload = JSON.parse(Buffer.from(p, 'base64url'));
    expect(payload.sub).toBe(PRINCIPAL.DEV.sub);
    const swapped = `${h}.${b64({ ...payload, sub: PRINCIPAL.PROD.sub })}.${s}`;
    const swappedInternal = `${h}.${b64({ ...payload, sub: 'sm-internal-orgA' })}.${s}`;
    const x = await present('orgA', { accessToken: swapped });
    const xi = await present('orgA', { accessToken: swappedInternal });
    const control = await present('orgA', { accessToken: t });
    record({ leg: '22d', attempted: `p-dev's genuine /ticket token with payload.sub replaced by ${PRINCIPAL.PROD.sub} (the prod grant) and by sm-internal-orgA, original header and signature kept`, expected: 'both refused; the untouched token (control) connects', observed: { prodSub: x, internalSub: xi, control }, pass: brokerRefusal(x) && brokerRefusal(xi) && control.connected });
    expect(brokerRefusal(x), JSON.stringify(x)).toBe(true);
    expect(brokerRefusal(xi), JSON.stringify(xi)).toBe(true);
    expect(control.connected).toBe(true);
  }, 30000);

  it('22e: the ID-token path — an orgA ticket presented to VPN orgB as an ID token is refused (alone, and next to an orgB access token); the reverse direction too; a positive control shows an ID token ALONE is a working credential at its own organization', async () => {
    const tA = (await tryTicket(PRINCIPAL.DEV.cred)).token;
    const tB = (await tryTicket(PRINCIPAL.ORGB.cred)).token;
    const idAloneHome = await present('orgA', { idToken: tA });                              // control: ID token alone, home organization
    const idAloneAtB = await present('orgB', { idToken: tA });                               // orgA ticket as ID token at orgB
    const idAloneBAtA = await present('orgA', { idToken: tB });                              // reverse
    const accessBIdA = await present('orgB', { accessToken: tB, idToken: tA });              // valid orgB access token + orgA ticket as ID token
    // NOTE (measured, not explained): access token + ID token TOGETHER are refused even when both are valid at the home organization,
    // so the combined presentation above is refused for a reason that is NOT attributable to the audience. Recorded for exactly that reason.
    const accessBIdBHome = await present('orgB', { accessToken: tB, idToken: tB });
    const accessAIdAHome = await present('orgA', { accessToken: tA, idToken: tA });
    record({
      leg: '22e', attempted: 'orgA ticket as an ID token at orgB; orgB ticket as an ID token at orgA; orgB access token + orgA ticket as ID token at orgB; controls: ID token alone at its home organization; access + ID both valid at home',
      expected: 'cross-organization ID tokens refused; ID token alone works at home (control)',
      observed: { idTokenAloneAtHome_orgA: idAloneHome, orgATicketAsIdTokenAtOrgB: idAloneAtB, orgBTicketAsIdTokenAtOrgA: idAloneBAtA, orgBAccessPlusOrgAIdTokenAtOrgB: accessBIdA, combinedBothValidAtHome_orgB: accessBIdBHome, combinedBothValidAtHome_orgA: accessAIdAHome },
      pass: idAloneHome.connected && brokerRefusal(idAloneAtB) && brokerRefusal(idAloneBAtA) && brokerRefusal(accessBIdA),
      note: 'access+ID combined is refused even when both are valid at home (see the two combinedBothValidAtHome_* rows): that refusal is not evidence about the audience',
    });
    expect(idAloneHome.connected, JSON.stringify(idAloneHome)).toBe(true);
    expect(idAloneHome.newSessionsOnBroker[0]?.clientUsername, 'an ID token alone resolves to its sub, like an access token').toBe(PRINCIPAL.DEV.sub);
    expect(brokerRefusal(idAloneAtB), JSON.stringify(idAloneAtB)).toBe(true);
    expect(brokerRefusal(idAloneBAtA), JSON.stringify(idAloneBAtA)).toBe(true);
    expect(brokerRefusal(accessBIdA), JSON.stringify(accessBIdA)).toBe(true);
  }, 60000);

  it('22f: a client-supplied SMF userName next to an OAuth token is IGNORED — every session resolves to the token\'s sub, whatever userName was sent (internal, control, another grant, `default`)', async () => {
    // the library puts sprop.userName into the SMF header (pm_username) irrespective of the authentication scheme
    // (solclientjs-debug.js, ClientCtrlMessage login builder, `if (sprop.userName) smfHeader.pm_username = ...`), so this value is on the wire
    const t = (await tryTicket(PRINCIPAL.DEV.cred)).token;
    const rows = [];
    for (const userName of ['sm-internal-orgA', PRINCIPAL.PROD.sub, 'orgA-su1-dev-ctl', 'default']) {
      const x = await present('orgA', { accessToken: t, userName });
      rows.push({ suppliedUserName: userName, ...x });
    }
    record({ leg: '22f', attempted: 'OAuth login with p-dev\'s ticket AND a client-supplied SMF userName in {sm-internal-orgA, the prod grant id, orgA-su1-dev-ctl, default}', expected: 'connects each time; the session\'s client username, ACL and client profile are those of the token\'s sub (br-...d001 / acl-orgA-su1-dev / sm-browser), never the supplied name', observed: rows, pass: rows.every((r) => r.connected && r.newSessionsOnBroker[0]?.clientUsername === PRINCIPAL.DEV.sub && r.newSessionsOnBroker[0]?.acl === 'acl-orgA-su1-dev' && r.newSessionsOnBroker[0]?.clientProfile === 'sm-browser') });
    for (const r of rows) {
      expect(r.connected, JSON.stringify(r)).toBe(true);
      expect(r.newSessionsOnBroker, JSON.stringify(r)).toEqual([{ clientUsername: PRINCIPAL.DEV.sub, original: PRINCIPAL.DEV.sub, acl: 'acl-orgA-su1-dev', clientProfile: 'sm-browser' }]);
    }
  }, 60000);

  it('22g: expiry DURING a live session — a ticket with a ~10 s lifetime: the session is up and subscribed, then the broker DISCONNECTS it at expiry (OAuth profile disconnectOnTokenExpirationEnabled); a 900 s ticket held alongside stays up', async () => {
    const cfg = (await (await fetch('http://127.0.0.1:8081/SEMP/v2/config/msgVpns/orgA/authenticationOauthProfiles/sm_scope_oauth', { headers: { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') } })).json()).data;
    const exp = nowS() + 10;
    const short = await mint({ org: 'orgA', sub: BR.GRANT, expiresIn: exp }, { raw: true });
    const long = await mint({ org: 'orgA', sub: BR.COMP1 }, { raw: true });   // 15 m default
    const a = await connectObserved('orgA', () => SolaceWebAppSDK.connect({ url: WSS, vpn: 'orgA', protocol: 'smf', auth: { type: 'oauth', accessToken: short }, reconnect: false, reconnectRetries: 0 }));
    const b = await connectObserved('orgA', () => SolaceWebAppSDK.connect({ url: WSS, vpn: 'orgA', protocol: 'smf', auth: { type: 'oauth', accessToken: long }, reconnect: false, reconnectRetries: 0 }));
    const sub = await trySubscribe(a.client, 'sm/v1/orgA/su1/dev/notify/>');
    const upAt = Date.now();
    const present0 = async (name) => (await sessions('orgA')).some((c) => c.clientName === name);
    expect(await present0(a.session.clientName)).toBe(true);
    const dropped = await waitFor(async () => !(await present0(a.session.clientName)), { timeoutMs: 45000, stepMs: 1000 });
    const droppedAfterS = (Date.now() - upAt) / 1000;
    const longStillUp = await present0(b.session.clientName);
    record({ leg: '22g', attempted: `ticket for ${BR.GRANT} with exp = now+10 s; connect, subscribe the granted scope, wait up to 45 s; a 15-minute ticket for ${BR.COMP1} held alongside`, expected: 'measured (no expected value): does the broker drop the session at token expiry?', observed: { disconnectOnTokenExpirationEnabled: cfg?.disconnectOnTokenExpirationEnabled, subscribe: sub, sessionDroppedByBroker: dropped, secondsAfterConnectWhenDropped: dropped ? Math.round(droppedAfterS) : null, mintedLifetimeS: 10, longTicketSessionStillUp: longStillUp }, pass: dropped && longStillUp });
    expect(cfg?.disconnectOnTokenExpirationEnabled).toBe(true);
    expect(dropped, 'the broker disconnected the session at token expiry').toBe(true);
    expect(longStillUp).toBe(true);
    await a.client.disconnect().catch(() => {}); await b.client.disconnect();
  }, 90000);
});
