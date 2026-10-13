// Exploratory probe, NOT a test: which JWT `sub` values escape the stream tier's
// `sm/v1/orgA/stream/$client-username/>` confinement, if the ticket issuer does NOT filter?
//
// For each candidate `sub` (minted through the rule-free /raw-token endpoint) it records:
//   - whether /token (WITH the sub rule) accepts it,
//   - whether the broker lets it connect (a connect that does not finish in 8 s is recorded as
//     hung — the SDK retries a refused login; hung is NOT an escape),
//   - which of a set of victim-branch subscriptions the broker ALLOWS.
// Output is one `C {json}` line per sub. Run:  NODE_TLS_REJECT_UNAUTHORIZED=0 node tools/hostile-sub-corpus.mjs
// Result of the run reported in the result doc: evidence/hostile-sub-corpus.log.

import { BR, oauthClient, connectObserved, trySubscribe, sleep, tryMint } from '../tests/lib/common.mjs';

setTimeout(() => { console.log('C HARD-TIMEOUT'); process.exit(1); }, 170000);

const V = BR.STREAM_B;   // round 2: the victim is a grammar-valid stream subject
const corpus = [
  `${V}/s1`, `${V}/>`, `${V}/*`, `${V}/`, `/${V}`,
  '>', '*', '#', '+', 'a b', '$SYS', '$client-username', '.', '..', 'a%2Fb', 'a\\b', 'a;b',
  `${V}%`, 'user.name@x-y_z', 'a=b', 'a:b', 'orgA-su1-dev', 'sm-internal-orgA', '#client-username',
  'ünï', 'a\tb', `${V}?`, `${V}∕s1`, '＞',
];
const victim = [
  `sm/v1/orgA/stream/${V}/s1`, `sm/v1/orgA/stream/${V}/>`,
  'sm/v1/orgA/stream/>', 'sm/v1/orgA/stream/*/s1',
];

for (const sub of corpus) {
  const rule = await tryMint({ org: 'orgA', sub });
  let conn;
  try {
    conn = await Promise.race([
      connectObserved('orgA', () => oauthClient('orgA', sub, { raw: true })),
      new Promise((_, rej) => setTimeout(() => rej(new Error('connect hung > 8 s')), 8000)),
    ]);
  } catch (e) {
    console.log('C', JSON.stringify({ sub, rule: rule.ok ? 'accept' : 'reject', connect: 'failed', msg: e.message.slice(0, 80) }));
    continue;
  }
  const out = {};
  for (const v of victim) out[v] = (await trySubscribe(conn.client, v)).outcome;
  await sleep(100);
  const escaped = Object.values(out).includes('allowed');
  console.log('C', JSON.stringify({
    sub, rule: rule.ok ? 'accept' : 'reject', clientUsername: conn.session.clientUsername,
    acl: conn.session.aclProfileName, escaped, out,
  }));
  await conn.client.disconnect();
}
process.exit(0);
