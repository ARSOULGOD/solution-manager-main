// Gate-G1 spike token issuer — throwaway harness, NOT product code.
//
// Simulates the console-gateway's ticket-minting role from BE-D5:
//   - RS256 keypair PERSISTED to keys.json and reloaded on restart. A gateway restart must
//     never invalidate live tickets (the BE-D5 persistence requirement); Task 5 tests exactly
//     that, and also rotation with an overlap window.
//   - GET  /jwks.json           -> the public JWK set the broker's OAuth profile fetches
//   - POST /token {sub, ...anyOtherClaims} -> { token }
//     The request body IS the JWT payload (Task 3): every field is signed into the token, so
//     candidates can mint whatever claim they are testing (e.g. `groups`). The registered
//     claims iss/sub/aud/iat/exp are stamped by the issuer and always override the body.
//     One reserved field: `expiresIn` (Task 4) is a minting DIRECTIVE, not a claim — it sets
//     the token lifetime (default '15m') and is stripped from the payload.
//   - POST /rotate               -> { kid, kids } (Task 5)
//     Generates a NEW keypair under a new kid, keeps the previous public JWK published
//     alongside it (BE-D5's overlap window), and signs every subsequent token with the new
//     key. At most two public keys are ever published, so a SECOND /rotate drops the oldest —
//     which is what ends the overlap and is what makes an old-key token finally die.
//   - GET  /stats                -> { kid, kids, jwksFetchCount, jwksFetches[] } (Task 5)
//     Measurement scaffolding only. Every GET /jwks.json is timestamped here, so the broker's
//     JWKS cache-refresh behaviour can be observed at the SOURCE (did the broker re-fetch?)
//     instead of being inferred from whether a login happened to succeed.
//
// Listens on :8090. The broker reaches it at http://host.docker.internal:8090/jwks.json.

import { createServer } from 'node:http';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { generateKeyPair, exportJWK, importJWK, SignJWT } from 'jose';

const KEYS = new URL('./keys.json', import.meta.url).pathname;
let stored;
if (existsSync(KEYS)) {
  stored = JSON.parse(readFileSync(KEYS, 'utf8')); // restart: same keys, same kids
} else {
  const { publicKey, privateKey } = await generateKeyPair('RS256', { extractable: true });
  const pub = { ...(await exportJWK(publicKey)), kid: 'spike-1', use: 'sig', alg: 'RS256' };
  const priv = { ...(await exportJWK(privateKey)), kid: 'spike-1', alg: 'RS256' };
  stored = { keys: [pub], privateJwks: [priv], kid: 'spike-1' };
  writeFileSync(KEYS, JSON.stringify(stored));
}
export const state = { ...stored, privateKey: null };

// The signing key is whichever private JWK matches the ACTIVE kid. Re-derived on every
// rotation, and persisted so a restart reloads exactly the same material.
async function activate() {
  state.privateKey = await importJWK(
    state.privateJwks.find((k) => k.kid === state.kid),
    'RS256'
  );
}
await activate();

function persist() {
  writeFileSync(
    KEYS,
    JSON.stringify({ keys: state.keys, privateJwks: state.privateJwks, kid: state.kid })
  );
}

// Task 5 — BE-D5's overlap window, as a mechanism rather than a doc sentence.
// Rotation publishes the NEW public JWK alongside the previous one and starts signing with
// the new one; the previous key stays verifiable until the NEXT rotation evicts it. Two
// published keys is the whole overlap: one rotation is safe for tokens already in flight,
// two rotations is what actually revokes them.
async function rotate() {
  const next = Math.max(0, ...state.keys.map((k) => Number(k.kid.split('-')[1]) || 0)) + 1;
  const kid = `spike-${next}`;
  const { publicKey, privateKey } = await generateKeyPair('RS256', { extractable: true });
  state.keys = [
    ...state.keys,
    { ...(await exportJWK(publicKey)), kid, use: 'sig', alg: 'RS256' },
  ].slice(-2); // overlap window = 2 published keys; the third rotation-in drops the oldest
  state.privateJwks = [...state.privateJwks, { ...(await exportJWK(privateKey)), kid, alg: 'RS256' }]
    .slice(-2);
  state.kid = kid;
  await activate();
  persist();
  return { kid, kids: state.keys.map((k) => k.kid) };
}

// Measurement scaffolding: when did the broker actually PULL the JWKS? Without this the
// refresh latency can only be inferred from whether a login succeeded, which conflates
// "the broker re-fetched" with "the broker re-fetched AND accepted the token".
const jwksFetches = [];

createServer(async (req, res) => {
  // Prefix-matched, not exact: a JWKS URL carrying a query string is ordinary, and a fetch
  // that fell through to the 404 branch would be a MISSED measurement rather than a visible
  // one — the worst kind of gap in a harness whose whole job is counting broker fetches.
  if (req.method === 'GET' && req.url.startsWith('/jwks.json')) {
    jwksFetches.push({ at: new Date().toISOString(), url: req.url, kids: state.keys.map((k) => k.kid) });
    res.setHeader('content-type', 'application/json');
    return res.end(JSON.stringify({ keys: state.keys }));
  }
  if (req.method === 'POST' && req.url === '/rotate') {
    const result = await rotate();
    res.setHeader('content-type', 'application/json');
    return res.end(JSON.stringify(result));
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
  if (req.method === 'POST' && req.url === '/token') {
    let body = '';
    for await (const c of req) body += c;
    // Task 3: the whole request body becomes the JWT payload, so a test can mint ANY claim
    // (candidate (b) needs `groups`) without the issuer growing a field per candidate.
    // `scopes: []` stays as a default so a body that omits it still produces the claim shape
    // Tasks 1-2 minted; anything in the body overrides it. `sub` is spread too and then
    // re-stamped by setSubject below, which is a no-op — the registered claims (iss, sub,
    // aud, iat, exp) are all set explicitly after the spread and therefore always win.
    // Task 4: `expiresIn` is a MINTING DIRECTIVE, not a claim. It is pulled OUT of the body so
    // it never lands in the payload, and drives setExpirationTime instead. The default stays
    // '15m', so a body that omits it produces byte-identical claims to Tasks 1-3. The
    // expired-ticket fixture (design §14) mints with expiresIn: '1s'.
    const claims = JSON.parse(body);
    const { sub, expiresIn = '15m', ...rest } = claims;
    const token = await new SignJWT({ scopes: [], ...rest })
      .setProtectedHeader({ alg: 'RS256', kid: state.kid })
      .setIssuer('sm-spike-issuer')
      .setSubject(sub)
      .setAudience('sm-mesh')
      .setIssuedAt()
      .setExpirationTime(expiresIn)
      .sign(state.privateKey);
    res.setHeader('content-type', 'application/json');
    return res.end(JSON.stringify({ token }));
  }
  res.statusCode = 404;
  res.end();
}).listen(8090);
console.log('issuer on :8090');
