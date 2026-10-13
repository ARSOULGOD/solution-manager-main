// §11.3 spike token issuer — throwaway harness, NOT product code.
//
// Copied from backend/spikes/mesh-authz/issuer/issuer.mjs (G1) with two deltas:
//   - Listens on :8091, not :8090, so this spike can run alongside G1's own issuer without a
//     port collision (checked free with `lsof` before this file was written).
//   - iss/aud strings are 'sm-spike-acl-issuer' / 'sm-mesh-acl-<org>' (one audience per organization, round 2) so a token minted here can
//     never be confused with a G1 token even if both brokers were ever reachable at once.
//
// Everything else — key persistence, rotation, the /token contract — is unchanged; this
// spike does not exercise rotation/renewal (that is G1 Task 5's territory), but keeping the
// same shape means a future spike can lift this file verbatim again.
//
//   GET  /jwks.json           -> the public JWK set the broker's OAuth profile fetches
//   POST /token {org, sub?, ...} -> { token }  (request body IS the JWT payload; iss/sub/aud/
//                                 iat/exp are stamped by the issuer and always win).
//                                 `org` is REQUIRED and selects the audience 'sm-mesh-acl-<org>'
//                                 (round 2 MAJOR: a ticket is bound to ONE organization's VPN).
//                                 `sub` is either OMITTED (the issuer generates one from the
//                                 reserved browser grammar) or must MATCH that grammar (round 2
//                                 BLOCKER: it stands for the id of the browser's provisioned
//                                 grant, resolved server-side, never a caller-chosen username).
//                                 A violating sub or unknown org is a 400, no token.
//   POST /raw-token {org?, aud?, sub, ...} -> { token }  MEASUREMENT ONLY. Same as /token but
//                                 WITHOUT the sub grammar, so tests can re-prove that the escape
//                                 the rule closes is real (a rule that is never shown to be
//                                 load-bearing proves nothing). `aud` overrides the audience.
//                                 A product issuer has NO such endpoint.
//   POST /ticket  (Authorization: Bearer <principal credential>, EMPTY body) -> { token }
//                                 ROUND 3 (the product-shaped path). The issuer authenticates the
//                                 caller as a PRINCIPAL (spike stand-in: a static bearer credential;
//                                 real authentication and the RBAC check are NOT what is measured)
//                                 and takes `sub`, the organization/audience and the lifetime ONLY
//                                 from that principal's server-side grant record (PRINCIPALS below).
//                                 The request carries none of them: ANY body field or query
//                                 parameter that would set one is HTTP 400 (no token), and the
//                                 lifetime is fixed at TICKET_TTL_S. No credential / unknown
//                                 credential is HTTP 401.
//   GET  /stats                -> { kid, kids, jwksFetchCount, jwksFetches[] }
//
// ⚠️ /token and /raw-token have NO CALLER AUTHENTICATION. /token enforces the reserved-grammar
// allow-list and the organization audience (what legs 16a-16d and 17a-17e measure) but takes
// `sub`, `org` and `expiresIn` straight from the request body. That is a FIXTURE, not the product
// shape: leg 19c measures that any caller can mint another grant's ticket through it (and a
// 3650-day one). The principal-to-grant rule is only in /ticket.
//
// Listens on :8091. The broker reaches it at http://host.docker.internal:8091/jwks.json.

import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { generateKeyPair, exportJWK, importJWK, SignJWT } from 'jose';

const KEYS = new URL('./keys.json', import.meta.url).pathname;
let stored;
if (existsSync(KEYS)) {
  stored = JSON.parse(readFileSync(KEYS, 'utf8')); // restart: same keys, same kids
} else {
  const { publicKey, privateKey } = await generateKeyPair('RS256', { extractable: true });
  const pub = { ...(await exportJWK(publicKey)), kid: 'acl-spike-1', use: 'sig', alg: 'RS256' };
  const priv = { ...(await exportJWK(privateKey)), kid: 'acl-spike-1', alg: 'RS256' };
  stored = { keys: [pub], privateJwks: [priv], kid: 'acl-spike-1' };
  writeFileSync(KEYS, JSON.stringify(stored));
}
export const state = { ...stored, privateKey: null };

async function activate() {
  state.privateKey = await importJWK(
    state.privateJwks.find((k) => k.kid === state.kid),
    'RS256'
  );
}
await activate();

const jwksFetches = [];

// THE SUB RULE (review round 1 MAJOR 2, extended in round 2 BLOCKER).
//
// Two independent hazards meet at the JWT `sub`:
//
//  (1) STREAM SUBSTITUTION. The zero-write stream route substitutes the session's client
//      username — the JWT `sub` — into `sm/v1/<org>/stream/$client-username/>`. Solace documents
//      that "any MQTT client IDs or client usernames that contain "/" characters will never be
//      able to match the `$client-id` or `$client-username` elements of an ACL rule" and that
//      "Substitution variables must appear alone at any level in the topic exception"
//      (https://docs.solace.com/Security/Granting-Clients-Access.htm, fetched 2026-09-30). The
//      BROKER DOES NOT BEHAVE AS DOCUMENTED: a `sub` of `stream-B/s1` connects, falls through to
//      `default`, and substitutes into `sm/v1/orgA/stream/stream-B/s1/>` — inside stream-B's
//      branch (measured, tests/04 14-escape). Hence the character deny-list below.
//
//  (2) IDENTITY BINDING (round 2). The OAuth `sub` becomes the broker's client username. EVERY
//      provisioned client username is therefore reachable over OAuth by whoever controls `sub`:
//      `sm-internal-<vpn>` (publishes anywhere in sm/v1/>, subscribes sm/v1/>), the `*-ctl` and
//      `*-abl-*` identities (guaranteed everything) — Solace documents no way to restrict a
//      client username to one authentication scheme (Client-Authorization-Overview, fetched
//      2026-09-30: the page contains no such statement). A character deny-list cannot stop
//      `sub="sm-internal-orgA"`: it contains none of the forbidden characters (measured by the
//      round-2 reviewer: 200 OK, then a forged publish). So the issuer mints `sub` ONLY from a
//      RESERVED BROWSER GRAMMAR, an allow-list, not a deny-list:
//
//          BROWSER_SUB = ^br-[0-9a-f]{16}$      ("br-" + 16 lowercase hex = a server-generated id)
//
//      Provisioning (provision.sh, and tests/07 from SEMP) guarantees the other half: EVERY client
//      username matching this grammar uses client profile `sm-browser`, and NO internal, control or
//      ablation username matches it. The two halves together are what "a browser is confined"
//      rests on; neither alone is enough.
//
// The deny-list is kept as the FIRST check so a hostile value is reported with the reason a
// reviewer expects ("contains forbidden character") rather than a bare grammar miss.
const SUB_DENY = /[\/*>#+\s]/u;
export const BROWSER_SUB = /^br-[0-9a-f]{16}$/;
export function subViolation(sub) {
  if (typeof sub !== 'string' || sub.length === 0) return 'sub must be a non-empty string';
  if (sub.length > 128) return 'sub longer than 128 characters';
  const m = SUB_DENY.exec(sub);
  if (m) return `sub contains forbidden character ${JSON.stringify(m[0])}`;
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/u.test(sub)) return 'sub contains a control character';
  if (!BROWSER_SUB.test(sub)) return 'sub is not in the reserved browser grammar ^br-[0-9a-f]{16}$';
  return null;
}
const ORGS = new Set(['orgA', 'orgB']);

// ROUND 3 (MAJOR): the server-side PRINCIPAL -> GRANT record. Credential -> principal -> the ONE
// grant that principal holds (organization + the id that becomes the JWT `sub` = the broker
// client username). Stand-in for "the authenticated, RBAC-checked principal's grant record".
// The grant ids are those provisioned by provision.sh (d001 = [orgA,su1,dev]; c102 =
// composed [orgA,su1,prod]) or unprovisioned stream-only ids that fall through to `default`.
//
// ROUND 4 (MINOR 1): this is a MAP, not a plain object. With `PRINCIPALS[cred]` on a plain
// object a credential that names an Object.prototype key (`__proto__`, `constructor`, `toString`,
// `hasOwnProperty`, ...) resolved to a non-undefined value, was treated as a known principal, and
// the issuer signed a ticket with no `sub` (reviewer probe, measured against the round-3 issuer:
// HTTP 200 for each of those four). `Map.get` only ever returns entries that were `set`.
export const PRINCIPALS = new Map([
  ['cred-p-dev',    { id: 'p-dev',    org: 'orgA', sub: 'br-000000000000d001' }],
  ['cred-p-prod',   { id: 'p-prod',   org: 'orgA', sub: 'br-000000000000c102' }],
  ['cred-p-stream', { id: 'p-stream', org: 'orgA', sub: 'br-00000000000000a1' }],
  ['cred-p-orgb',   { id: 'p-orgb',   org: 'orgB', sub: 'br-00000000000000b2' }],
]);
export const TICKET_TTL_S = 900; // the ONLY lifetime /ticket issues
export const audienceFor = (org) => `sm-mesh-acl-${org}`;
const generateSub = () => 'br-' + randomBytes(8).toString('hex');

createServer(async (req, res) => {
  if (req.method === 'GET' && req.url.startsWith('/jwks.json')) {
    jwksFetches.push({ at: new Date().toISOString(), url: req.url, kids: state.keys.map((k) => k.kid) });
    res.setHeader('content-type', 'application/json');
    return res.end(JSON.stringify({ keys: state.keys }));
  }
  if (req.method === 'GET' && req.url === '/stats') {
    res.setHeader('content-type', 'application/json');
    return res.end(JSON.stringify({
      kid: state.kid,
      kids: state.keys.map((k) => k.kid),
      jwksFetchCount: jwksFetches.length,
      jwksFetches: jwksFetches.slice(-20),
    }));
  }
  if (req.method === 'POST' && req.url.split('?')[0] === '/ticket') {
    const json = (code, obj) => { res.statusCode = code; res.setHeader('content-type', 'application/json'); return res.end(JSON.stringify(obj)); };
    let raw = '';
    for await (const c of req) raw += c;
    const m = /^Bearer (.+)$/.exec(req.headers.authorization ?? '');
    const principal = m ? PRINCIPALS.get(m[1]) : undefined;   // Map lookup: prototype keys are not principals (round 4)
    if (!principal) return json(401, { error: 'unauthenticated', reason: 'a known principal credential is required' });
    // The request carries NOTHING that selects a grant, an organization or a lifetime.
    let body = {};
    if (raw.trim() !== '') { try { body = JSON.parse(raw); } catch { return json(400, { error: 'bad-request', reason: 'body is not JSON' }); } }
    const fields = body && typeof body === 'object' && !Array.isArray(body) ? Object.keys(body) : ['<non-object body>'];
    const q = req.url.includes('?') ? [...new URLSearchParams(req.url.split('?')[1]).keys()] : [];
    if (fields.length > 0 || q.length > 0) {
      return json(400, { error: 'ticket-request-carries-claims', reason: `the ticket request must be empty; sub, organization, audience and lifetime come from the principal's grant record (got body fields [${fields}] query [${q}])` });
    }
    const now = Math.floor(Date.now() / 1000);
    const token = await new SignJWT({ scopes: [] })
      .setProtectedHeader({ alg: 'RS256', kid: state.kid })
      .setIssuer('sm-spike-acl-issuer')
      .setSubject(principal.sub)
      .setAudience(audienceFor(principal.org))
      .setIssuedAt(now)
      .setExpirationTime(now + TICKET_TTL_S)
      .sign(state.privateKey);
    return json(200, { token });
  }
  if (req.method === 'POST' && (req.url === '/token' || req.url === '/raw-token')) {
    let body = '';
    for await (const c of req) body += c;
    const claims = JSON.parse(body);
    let { sub, org, aud, expiresIn = '15m', ...rest } = claims;
    const reject = (error, reason) => {
      res.statusCode = 400;
      res.setHeader('content-type', 'application/json');
      return res.end(JSON.stringify({ error, reason }));
    };
    if (req.url === '/token') {
      if (!ORGS.has(org)) return reject('org-rejected', `org must be one of ${[...ORGS]}`);
      if (sub === undefined) sub = generateSub();
      const why = subViolation(sub);
      if (why) return reject('sub-rejected', why);
      aud = audienceFor(org);
    } else {
      // measurement-only: no grammar, and the audience may be forced
      aud = aud ?? (ORGS.has(org) ? audienceFor(org) : 'sm-mesh-acl');
    }
    const token = await new SignJWT({ scopes: [], ...rest })
      .setProtectedHeader({ alg: 'RS256', kid: state.kid })
      .setIssuer('sm-spike-acl-issuer')
      .setSubject(sub)
      .setAudience(aud)
      .setIssuedAt()
      .setExpirationTime(expiresIn)
      .sign(state.privateKey);
    res.setHeader('content-type', 'application/json');
    return res.end(JSON.stringify({ token }));
  }
  res.statusCode = 404;
  res.end();
}).listen(8091, process.env.ISSUER_HOST ?? '127.0.0.1');
console.log(`issuer on ${process.env.ISSUER_HOST ?? '127.0.0.1'}:8091`);
