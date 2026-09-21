// G1 baseline — throwaway spike test, NOT product code.
//
// Proves the shape the design's §5 production path assumes:
//   browser --(OAuth JWT)--> wss://localhost:9443/mesh --(nginx TLS terminate)--> broker
// and the invariant the whole notify design rests on (design §3): only backend/internal
// identities publish to sm/v1/…; browsers subscribe and nothing else.
//
// Deviations from the plan's Task 1 text, and why — see README "Deviations from the task text":
//   1. Import path. The SDK package has no `exports` map and its `main` is the UMD bundle, so
//      `import SolaceWebAppSDK from 'solace-webapp-sdk'` yields an empty module under Node ESM.
//      The ES build is imported by its explicit path.
//   2. The negative test cannot be written as `expect(publish(...)).rejects.toThrow()`. The SDK
//      publishes fire-and-forget (`session.send()`, no ack correlation) and registers no
//      REJECTED_MESSAGE_ERROR handler, so a broker publish denial NEVER surfaces as a rejected
//      promise — for direct OR guaranteed delivery. That is itself a G1 finding. The denial is
//      therefore proven from the broker's side instead, and strictly: the message must not
//      reach a witness subscriber that demonstrably receives legitimate traffic, AND the
//      broker's per-client `publishTopicAclRxDiscardedMsgCount` must increment by exactly the
//      number of denied publishes. That counter is specifically the publish-topic ACL discard
//      counter, so a passing test cannot be explained by a dropped connection.

import { describe, it, expect } from 'vitest';
// See deviation 1 above — the bare specifier resolves to the UMD build and imports as empty.
import SolaceWebAppSDK from 'solace-webapp-sdk/dist/solace-webapp-sdk.es.js';

const WSS = 'wss://localhost:9443/mesh'; // npm test sets NODE_TLS_REJECT_UNAUTHORIZED=0 for the self-signed spike certs
const VPN = 'sm-spike';
const SEMP = 'http://localhost:8080/SEMP/v2/monitor';
const SEMP_AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');

async function mint(claims) {
  const r = await fetch('http://localhost:8090/token', { method: 'POST', body: JSON.stringify(claims) });
  return (await r.json()).token;
}

const browser = async (claims) => SolaceWebAppSDK.connect({
  url: WSS, vpn: VPN, protocol: 'smf',
  auth: { type: 'oauth', accessToken: await mint(claims) },
});
const internal = () => SolaceWebAppSDK.connect({             // stands in for mesh-bridge — the ONLY publisher
  url: WSS, vpn: VPN, protocol: 'smf',
  auth: { type: 'basic', username: 'sm-internal', password: 'sm-internal' },
});

// The broker's publish-topic ACL discard counter for every live client session in the VPN,
// keyed by clientName.
//
// NOT filtered by clientUsername: an OAuth client whose `sub` has no matching clientUsername
// object is mapped by the broker to the VPN's `default` client username, so the browser
// session does not appear under 'op-1'. That mapping is itself a G1 finding (README).
async function aclPublishDiscards() {
  const url = `${SEMP}/msgVpns/${VPN}/clients`
    + `?select=clientName,clientUsername,publishTopicAclRxDiscardedMsgCount&count=100`;
  const r = await fetch(url, { headers: { Authorization: SEMP_AUTH } });
  const body = await r.json();
  return new Map((body.data ?? []).map((c) => [c.clientName, c]));
}

// Per-client increases in the ACL publish-discard counter between two snapshots.
function discardDeltas(before, after) {
  const out = [];
  for (const [name, c] of after) {
    const delta = (c.publishTopicAclRxDiscardedMsgCount ?? 0)
      - (before.get(name)?.publishTopicAclRxDiscardedMsgCount ?? 0);
    if (delta > 0) out.push({ clientName: name, clientUsername: c.clientUsername, delta });
  }
  return out;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

describe('G1 baseline', () => {
  it('browser subscribes over OAuth+TLS; the INTERNAL identity publishes; message arrives', async () => {
    const sub = await browser({ sub: 'op-1', tenant: 'acme', scopes: ['notify'] });
    // Await the subscribe before publishing: the broker confirms subscriptions asynchronously,
    // and publishing into an unconfirmed subscription is a race, not a test.
    let resolveGot;
    const got = new Promise((res) => { resolveGot = res; });
    await sub.subscribe('sm/v1/acme/notify/>', (m) => resolveGot(m.topic));
    const pub = await internal();
    await pub.publish('sm/v1/acme/notify/instance/i-1/health', { k: 'GREEN' });
    expect(await got).toBe('sm/v1/acme/notify/instance/i-1/health');
    await Promise.all([sub.disconnect(), pub.disconnect()]);
  }, 30000);

  it('a browser OAuth token can NEVER publish to any sm/v1 topic (design §3: only backend modules publish)', async () => {
    // Witness: sm-internal may subscribe to all of sm/v1/>. Anything the browser managed to
    // publish would land here.
    const witness = await internal();
    const heard = [];
    await witness.subscribe('sm/v1/>', (m) => heard.push(m.topic));

    const c = await browser({ sub: 'op-1', tenant: 'acme', scopes: ['notify', 'stream'] });
    const before = await aclPublishDiscards();

    await c.publish('sm/v1/acme/notify/instance/i-1/health', { k: 'GREEN' });
    await c.publish('sm/v1/acme/stream/g1/s1', { v: 1 });

    // Poll the broker's ACL discard counters — SEMP stats settle asynchronously.
    let deltas = [];
    let total = 0;
    for (let i = 0; i < 20 && total < 2; i++) {
      await sleep(250);
      deltas = discardDeltas(before, await aclPublishDiscards());
      total = deltas.reduce((n, d) => n + d.delta, 0);
    }

    // The invariant itself: nothing the browser published reached a subscriber...
    expect(heard).toEqual([]);
    // ...and the right red for it: the broker counted exactly two publish-topic ACL discards,
    // so the messages were refused on authorization rather than lost some other way...
    expect(total, JSON.stringify(deltas)).toBe(2);
    // ...none charged to the internal publisher, so it is the browser being denied...
    expect(deltas.map((d) => d.clientUsername)).not.toContain('sm-internal');
    // ...and the browser session is still up, so this is not a dropped connection.
    expect(c.isConnected()).toBe(true);

    // Positive control on the SAME witness subscription: a legitimately published message on
    // an sm/v1 topic does arrive, so "heard was empty" means denial and not a dead subscriber.
    const pub = await internal();
    await pub.publish('sm/v1/acme/control/ping', { ok: true });
    for (let i = 0; i < 20 && heard.length === 0; i++) await sleep(100);
    expect(heard).toEqual(['sm/v1/acme/control/ping']);

    await Promise.all([c.disconnect(), pub.disconnect(), witness.disconnect()]);
  }, 30000);
});
