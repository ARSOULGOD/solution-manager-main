// Round 1 review BLOCKER 1 — throwaway spike test, NOT product code.
//
// Solace documents: "Subscribe-topic access controls are not considered when a consuming client
// binds directly to a durable endpoint (either a queue or topic endpoint)."
// (https://docs.solace.com/Security/Granting-Clients-Access.htm). So a subscribe ACL that
// perfectly confines DIRECT subscriptions (legs 1-10) does NOT confine a client that may BIND a
// queue subscribed to a sibling environment's topics — that is exactly the leak open item 35
// exists to prevent. Client profile flags (https://docs.solace.com/Security/Configuring-Client-
// Profiles.htm): allow-guaranteed-message-receive = "permit clients ... to bind to topic
// endpoints or queues"; allow-guaranteed-endpoint-create = "permit clients ... to create queues
// or topic endpoints" (durable AND temporary); allow-guaranteed-message-send.
//
// Design: three identities share the IDENTICAL ACL profile acl-orgA-su1-dev and differ only in
// client profile, so any difference in outcome is attributable to the client profile:
//     orgA-su1-dev      sm-browser     (all guaranteed flags off)   <- the browser
//     orgA-su1-dev-ctl  sm-internal    (all on)                     <- positive control
//     orgA-abl-*        sm-internal with exactly ONE flag off       <- ablations
// The bait: a queue subscribed to the SIBLING environment's topic sm/v1/orgA/su1/prod/notify/>,
// with other-permission `consume` (the worst case: a queue whose owner did not lock it down).

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  internal, oauthClient, connectObserved, trySubscribe, attempt, record, sleep, waitFor,
  sempConfig, sempMonitor, sempWrite, clientDetail, ACL_DENIED, advertisedCapabilities, spoofCapabilities, BR, NON_BROWSER, tempEndpoint, healthTopic, bindDurableTopicEndpoint,
} from './lib/common.mjs';

const PROD_TOPIC = 'sm/v1/orgA/su1/prod/notify/solution/s1/instance/i-prod-secret/health';
const Q_OPEN = 'sm-q-prod';             // other-permission consume: the worst case
const Q_LOCKED = 'sm-q-prod-locked';    // other-permission no-access: the normal posture
const Q_NEW = 'sm-q-browser-created';   // must never come to exist via a browser identity
const Q_NEW_CTL = 'sm-q-ctl-created';
const withTimeout = (p, ms = 12000) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(Object.assign(new Error(`no answer in ${ms} ms`), { name: 'Timeout' })), ms))]);
const NORETRY = { reconnectAttempts: 0, acknowledgeMode: 'auto' };

let pub;
const spoolCount = async (q) => (await sempMonitor(`/msgVpns/orgA/queues/${q}/msgs?count=100`)).data?.length ?? -1;
const queueExists = async (q) => (await sempConfig(`/msgVpns/orgA/queues/${q}`)).status === 200;
const bait = async (tag) => pub.publishGuaranteed(PROD_TOPIC, { tag });

async function mkQueue(name, permission) {
  await sempWrite('DELETE', `/msgVpns/orgA/queues/${name}`);
  const q = await sempWrite('POST', '/msgVpns/orgA/queues', { msgVpnName: 'orgA', queueName: name, owner: 'sm-internal-orgA', permission, ingressEnabled: true, egressEnabled: true, accessType: 'non-exclusive' });
  expect(q.ok, JSON.stringify(q.error)).toBe(true);
  const s = await sempWrite('POST', `/msgVpns/orgA/queues/${name}/subscriptions`, { msgVpnName: 'orgA', queueName: name, subscriptionTopic: 'sm/v1/orgA/su1/prod/notify/>' });
  expect(s.ok, JSON.stringify(s.error)).toBe(true);
}

beforeAll(async () => {
  pub = await internal('orgA');
  await mkQueue(Q_OPEN, 'consume');
  await mkQueue(Q_LOCKED, 'no-access');
  for (const q of [Q_NEW, Q_NEW_CTL]) await sempWrite('DELETE', `/msgVpns/orgA/queues/${q}`);
}, 60000);

afterAll(async () => {
  await pub?.disconnect();
  for (const q of [Q_OPEN, Q_LOCKED, Q_NEW, Q_NEW_CTL]) await sempWrite('DELETE', `/msgVpns/orgA/queues/${q}`);
});

describe('BLOCKER 1 — a browser identity can neither bind, create, browse, nor receive guaranteed messages', () => {
  it('bait is live: prod-subscribed queues spool the sibling-environment event (both queues)', async () => {
    await bait('bait-1');
    expect(await waitFor(async () => (await spoolCount(Q_OPEN)) === 1 && (await spoolCount(Q_LOCKED)) === 1)).toBe(true);
    record({ leg: '15-bait', attempted: `internal publisher publishGuaranteed ${PROD_TOPIC}`, expected: 'both bait queues spool 1 message', observed: { [Q_OPEN]: await spoolCount(Q_OPEN), [Q_LOCKED]: await spoolCount(Q_LOCKED) }, pass: true });
  }, 30000);

  it('the ACL still refuses the DIRECT subscription to the same prod topic (so a queue bind would be a bypass, not a grant)', async () => {
    const { client } = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    const r = await trySubscribe(client, 'sm/v1/orgA/su1/prod/notify/>');
    expect(r).toMatchObject(ACL_DENIED);
    record({ leg: '15-direct', attempted: 'orgA-su1-dev direct subscribe sm/v1/orgA/su1/prod/notify/>', expected: 'refused', observed: r, pass: true });
    await client.disconnect();
  }, 30000);

  it('BROWSER (sm-browser): bind to an existing prod-subscribed queue is REFUSED, for both an other-permission=consume queue and a no-access queue; nothing delivered, spool untouched, no flow bound', async () => {
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    expect(session.clientProfileName).toBe('sm-browser');
    expect(session.aclProfileName).toBe('acl-orgA-su1-dev');
    const got = [];
    const rOpen = await attempt(() => withTimeout(client.consume(Q_OPEN, (m) => got.push(m.topic), NORETRY)));
    const rLocked = await attempt(() => withTimeout(client.consume(Q_LOCKED, (m) => got.push(m.topic), NORETRY)));
    await sleep(800);
    const d = await clientDetail('orgA', session.clientName);
    const flows = (await sempMonitor(`/msgVpns/orgA/queues/${Q_OPEN}/txFlows?count=50`)).data ?? [];
    record({
      leg: '15a', attempted: `orgA-su1-dev (sm-browser) consume ${Q_OPEN} (other-permission=consume) and ${Q_LOCKED} (no-access)`,
      expected: 'both refused; 0 messages delivered; bait queues still hold 1 message each; no tx flow bound',
      observed: { open: rOpen, locked: rLocked, delivered: got, spoolOpen: await spoolCount(Q_OPEN), spoolLocked: await spoolCount(Q_LOCKED), txFlowsOnOpenQueue: flows.length, brokerCounters: { bindRequestCount: d.bindRequestCount, bindSuccessCount: d.bindSuccessCount, guaranteedDeniedBindFailureCount: d.guaranteedDeniedBindFailureCount, otherBindFailureCount: d.otherBindFailureCount } },
      pass: rOpen.outcome === 'rejected' && rLocked.outcome === 'rejected' && got.length === 0,
    });
    expect(rOpen.outcome, JSON.stringify(rOpen)).toBe('rejected');
    expect(rLocked.outcome, JSON.stringify(rLocked)).toBe('rejected');
    expect(got).toEqual([]);
    expect(await spoolCount(Q_OPEN)).toBe(1);
    expect(flows.length).toBe(0);
    expect(d.bindSuccessCount).toBe(0);
    await client.disconnect();
  }, 60000);

  it('BROWSER (sm-browser): createIfMissing is REFUSED for a new name and for an existing name; no queue comes to exist', async () => {
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    const rNew = await attempt(() => withTimeout(client.consume(Q_NEW, () => {}, { ...NORETRY, createIfMissing: true })));
    const rExisting = await attempt(() => withTimeout(client.consume(Q_OPEN, () => {}, { ...NORETRY, createIfMissing: true })));
    await sleep(500);
    const exists = await queueExists(Q_NEW);
    const d = await clientDetail('orgA', session.clientName);
    record({
      leg: '15b', attempted: `orgA-su1-dev (sm-browser) consume(${Q_NEW}, createIfMissing) and consume(${Q_OPEN}, createIfMissing)`,
      expected: 'both refused; queue sm-q-browser-created does not exist afterwards',
      observed: { newQueue: rNew, existingQueue: rExisting, newQueueExistsAfter: exists, bindSuccessCount: d.bindSuccessCount },
      pass: rNew.outcome === 'rejected' && rExisting.outcome === 'rejected' && !exists,
    });
    expect(rNew.outcome, JSON.stringify(rNew)).toBe('rejected');
    expect(rExisting.outcome, JSON.stringify(rExisting)).toBe('rejected');
    expect(exists).toBe(false);
    await client.disconnect();
  }, 60000);

  it('BROWSER (sm-browser): non-destructive queue BROWSE of the prod queue is REFUSED and delivers nothing', async () => {
    const { client } = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    const got = [];
    const r = await attempt(() => withTimeout(client.browseQueue(Q_OPEN, (m) => got.push(m.topic))));
    await sleep(500);
    record({ leg: '15c', attempted: `orgA-su1-dev (sm-browser) browseQueue ${Q_OPEN}`, expected: 'refused; nothing delivered', observed: { result: r, delivered: got }, pass: r.outcome === 'rejected' && got.length === 0 });
    expect(r.outcome, JSON.stringify(r)).toBe('rejected');
    expect(got).toEqual([]);
    await client.disconnect();
  }, 60000);

  it('POSITIVE CONTROL (sm-internal client profile, IDENTICAL ACL): the SAME bind SUCCEEDS and RECEIVES the sibling-environment event — the leak is real without the client profile', async () => {
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', NON_BROWSER.CTL, { raw: true }));
    expect(session.clientProfileName).toBe('sm-internal');
    expect(session.aclProfileName).toBe('acl-orgA-su1-dev');
    const direct = await trySubscribe(client, 'sm/v1/orgA/su1/prod/notify/>');
    const got = [];
    const r = await attempt(() => withTimeout(client.consume(Q_OPEN, (m) => got.push({ topic: m.topic, payload: m.payload }), NORETRY)));
    await waitFor(() => got.length >= 1, { timeoutMs: 5000 });
    record({
      leg: '15d-control', attempted: `orgA-su1-dev-ctl (same ACL, sm-internal) consume ${Q_OPEN}`,
      expected: 'bind succeeds and the prod event is delivered (proves the ACL is bypassed by a bind)',
      observed: { directSubscribeToSameTopic: direct, bind: r, delivered: got, spoolAfter: await spoolCount(Q_OPEN) },
      pass: r.outcome === 'resolved' && got.length === 1,
    });
    expect(direct, 'same identity, same topic, DIRECT subscribe: refused').toMatchObject(ACL_DENIED);
    expect(r.outcome, JSON.stringify(r)).toBe('resolved');
    expect(got.map((g) => g.topic)).toEqual([PROD_TOPIC]);
    await client.disconnect();
  }, 60000);

  it('POSITIVE CONTROL: createIfMissing SUCCEEDS under sm-internal, and the queue then exists on the broker (owned by the creating client username)', async () => {
    const { client } = await connectObserved('orgA', () => oauthClient('orgA', NON_BROWSER.CTL, { raw: true }));
    const r = await attempt(() => withTimeout(client.consume(Q_NEW_CTL, () => {}, { ...NORETRY, createIfMissing: true })));
    const q = await sempConfig(`/msgVpns/orgA/queues/${Q_NEW_CTL}`);
    record({ leg: '15e-control', attempted: `orgA-su1-dev-ctl consume(${Q_NEW_CTL}, createIfMissing)`, expected: 'resolves; queue exists', observed: { result: r, exists: q.status === 200, owner: q.data?.owner }, pass: r.outcome === 'resolved' && q.status === 200 });
    expect(r.outcome, JSON.stringify(r)).toBe('resolved');
    expect(q.status).toBe(200);
    await client.disconnect();
  }, 60000);

  it('POSITIVE CONTROL: browse of the consume-permission queue succeeds under sm-internal (so the browser refusal in 15c is attributable to the client profile), and a no-access queue is still refused by the broker queue permission', async () => {
    await bait('bait-2');
    await waitFor(async () => (await spoolCount(Q_OPEN)) >= 1);
    const { client } = await connectObserved('orgA', () => oauthClient('orgA', NON_BROWSER.CTL, { raw: true }));
    const got = [];
    const r = await attempt(() => withTimeout(client.browseQueue(Q_OPEN, (m) => got.push(m.topic))));
    await waitFor(() => got.length >= 1, { timeoutMs: 5000 });
    const locked = await attempt(() => withTimeout(client.browseQueue(Q_LOCKED, () => {})));
    record({ leg: '15f-control', attempted: `orgA-su1-dev-ctl browseQueue ${Q_OPEN} (other-permission=consume) and ${Q_LOCKED} (no-access)`, expected: 'browse of the consume-permission queue succeeds and delivers the prod event; the no-access queue is refused by the BROKER (queue permission)', observed: { open: r, delivered: got, spoolStillHeld: await spoolCount(Q_OPEN), lockedQueue: locked }, pass: r.outcome === 'resolved' && got.length === 1 });
    expect(r.outcome, JSON.stringify(r)).toBe('resolved');
    expect(got).toEqual([PROD_TOPIC]);
    expect(locked.outcome).toBe('rejected');
    await client.disconnect();
  }, 60000);
});

describe('WIRE LEVEL — a hostile client that forces solclientjs to send the operation (capability table spoofed)', () => {
  it('what the broker advertised at login: sm-browser vs sm-internal (measured capability tables)', async () => {
    const b = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    const c = await connectObserved('orgA', () => oauthClient('orgA', NON_BROWSER.CTL, { raw: true }));
    const capB = advertisedCapabilities(b.client);
    const capC = advertisedCapabilities(c.client);
    record({ leg: '15i-caps', attempted: 'read the capability table each session was advertised', expected: 'browser: no guaranteed consume/publish/browse/endpoint; control: all', observed: { 'sm-browser': capB, 'sm-internal': capC }, pass: !capB.CONSUME && !capB.PUBLISH && capC.CONSUME && capC.PUBLISH });
    expect(capB.CONSUME).toBe(false);
    expect(capB.PUBLISH).toBe(false);
    expect(capC.CONSUME).toBe(true);
    expect(capC.PUBLISH).toBe(true);
    await Promise.all([b.client.disconnect(), c.client.disconnect()]);
  }, 30000);

  it('SPOOFED BROWSER: bind to the existing prod queue is put on the wire and the BROKER refuses it; nothing delivered, spool untouched, broker counts the denied bind', async () => {
    await waitFor(async () => (await spoolCount(Q_LOCKED)) >= 1);
    const beforeOpen = await spoolCount(Q_OPEN);
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    spoofCapabilities(client);
    const got = [];
    const rOpen = await attempt(() => withTimeout(client.consume(Q_OPEN, (m) => got.push(m.topic), NORETRY)));
    const rLocked = await attempt(() => withTimeout(client.consume(Q_LOCKED, (m) => got.push(m.topic), NORETRY)));
    await sleep(800);
    const d = await clientDetail('orgA', session.clientName);
    const flows = (await sempMonitor(`/msgVpns/orgA/queues/${Q_OPEN}/txFlows?count=50`)).data ?? [];
    record({
      leg: '15a-wire', attempted: `orgA-su1-dev (sm-browser), capability table forced to capable: consume ${Q_OPEN} and ${Q_LOCKED}`,
      expected: 'bind reaches the broker (bindRequestCount > 0) and is refused (bindSuccessCount == 0); nothing delivered; spool untouched',
      observed: { open: rOpen, locked: rLocked, delivered: got, spoolOpenBefore: beforeOpen, spoolOpenAfter: await spoolCount(Q_OPEN), txFlows: flows.length, brokerCounters: { bindRequestCount: d.bindRequestCount, bindSuccessCount: d.bindSuccessCount, guaranteedDeniedBindFailureCount: d.guaranteedDeniedBindFailureCount, otherBindFailureCount: d.otherBindFailureCount, disabledBindFailureCount: d.disabledBindFailureCount } },
      pass: rOpen.outcome === 'rejected' && got.length === 0 && d.bindSuccessCount === 0,
    });
    expect(rOpen.outcome, JSON.stringify(rOpen)).toBe('rejected');
    expect(rLocked.outcome, JSON.stringify(rLocked)).toBe('rejected');
    expect(got).toEqual([]);
    expect(d.bindRequestCount, 'the bind must actually have reached the broker, or this leg proves nothing').toBeGreaterThan(0);
    expect(d.bindSuccessCount).toBe(0);
    expect(flows.length).toBe(0);
    expect(await spoolCount(Q_OPEN)).toBe(beforeOpen);
    await client.disconnect();
  }, 60000);

  it('SPOOFED BROWSER: createIfMissing and browse are put on the wire and the BROKER refuses them; no queue is created', async () => {
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    spoofCapabilities(client);
    const rNew = await attempt(() => withTimeout(client.consume(Q_NEW, () => {}, { ...NORETRY, createIfMissing: true })));
    const got = [];
    const rBrowse = await attempt(() => withTimeout(client.browseQueue(Q_OPEN, (m) => got.push(m.topic))));
    await sleep(500);
    const exists = await queueExists(Q_NEW);
    const d = await clientDetail('orgA', session.clientName);
    record({
      leg: '15b-wire', attempted: `orgA-su1-dev (sm-browser), capability table forced to capable: consume(${Q_NEW}, createIfMissing) and browseQueue ${Q_OPEN}`,
      expected: 'both reach the broker and are refused; queue not created; nothing delivered',
      observed: { create: rNew, browse: rBrowse, delivered: got, queueExistsAfter: exists, brokerCounters: { bindRequestCount: d.bindRequestCount, bindSuccessCount: d.bindSuccessCount, guaranteedDeniedBindFailureCount: d.guaranteedDeniedBindFailureCount, otherBindFailureCount: d.otherBindFailureCount } },
      pass: rNew.outcome === 'rejected' && rBrowse.outcome === 'rejected' && !exists && got.length === 0,
    });
    expect(rNew.outcome, JSON.stringify(rNew)).toBe('rejected');
    expect(rBrowse.outcome, JSON.stringify(rBrowse)).toBe('rejected');
    expect(exists).toBe(false);
    expect(got).toEqual([]);
    expect(d.bindSuccessCount).toBe(0);
    await client.disconnect();
  }, 60000);
});

describe('Ablation — which single flag is load-bearing for which operation (observed, then pinned as regression)', () => {
  for (const [user, flag] of [['orgA-abl-create-off', 'allowGuaranteedEndpointCreateEnabled=false'], ['orgA-abl-recv-off', 'allowGuaranteedMsgReceiveEnabled=false'], ['orgA-abl-send-off', 'allowGuaranteedMsgSendEnabled=false']]) {
    it(`${user} (${flag}, everything else on): bind existing / createIfMissing / browse`, async () => {
      const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', user, { raw: true }));
      expect(session.aclProfileName).toBe('acl-orgA-su1-dev');
      const bind = await attempt(() => withTimeout(client.consume(Q_OPEN, () => {}, NORETRY)));
      const create = await attempt(() => withTimeout(client.consume(`sm-q-abl-${user}`, () => {}, { ...NORETRY, createIfMissing: true })));
      const created = await queueExists(`sm-q-abl-${user}`);
      const browse = await attempt(() => withTimeout(client.browseQueue(Q_OPEN, () => {})));
      await sempWrite('DELETE', `/msgVpns/orgA/queues/sm-q-abl-${user}`);
      // Same three operations again with the library's local capability gate forced open, so a
      // refusal here is the BROKER's (wire-level) and not solclientjs's.
      spoofCapabilities(client);
      const bindW = await attempt(() => withTimeout(client.consume(Q_OPEN, () => {}, NORETRY)));
      const createW = await attempt(() => withTimeout(client.consume(`sm-q-abl-w-${user}`, () => {}, { ...NORETRY, createIfMissing: true })));
      const createdW = await queueExists(`sm-q-abl-w-${user}`);
      await sempWrite('DELETE', `/msgVpns/orgA/queues/sm-q-abl-w-${user}`);
      record({ leg: '15g-ablation', attempted: `${user} (${flag}): consume existing ${Q_OPEN}; consume(createIfMissing); browseQueue — plain, then with the local capability gate forced open`, expected: 'record only', observed: { plain: { bind: bind.outcome, create: create.outcome, queueCreated: created, browse: browse.outcome, detail: { bind, create, browse } }, wire: { bind: bindW.outcome, create: createW.outcome, queueCreated: createdW, detail: { bindW, createW } } }, pass: null });
      await client.disconnect();
      // Pinned AFTER observation (a regression pin, not a prior hypothesis): which single flag
      // is load-bearing for which operation.
      if (user === 'orgA-abl-create-off') {
        expect([bind.outcome, bindW.outcome], 'create-off does NOT stop a bind').toEqual(['resolved', 'resolved']);
        expect([create.outcome, createW.outcome, created, createdW], 'create-off stops creation (broker-side)').toEqual(['rejected', 'rejected', false, false]);
      } else if (user === 'orgA-abl-recv-off') {
        expect([bind.outcome, bindW.outcome], 'recv-off stops a bind (plain: library gate, wire: broker)').toEqual(['rejected', 'rejected']);
        expect(createdW, 'recv-off does NOT stop queue CREATION on the wire').toBe(true);
      } else {
        expect([bind.outcome, bindW.outcome, create.outcome, createW.outcome, created, createdW], 'send-off stops neither').toEqual(['resolved', 'resolved', 'resolved', 'resolved', true, true]);
      }
    }, 90000);
  }
});

describe('Shared subscriptions — another route around the topic ACL?', () => {
  it('the topic ACL applies to a shared subscription\'s topic: a prod shared subscription is refused even under sm-internal (shared-subscription flag ON); a granted-scope one is measured', async () => {
    const { client } = await connectObserved('orgA', () => oauthClient('orgA', NON_BROWSER.CTL, { raw: true }));
    const prod = await trySubscribe(client, '#share/g1/sm/v1/orgA/su1/prod/notify/>');
    const own = await trySubscribe(client, '#share/g1/sm/v1/orgA/su1/dev/notify/>');
    record({ leg: '15h-shared', attempted: 'orgA-su1-dev-ctl (sm-internal, shared subs ON) subscribe #share/g1/<prod topic> and #share/g1/<dev topic>', expected: 'record only', observed: { prod, own }, pass: null });
    await client.disconnect();
    const b = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    const bProd = await trySubscribe(b.client, '#share/g1/sm/v1/orgA/su1/prod/notify/>');
    const bOwn = await trySubscribe(b.client, '#share/g1/sm/v1/orgA/su1/dev/notify/>');
    record({ leg: '15h-shared', attempted: 'orgA-su1-dev (sm-browser, shared subs OFF) same two subscribes', expected: 'record only', observed: { prod: bProd, own: bOwn }, pass: null });
    expect(prod, 'shared subscription to a sibling env must not be allowed by the ACL').toMatchObject(ACL_DENIED);
    expect(bProd).toMatchObject({ outcome: expect.stringMatching(/acl-denied|other/) });
    await b.client.disconnect();
  }, 60000);
});

// =====================================================================================
// ROUND 3 MINOR 4 — TEMPORARY queues and topic endpoints. The owner-authorised fix (1) is
// "browsers get a client profile that cannot bind or create queues"; `sm-browser` is still
// ADVERTISED TEMP_ENDPOINT=true (15i), so this is measured with raw consumers on the session,
// plain and with the local capability gate forced open, each with an `sm-internal` control that
// has the IDENTICAL ACL. Docs: allow-guaranteed-endpoint-create governs "durable and
// non-durable/temporary queues or topic endpoints" (Configuring Client Profiles, fetched
// 2026-09-30); the page states nothing about ACL treatment of subscriptions on temporary
// endpoints, so that is MEASURED here (control legs), not assumed.
// =====================================================================================
describe('Temporary endpoints — a browser identity can create neither a temporary queue nor a temporary topic endpoint', () => {
  const DEV = 'sm/v1/orgA/su1/dev/notify/>';
  const PROD = 'sm/v1/orgA/su1/prod/notify/>';
  const devHealth = healthTopic({ org: 'orgA', su: 'su1', env: 'dev' });
  const prodHealth = healthTopic({ org: 'orgA', su: 'su1', env: 'prod' });
  const counters = (d) => ({ bindRequestCount: d.bindRequestCount, bindSuccessCount: d.bindSuccessCount, guaranteedDeniedBindFailureCount: d.guaranteedDeniedBindFailureCount, otherBindFailureCount: d.otherBindFailureCount });
  const closeMc = (r) => { try { r.mc?.disconnect(); } catch { /* already down */ } };

  for (const [label, spoof] of [['plain', false], ['capability gate forced open (wire)', true]]) {
    it(`BROWSER (sm-browser), ${label}: temporary QUEUE is refused; nothing bound (bindSuccessCount 0), nothing delivered`, async () => {
      const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
      if (spoof) spoofCapabilities(client);
      const r = await tempEndpoint(client, 'queue', { addSubs: [DEV, PROD] });
      await bait(`tq-${label}`);
      await sleep(1200);
      const d = await clientDetail('orgA', session.clientName);
      closeMc(r);
      record({
        leg: `15j-tempq-${spoof ? 'wire' : 'plain'}`, attempted: `br-...d001 (sm-browser${spoof ? ', capability gate forced open' : ''}) creates a NON-DURABLE queue, then a prod-topic message is published guaranteed`,
        expected: 'endpoint not created (connect fails at the broker); bindSuccessCount 0; nothing delivered',
        observed: { up: r.up, connectFailed: r.connectFailed, subscriptions: r.subs, delivered: r.got, brokerCounters: counters(d) },
        pass: !r.up && r.got.length === 0 && d.bindSuccessCount === 0,
      });
      expect(r.up, JSON.stringify(r.connectFailed)).toBe(false);
      expect(r.connectFailed, 'the broker reported a connect failure').toBeTruthy();
      expect(r.got).toEqual([]);
      expect(d.bindSuccessCount).toBe(0);
      if (spoof) {
        // only the forced-open run puts the attempt on the wire; the plain run is refused by the library first
        expect(d.bindRequestCount, 'the attempt must have reached the broker, or this proves nothing').toBeGreaterThan(0);
        expect(d.guaranteedDeniedBindFailureCount + d.otherBindFailureCount, 'the broker counted the refused bind').toBeGreaterThan(0);
      }
      await client.disconnect();
    }, 60000);

    it(`BROWSER (sm-browser), ${label}: non-durable TOPIC ENDPOINT is refused on the GRANTED topic (profile-attributable) and on the PROD topic; nothing delivered`, async () => {
      const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
      if (spoof) spoofCapabilities(client);
      const onDev = await tempEndpoint(client, 'topicEndpoint', { endpointTopic: DEV });
      closeMc(onDev);
      const onProd = await tempEndpoint(client, 'topicEndpoint', { endpointTopic: PROD });
      closeMc(onProd);
      await bait(`te-${label}`);
      await pub.publishGuaranteed(devHealth, { te: label });
      await sleep(1200);
      const d = await clientDetail('orgA', session.clientName);
      record({
        leg: `15k-tempte-${spoof ? 'wire' : 'plain'}`, attempted: `br-...d001 (sm-browser${spoof ? ', capability gate forced open' : ''}) creates a NON-DURABLE topic endpoint on its OWN granted topic ${DEV} and on the sibling ${PROD}`,
        expected: 'both refused; the granted-topic refusal cannot be the subscribe ACL (it allows that topic), so it is the client profile; bindSuccessCount 0; nothing delivered',
        observed: { onGrantedTopic: { up: onDev.up, connectFailed: onDev.connectFailed, delivered: onDev.got }, onProdTopic: { up: onProd.up, connectFailed: onProd.connectFailed, delivered: onProd.got }, brokerCounters: counters(d) },
        pass: !onDev.up && !onProd.up && onDev.got.length === 0 && onProd.got.length === 0 && d.bindSuccessCount === 0,
      });
      expect(onDev.up).toBe(false); expect(onProd.up).toBe(false);
      expect(onDev.connectFailed).toBeTruthy(); expect(onProd.connectFailed).toBeTruthy();
      expect([...onDev.got, ...onProd.got]).toEqual([]);
      expect(d.bindSuccessCount).toBe(0);
      await client.disconnect();
    }, 60000);
  }

  it('POSITIVE CONTROL (sm-internal, IDENTICAL ACL): a temporary QUEUE is created (bindSuccessCount 1); a topic subscription inside the granted scope is accepted and DELIVERS; the prod topic is refused by the SUBSCRIBE ACL and NOT delivered', async () => {
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', NON_BROWSER.CTL, { raw: true }));
    expect(session.clientProfileName).toBe('sm-internal');
    expect(session.aclProfileName).toBe('acl-orgA-su1-dev');
    const r = await tempEndpoint(client, 'queue', { addSubs: [DEV, PROD] });
    await pub.publishGuaranteed(devHealth, { tq: 'dev' });
    await pub.publishGuaranteed(prodHealth, { tq: 'prod' });
    await waitFor(() => r.got.length >= 1, { timeoutMs: 5000 });
    await sleep(1000);
    const d = await clientDetail('orgA', session.clientName);
    closeMc(r);
    record({
      leg: '15j-tempq-control', attempted: 'orgA-su1-dev-ctl (sm-internal, same ACL) creates a non-durable queue, adds the granted-scope and the prod topic, then dev and prod events are published guaranteed',
      expected: 'queue UP (bindSuccessCount 1); granted topic SUBSCRIPTION_OK and delivered; prod topic SUBSCRIPTION_ERROR (ACL) and not delivered',
      observed: { up: r.up, subscriptions: r.subs, delivered: r.got, brokerCounters: counters(d) },
      pass: r.up && r.subs[DEV] === 'OK' && /ACL Denied/i.test(r.subs[PROD] ?? '') && r.got.length === 1 && d.bindSuccessCount === 1,
    });
    expect(r.up).toBe(true);
    expect(d.bindSuccessCount).toBe(1);
    expect(r.subs[DEV]).toBe('OK');
    expect(r.subs[PROD]).toMatch(/ACL Denied/i);
    expect(r.got).toEqual([devHealth]);
    await client.disconnect();
  }, 60000);

  it('POSITIVE CONTROL (sm-internal, IDENTICAL ACL): a non-durable TOPIC ENDPOINT on the granted topic is created (UP) and DELIVERS; on the prod topic it is refused "Subscription ACL Denied" — so the browser refusal on the GRANTED topic above is the client profile', async () => {
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', NON_BROWSER.CTL, { raw: true }));
    const onDev = await tempEndpoint(client, 'topicEndpoint', { endpointTopic: DEV });
    const onProd = await tempEndpoint(client, 'topicEndpoint', { endpointTopic: PROD });
    await pub.publishGuaranteed(devHealth, { te: 'ctl-dev' });
    await pub.publishGuaranteed(prodHealth, { te: 'ctl-prod' });
    await waitFor(() => onDev.got.length >= 1, { timeoutMs: 5000 });
    await sleep(1000);
    const d = await clientDetail('orgA', session.clientName);
    closeMc(onDev); closeMc(onProd);
    record({
      leg: '15k-tempte-control', attempted: 'orgA-su1-dev-ctl (sm-internal, same ACL) creates a non-durable topic endpoint on the granted topic and on the prod topic',
      expected: 'granted topic: UP and delivers; prod topic: connect failed (ACL); nothing from prod delivered',
      observed: { onGrantedTopic: { up: onDev.up, delivered: onDev.got }, onProdTopic: { up: onProd.up, connectFailed: onProd.connectFailed, delivered: onProd.got }, brokerCounters: counters(d) },
      pass: onDev.up && onDev.got.length === 1 && !onProd.up && /ACL Denied/i.test(onProd.connectFailed ?? '') && onProd.got.length === 0,
    });
    expect(onDev.up).toBe(true);
    expect(onDev.got).toEqual([devHealth]);
    expect(onProd.up).toBe(false);
    expect(onProd.connectFailed).toMatch(/ACL Denied/i);
    expect(onProd.got).toEqual([]);
    await client.disconnect();
  }, 60000);
});


// =====================================================================================
// ROUND 4 MINOR 5 — DURABLE, admin-created TOPIC ENDPOINTS. Round 3 measured durable QUEUES
// (15a-15h) and TEMPORARY topic endpoints (15k) but not a durable topic endpoint; the flag
// allow-guaranteed-message-receive was cited ("bind to topic endpoints or queues") for it, not measured.
//
// A durable topic endpoint is created by an administrator (SEMP) WITHOUT a subscription: its
// subscription (`destinationTopic` in the monitor) is set by a bind that states one. So the fixture is:
//   sm-te-prod  admin-created, first bound by `sm-internal-orgA` (acl sm/v1/>) stating the PROD topic
//   sm-te-dev   admin-created, first bound by `sm-internal-orgA` stating a topic INSIDE the browser's scope
// then each is unbound and a guaranteed "bait" message is spooled into each. Two endpoints, because the
// bind carries a subscription and Solace's ACL is consulted for it (measured below): the prod endpoint
// shows the ACL layer, the in-scope endpoint isolates the CLIENT PROFILE layer (the ACL allows that
// topic, so a refusal there can only be the client profile).
// =====================================================================================
describe('Durable TOPIC ENDPOINTS (admin-created) — a browser identity cannot bind them; the refusals are attributed to the layer that made them', () => {
  const PROD_T = 'sm/v1/orgA/su1/prod/notify/solution/s1/instance/i-prod-secret/health';
  const DEV_T = healthTopic({ org: 'orgA', su: 'su1', env: 'dev' });
  const TE_PROD = 'sm-te-prod';
  const TE_DEV = 'sm-te-dev';
  const enc = encodeURIComponent;
  const teSpool = async (n) => ((await sempMonitor(`/msgVpns/orgA/topicEndpoints/${enc(n)}/msgs?count=100`)).data ?? []).length;
  const teMon = async (n) => (await sempMonitor(`/msgVpns/orgA/topicEndpoints/${enc(n)}`)).data ?? {};
  const counters = (d) => ({ bindRequestCount: d.bindRequestCount, bindSuccessCount: d.bindSuccessCount, guaranteedDeniedBindFailureCount: d.guaranteedDeniedBindFailureCount, otherBindFailureCount: d.otherBindFailureCount });
  const closeMc = (x) => { try { x.mc?.disconnect(); } catch { /* already down */ } };
  let ready = false;

  beforeAll(async () => {
    for (const n of [TE_PROD, TE_DEV]) {
      await sempWrite('DELETE', `/msgVpns/orgA/topicEndpoints/${enc(n)}`);
      const r = await sempWrite('POST', '/msgVpns/orgA/topicEndpoints', { msgVpnName: 'orgA', topicEndpointName: n, owner: 'sm-internal-orgA', permission: 'consume', ingressEnabled: true, egressEnabled: true, accessType: 'non-exclusive' });
      expect(r.ok, JSON.stringify(r.error)).toBe(true);
    }
    const adm = await internal('orgA');
    for (const [n, t] of [[TE_PROD, PROD_T], [TE_DEV, DEV_T]]) {
      const first = await bindDurableTopicEndpoint(adm, n, t);      // sets the endpoint's subscription, then unbinds
      expect(first.up, JSON.stringify(first.connectFailed)).toBe(true);
      closeMc(first);
    }
    await adm.disconnect();
    await sleep(500);
    expect((await teMon(TE_PROD)).destinationTopic).toBe(PROD_T);
    expect((await teMon(TE_DEV)).destinationTopic).toBe(DEV_T);
    await pub.publishGuaranteed(PROD_T, { bait: 'te-prod' });
    await pub.publishGuaranteed(DEV_T, { bait: 'te-dev' });
    expect(await waitFor(async () => (await teSpool(TE_PROD)) === 1 && (await teSpool(TE_DEV)) === 1)).toBe(true);
    ready = true;
  }, 90000);
  afterAll(async () => { for (const n of [TE_PROD, TE_DEV]) await sempWrite('DELETE', `/msgVpns/orgA/topicEndpoints/${enc(n)}`); });

  it('15l-bait: the admin-created endpoints hold the subscriptions the bind set and each spools its bait message', async () => {
    expect(ready).toBe(true);
    record({ leg: '15l-bait', attempted: 'SEMP-create sm-te-prod and sm-te-dev (owner sm-internal-orgA, other-permission consume); sm-internal-orgA binds each once stating the prod / in-scope topic; guaranteed bait published to both topics', expected: 'destinationTopic set on each; each spools 1', observed: { prod: { destinationTopic: (await teMon(TE_PROD)).destinationTopic, spooled: await teSpool(TE_PROD) }, dev: { destinationTopic: (await teMon(TE_DEV)).destinationTopic, spooled: await teSpool(TE_DEV) } }, pass: true });
  }, 30000);

  for (const [label, spoof] of [['plain', false], ['capability gate forced open (wire)', true]]) {
    it(`BROWSER (sm-browser), ${label}: bind to the PROD topic endpoint and to the IN-SCOPE topic endpoint is refused; spools untouched; nothing delivered; bindSuccessCount 0`, async () => {
      const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
      if (spoof) spoofCapabilities(client);
      const onProd = await bindDurableTopicEndpoint(client, TE_PROD, PROD_T);
      const onDev = await bindDurableTopicEndpoint(client, TE_DEV, DEV_T);
      // the strongest variant against the prod endpoint: state a subscription INSIDE the granted scope, and a bind that states none but may create
      const onProdInScope = await bindDurableTopicEndpoint(client, TE_PROD, DEV_T);
      const onProdNoSub = await bindDurableTopicEndpoint(client, TE_PROD, undefined, { createIfMissing: true });
      const d = await clientDetail('orgA', session.clientName);
      const res = { prodTE_statingProd: onProd, devTE_statingDev: onDev, prodTE_statingInScope: onProdInScope, prodTE_noSubscriptionCreateIfMissing: onProdNoSub };
      const slim = Object.fromEntries(Object.entries(res).map(([k, x]) => [k, { up: x.up, connectFailed: x.connectFailed, delivered: x.got }]));
      for (const x of Object.values(res)) closeMc(x);
      record({
        leg: `15l-te-browser-${spoof ? 'wire' : 'plain'}`, attempted: `br-...d001 (sm-browser${spoof ? ', capability gate forced open' : ''}) binds durable topic endpoints: prod endpoint stating the prod topic; in-scope endpoint stating its topic; prod endpoint stating an IN-SCOPE topic; prod endpoint stating none with createIfMissing`,
        expected: 'all four refused; bindSuccessCount 0; both spools still 1; nothing delivered',
        observed: { ...slim, spoolProd: await teSpool(TE_PROD), spoolDev: await teSpool(TE_DEV), brokerCounters: counters(d) },
        pass: Object.values(res).every((x) => !x.up && x.got.length === 0) && d.bindSuccessCount === 0 && (await teSpool(TE_PROD)) === 1 && (await teSpool(TE_DEV)) === 1,
      });
      for (const [k, x] of Object.entries(res)) { expect(x.up, k).toBe(false); expect(x.got, k).toEqual([]); }
      expect(d.bindSuccessCount).toBe(0);
      expect(await teSpool(TE_PROD)).toBe(1);
      expect(await teSpool(TE_DEV)).toBe(1);
      if (spoof) {
        expect(d.bindRequestCount, 'the binds must have reached the broker, or this proves nothing').toBeGreaterThan(0);
        // ATTRIBUTION: the in-scope endpoint is the one whose subscription the ACL ALLOWS, so its refusal can only be the client profile
        expect(onDev.connectFailed).toMatch(/Service Unavailable/i);
        expect(d.guaranteedDeniedBindFailureCount, 'the broker counted a client-profile denial').toBeGreaterThan(0);
        // the prod endpoint is refused at the ACL layer first
        expect(onProd.connectFailed).toMatch(/Subscription ACL Denied/i);
      }
      await client.disconnect();
    }, 90000);
  }

  it('CONTROL A (sm-internal, IDENTICAL ACL acl-orgA-su1-dev): the IN-SCOPE topic endpoint binds and DELIVERS its bait (so the browser refusal on it is the client profile); the PROD topic endpoint is refused by the SUBSCRIBE ACL — a topic-endpoint bind states a subscription and the ACL is consulted for it (unlike a queue bind, 15d), and the in-scope-subscription and no-subscription bypass attempts on the prod endpoint are refused too', async () => {
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', NON_BROWSER.CTL, { raw: true }));
    expect(session.clientProfileName).toBe('sm-internal');
    expect(session.aclProfileName).toBe('acl-orgA-su1-dev');
    const onProd = await bindDurableTopicEndpoint(client, TE_PROD, PROD_T);
    const onProdInScope = await bindDurableTopicEndpoint(client, TE_PROD, DEV_T);
    const onProdNoSub = await bindDurableTopicEndpoint(client, TE_PROD, undefined, { createIfMissing: true });
    const spoolProdBefore = await teSpool(TE_PROD);
    const onDev = await bindDurableTopicEndpoint(client, TE_DEV, DEV_T);
    await waitFor(() => onDev.got.length >= 1, { timeoutMs: 4000 });
    await sleep(600);
    const d = await clientDetail('orgA', session.clientName);
    const slim = (x) => ({ up: x.up, connectFailed: x.connectFailed, delivered: x.got });
    record({
      leg: '15l-te-control-ctl', attempted: 'orgA-su1-dev-ctl (sm-internal, ACL identical to the browser) binds: the in-scope endpoint; the prod endpoint stating the prod topic; the prod endpoint stating an in-scope topic; the prod endpoint stating none with createIfMissing',
      expected: 'in-scope endpoint: bind succeeds, bait delivered; every prod-endpoint bind refused; prod spool untouched',
      observed: { inScopeEndpoint: slim(onDev), prodEndpoint_statingProd: slim(onProd), prodEndpoint_statingInScope: slim(onProdInScope), prodEndpoint_noSubscriptionCreateIfMissing: slim(onProdNoSub), spoolProdBefore, spoolProdAfter: await teSpool(TE_PROD), spoolDevAfter: await teSpool(TE_DEV), brokerCounters: counters(d) },
      pass: onDev.up && onDev.got.length === 1 && !onProd.up && !onProdInScope.up && !onProdNoSub.up && (await teSpool(TE_PROD)) === 1,
    });
    expect(onDev.up, JSON.stringify(onDev.connectFailed)).toBe(true);
    expect(onDev.got).toEqual([DEV_T]);
    expect(onProd.up).toBe(false);
    expect(onProd.connectFailed).toMatch(/Subscription ACL Denied/i);
    expect(onProdInScope.up).toBe(false);
    expect(onProdNoSub.up).toBe(false);
    expect(await teSpool(TE_PROD)).toBe(1);
    for (const x of [onProd, onProdInScope, onProdNoSub, onDev]) closeMc(x);
    await client.disconnect();
  }, 90000);

  it('CONTROL B (sm-internal-orgA: sm-internal profile AND an ACL that allows sm/v1/>): the PROD topic endpoint binds and DELIVERS the prod bait — the endpoint itself is bindable and would leak, so the refusals above are real', async () => {
    const c = await internal('orgA');
    const x = await bindDurableTopicEndpoint(c, TE_PROD, PROD_T);
    await waitFor(() => x.got.length >= 1, { timeoutMs: 4000 });
    await sleep(500);
    record({ leg: '15l-te-control-internal', attempted: 'sm-internal-orgA (acl-internal: sm/v1/>, client profile sm-internal) binds the prod topic endpoint stating the prod topic', expected: 'bind succeeds; the prod bait is delivered', observed: { up: x.up, connectFailed: x.connectFailed, delivered: x.got, spoolAfter: await teSpool(TE_PROD) }, pass: x.up && x.got.length === 1 });
    expect(x.up, JSON.stringify(x.connectFailed)).toBe(true);
    expect(x.got).toEqual([PROD_T]);
    closeMc(x);
    await c.disconnect();
  }, 60000);
});
