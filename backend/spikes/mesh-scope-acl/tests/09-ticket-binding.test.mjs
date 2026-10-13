// Round 3 MAJOR — PRINCIPAL-TO-GRANT BINDING. Throwaway spike test, NOT product code.
//
// The round-2 result said the binding from a browser to its identity was "measured". What legs
// 16-17 measure is narrower: a non-browser username cannot be minted, and a ticket for one
// organization is refused at the other. They do NOT measure that a principal can obtain only ITS
// OWN grant: the spike's /token has no caller authentication and takes `sub`, `org` and
// `expiresIn` from the request body (measured below, 19c/19d: any caller mints another grant's
// ticket, and a 3650-day one).
//
// The product-shaped path is the issuer's /ticket: the caller authenticates as a principal and
// `sub`, the audience and the lifetime come ONLY from that principal's server-side grant record;
// the request carries none of them. What is measured here is the behaviour of that stand-in.
// ⚠️ NOT measured: real caller authentication, the RBAC check that decides which grant a
// principal holds, and the gateway's key management. The credential is a static bearer string.

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  BR, PRINCIPAL, TICKET_TTL_S, SolaceWebAppSDK, WSS, internal, connectObserved, trySubscribe, tryMint, mint, tryTicket, decodeJwt,
  record, sleep, waitFor, healthTopic, brokerTx, settledDelta, ACL_DENIED,
} from './lib/common.mjs';

const PROD_A = { org: 'orgA', su: 'su1', env: 'prod' };
const DEV_A = { org: 'orgA', su: 'su1', env: 'dev' };
let pubA;
beforeAll(async () => { pubA = await internal('orgA'); }, 60000);
afterAll(async () => { await pubA?.disconnect(); });

const loginWith = (vpn, token) => SolaceWebAppSDK.connect({ url: WSS, vpn, protocol: 'smf', auth: { type: 'oauth', accessToken: token } });

describe('round 3 MAJOR — /ticket issues a principal ONLY its own grant', () => {
  it('19a: each principal, with an EMPTY request, gets a ticket whose sub/audience are its grant record\'s, with the fixed lifetime; the session resolves to that grant and nothing else', async () => {
    const rows = [];
    for (const p of Object.values(PRINCIPAL)) {
      const t = await tryTicket(p.cred);
      expect(t.ok, JSON.stringify(t)).toBe(true);
      const c = decodeJwt(t.token);
      expect(c.sub).toBe(p.sub);
      expect(c.aud).toBe(`sm-mesh-acl-${p.org}`);
      expect(c.exp - c.iat, 'lifetime is fixed by the issuer').toBe(TICKET_TTL_S);
      expect(Object.keys(c).sort()).toEqual(['aud', 'exp', 'iat', 'iss', 'scopes', 'sub']);
      const { client, session } = await connectObserved(p.org, () => loginWith(p.org, t.token));
      expect(session.originalClientUsername).toBe(p.sub);
      expect(session.aclProfileName, `${p.id} resolved ACL`).toBe(p.acl);
      expect(session.clientProfileName).toBe('sm-browser');
      rows.push({ principal: p.id, note: p.note, sub: c.sub, aud: c.aud, lifetimeSeconds: c.exp - c.iat, claims: Object.keys(c), session });
      await client.disconnect();
    }
    record({ leg: '19a', attempted: 'POST /ticket with an EMPTY body as each of 4 principals; decode the ticket; log in', expected: 'sub = the principal\'s own grant id, aud = its organization\'s audience, exp-iat = 900, no caller-supplied claim; the session resolves to that grant\'s ACL and client profile sm-browser', observed: rows, pass: true });
  }, 60000);

  it('19b: a principal ASKING for another grant, another organization, another audience, a longer lifetime, an internal username or extra claims is REFUSED: HTTP 400, no token — for every attempt, including one that names its OWN grant', async () => {
    const attempts = [
      ['dev asks for prod\'s grant id (the P1 escalation)', PRINCIPAL.DEV, { sub: PRINCIPAL.PROD.sub }],
      ['dev asks for the composed grant c102 via a `principal` field', PRINCIPAL.DEV, { principal: PRINCIPAL.PROD.id }],
      ['dev names its OWN grant (the request must carry none)', PRINCIPAL.DEV, { sub: PRINCIPAL.DEV.sub }],
      ['dev asks for organization orgB', PRINCIPAL.DEV, { org: 'orgB' }],
      ['orgB principal asks for organization orgA', PRINCIPAL.ORGB, { org: 'orgA' }],
      ['dev asks for orgB\'s audience', PRINCIPAL.DEV, { aud: 'sm-mesh-acl-orgB' }],
      ['dev asks for a 3650-day lifetime (expiresIn)', PRINCIPAL.DEV, { expiresIn: '3650d' }],
      ['dev asks for its own default lifetime explicitly (expiresIn 15m)', PRINCIPAL.DEV, { expiresIn: '15m' }],
      ['dev sets an absolute exp', PRINCIPAL.DEV, { exp: 4102444800 }],
      ['dev names an internal username', PRINCIPAL.DEV, { sub: 'sm-internal-orgA' }],
      ['dev adds a claim (scopes)', PRINCIPAL.DEV, { scopes: ['admin'] }],
      ['dev sends a non-object body', PRINCIPAL.DEV, ['sub']],
    ];
    const rows = [];
    for (const [label, p, body] of attempts) {
      const t = await tryTicket(p.cred, body);
      rows.push({ label, status: t.status, error: t.error, gotToken: !!t.token });
      expect(t.status, label).toBe(400);
      expect(t.token, label).toBeUndefined();
    }
    // query-string smuggling is refused too, and cannot select a grant
    const q = await tryTicket(PRINCIPAL.DEV.cred, undefined, { query: `?sub=${PRINCIPAL.PROD.sub}&org=orgB&expiresIn=3650d` });
    rows.push({ label: 'dev smuggles sub/org/expiresIn in the query string', status: q.status, gotToken: !!q.token });
    expect(q.status).toBe(400);
    expect(q.token).toBeUndefined();
    // a spoofed header cannot switch principal
    const h = await fetch('http://127.0.0.1:8091/ticket', { method: 'POST', headers: { Authorization: `Bearer ${PRINCIPAL.DEV.cred}`, 'x-principal': PRINCIPAL.PROD.id, 'x-grant': PRINCIPAL.PROD.sub } });
    const hj = await h.json();
    expect(h.status).toBe(200);
    expect(decodeJwt(hj.token).sub, 'headers do not select a grant').toBe(PRINCIPAL.DEV.sub);
    rows.push({ label: 'dev sends x-principal / x-grant headers naming prod', status: h.status, subInTicket: decodeJwt(hj.token).sub });
    record({ leg: '19b', attempted: `${attempts.length + 2} attempts by a principal to choose a grant, organization, audience, lifetime or claim (body, query string, headers)`, expected: 'every body/query attempt HTTP 400 with no token; headers ignored so the ticket still carries the caller\'s own grant', observed: rows, pass: true });
  }, 60000);

  it('19b-auth: no credential, an unknown credential, a credential of the wrong shape and a credential naming an Object.prototype key are HTTP 401, no token', async () => {
    // Round 4 MINOR 1: the round-3 issuer looked principals up with `PRINCIPALS[cred]` on a plain
    // object, so `__proto__`, `constructor`, `toString`, `hasOwnProperty` (reviewer probe) and every
    // other prototype key was "a known principal" (HTTP 200, a signed ticket with no `sub` and
    // aud `sm-mesh-acl-undefined`). Measured against that issuer: tools/issuer-proto-probe.sh,
    // evidence/issuer-proto-probe.log (HTTP 200 for all six probed keys). The current issuer uses a Map.
    const PROTO_KEYS = Object.getOwnPropertyNames(Object.prototype);   // enumerated from the runtime, not a hand-kept list
    expect(PROTO_KEYS).toEqual(expect.arrayContaining(['__proto__', 'constructor', 'toString', 'hasOwnProperty']));   // the reviewer's four
    const rows = [];
    const creds = [['no Authorization header', undefined], ['unknown credential', 'cred-nobody'], ['a grant id used as a credential', PRINCIPAL.DEV.sub], ['empty credential', ''],
      ...PROTO_KEYS.map((k) => [`Object.prototype key "${k}"`, k])];
    for (const [label, cred] of creds) {
      const t = await tryTicket(cred);
      rows.push({ label, status: t.status, gotToken: !!t.token });
      expect(t.status, label).toBe(401);
      expect(t.token, label).toBeUndefined();
    }
    // control: the same endpoint still serves a real principal (a 401 everywhere would prove nothing)
    const ok = await tryTicket(PRINCIPAL.DEV.cred);
    rows.push({ label: 'CONTROL real principal p-dev', status: ok.status, gotToken: !!ok.token });
    expect(ok.status).toBe(200);
    record({ leg: '19b-auth', attempted: `POST /ticket unauthenticated / with a wrong credential / with each of the ${PROTO_KEYS.length} Object.prototype keys as the credential (+ real-principal control)`, expected: 'HTTP 401, no token, for every one; the control is HTTP 200', observed: rows, pass: rows.slice(0, -1).every((r) => r.status === 401 && !r.gotToken) && ok.status === 200 });
  }, 30000);

  it('19e (positive control for 19c): the prod principal legitimately reads su1/prod through its OWN ticket; the dev principal\'s own ticket does not — so 19c\'s reach into prod is a real crossing of a boundary', async () => {
    const own = async (p, scope) => {
      const t = await tryTicket(p.cred);
      const { client, session } = await connectObserved('orgA', () => loginWith('orgA', t.token));
      const got = [];
      const r = await trySubscribe(client, `sm/v1/orgA/${scope.su}/${scope.env}/notify/>`, (m) => got.push(m.topic));
      const tx = await brokerTx('orgA', session.clientName);
      await pubA.publish(healthTopic(scope), { c: 'e' });
      await waitFor(() => got.length >= 1, { timeoutMs: 2500 });
      const brokerTx1 = await settledDelta('orgA', session.clientName, tx, r.outcome === 'allowed' ? 1 : 0);
      await client.disconnect();
      return { sub: subOf(t.token), subscribe: r.outcome, delivered: got, brokerDataTxMsgCountDelta: brokerTx1 };
    };
    const prodByProd = await own(PRINCIPAL.PROD, PROD_A);
    const prodByDev = await own(PRINCIPAL.DEV, PROD_A);
    record({ leg: '19e', attempted: 'the prod principal subscribes su1/prod with its own ticket; the dev principal subscribes su1/prod with its own ticket; one prod health event published', expected: 'prod principal: allowed, delivered (broker 1); dev principal: refused, nothing delivered (broker 0)', observed: { prodPrincipal: prodByProd, devPrincipal: prodByDev }, pass: prodByProd.subscribe === 'allowed' && prodByProd.brokerDataTxMsgCountDelta === 1 && prodByDev.subscribe === 'acl-denied' && prodByDev.brokerDataTxMsgCountDelta === 0 });
    expect(prodByProd).toMatchObject({ subscribe: 'allowed', brokerDataTxMsgCountDelta: 1 });
    expect(prodByDev).toMatchObject({ subscribe: 'acl-denied', brokerDataTxMsgCountDelta: 0 });
    expect(prodByDev.delivered).toEqual([]);
  }, 60000);
});

function subOf(token) { return decodeJwt(token).sub; }

describe('round 3 MAJOR — LOAD-BEARING: the spike\'s caller-unauthenticated /token lets any caller take another grant\'s scope and lifetime', () => {
  it('19c (P1): with NO credential, /token minted for sub = the prod grant\'s id logs in as that grant, subscribes su1/prod and RECEIVES the real prod health event (counted at the broker)', async () => {
    const t = await tryMint({ org: 'orgA', sub: PRINCIPAL.PROD.sub });
    expect(t.ok, JSON.stringify(t)).toBe(true);
    const { client, session } = await connectObserved('orgA', () => loginWith('orgA', t.token));
    const got = [];
    const r = await trySubscribe(client, 'sm/v1/orgA/su1/prod/notify/>', (m) => got.push(m.topic));
    const tx = await brokerTx('orgA', session.clientName);
    await pubA.publish(healthTopic(PROD_A), { real: 'prod' });
    await waitFor(() => got.length >= 1, { timeoutMs: 4000 });
    const broker = await settledDelta('orgA', session.clientName, tx, 1);
    record({
      leg: '19c', attempted: 'POST /token {org:orgA, sub:<prod grant id>} with NO credential; log in; subscribe sm/v1/orgA/su1/prod/notify/>; one prod health event published',
      expected: 'MEASURED ESCALATION (this is what the principal-to-grant rule in /ticket prevents, see 19b)',
      observed: { session, subscribe: r, delivered: got, brokerDataTxMsgCountDelta: broker },
      pass: false, note: 'the fixture /token has no caller authentication: a caller-chosen grant id reads another grant\'s scope; grant ids are sequential in the fixture',
    });
    expect(session.clientUsername).toBe(PRINCIPAL.PROD.sub);
    expect(r).toMatchObject({ outcome: 'allowed' });
    expect(broker).toBe(1);
    await client.disconnect();
  }, 60000);

  it('19d (P5): /token also honours a caller-chosen lifetime — a 3650-day ticket is issued — and passes caller claims through; /ticket does neither', async () => {
    const t = await tryMint({ org: 'orgA', sub: BR.GRANT, expiresIn: '3650d', extra: 'caller-claim' });
    expect(t.ok).toBe(true);
    const c = decodeJwt(t.token);
    const days = Math.round((c.exp - c.iat) / 86400);
    const own = decodeJwt((await tryTicket(PRINCIPAL.DEV.cred)).token);
    record({ leg: '19d', attempted: 'POST /token {org, sub, expiresIn:"3650d", extra:"caller-claim"}, no credential; then /ticket as a principal', expected: 'MEASURED: /token issues a 3650-day ticket with the caller\'s claim; /ticket issues exactly 900 s and no caller claim', observed: { tokenLifetimeDays: days, tokenClaims: Object.keys(c), ticketLifetimeSeconds: own.exp - own.iat, ticketClaims: Object.keys(own) }, pass: days === 3650 && own.exp - own.iat === TICKET_TTL_S });
    expect(days).toBe(3650);
    expect(Object.keys(c)).toContain('extra');
    expect(own.exp - own.iat).toBe(TICKET_TTL_S);
    expect(Object.keys(own)).not.toContain('extra');
  }, 30000);
});
