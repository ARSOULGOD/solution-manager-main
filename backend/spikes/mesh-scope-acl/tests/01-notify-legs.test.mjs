// §11.3 legs 1-10 and 11a/11b — throwaway spike test, NOT product code.
//
// Subscriber under test: the [orgA,su1,dev] grant = client username BR.GRANT ('br-000000000000d001',
// round 2: browser usernames are in the reserved grammar), on VPN 'orgA' whose ACL profile
// (acl-orgA-su1-dev) carries exactly ONE subscribe exception: sm/v1/orgA/su1/dev/notify/>,
// and whose CLIENT profile is 'sm-browser' (no guaranteed send / receive / endpoint create —
// see provision.sh and tests/05). Authenticated via OAuth/JWT (sub=BR.GRANT).
//
// Round 1 review changes (MAJOR 3, MAJOR 4, MINOR 7):
//  * The fixture is now RUN as §11.3 specifies: 8 leaf environments (2 orgs x 2 sub-orgs x 2 envs),
//    one instance per leaf, health + placement fact on every leaf, plus the org-terminating
//    catalog topic. Legs 1-10 assert DELIVERY, not just that subscribe() resolved.
//  * The negative delivery assertions are only meaningful if the traffic was live, so a
//    liveness witness (sm-internal-orgA on sm/v1/>) must have seen ALL of orgA's traffic first.
//  * Leg 11b now has an orgA-side liveness control.
//  * Every leg logs {attempted, expected, observed, pass}; legs 7/8 additionally assert the
//    session's resolved ACL and client profile and the broker-held subscription set, before
//    and after the refusal.

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  internal, oauthClient, connectObserved, trySubscribe, ACL_DENIED, record, sleep, waitFor,
  sessions, sessionNow, subscriptionsOf, brokerTx, settledDelta, connectObserved as _co,
  leaves, healthTopic, placementTopic, catalogTopic, orgTopics, publishOrgFixture, ORGS, BR, NON_BROWSER,
} from './lib/common.mjs';

const SCOPE = { org: 'orgA', su: 'su1', env: 'dev' };
const SCOPE_TOPICS = [healthTopic(SCOPE), placementTopic(SCOPE)].sort();

let pubA, pubB, witnessA, witnessB;
const heardA = [];
const heardB = [];

beforeAll(async () => {
  pubA = await internal('orgA');
  pubB = await internal('orgB');
  witnessA = await internal('orgA');
  witnessB = await internal('orgB');
  await witnessA.subscribe('sm/v1/>', (m) => heardA.push(m.topic));
  await witnessB.subscribe('sm/v1/>', (m) => heardB.push(m.topic));
}, 60000);

afterAll(async () => {
  await Promise.all([pubA, pubB, witnessA, witnessB].map((c) => c?.disconnect()));
});

/** Publish the whole 2-org fixture and wait until BOTH witnesses have seen every one of their
 *  own organization's 9 topics — the liveness precondition for every negative assertion. */
async function publishFixtureAndAwaitLiveness(tag) {
  heardA.length = 0; heardB.length = 0;
  await publishOrgFixture(pubA, 'orgA', tag);
  await publishOrgFixture(pubB, 'orgB', tag);
  const live = await waitFor(() => heardA.length >= 9 && heardB.length >= 9, { timeoutMs: 6000 });
  expect(live, `liveness: witnesses saw A=${heardA.length}/9 B=${heardB.length}/9`).toBe(true);
  expect([...heardA].sort()).toEqual([...orgTopics('orgA')].sort());
  expect([...heardB].sort()).toEqual([...orgTopics('orgB')].sort());
  // Isolation of the witnesses themselves: neither saw the other organization's topics.
  expect(heardA.some((t) => t.startsWith('sm/v1/orgB/'))).toBe(false);
  expect(heardB.some((t) => t.startsWith('sm/v1/orgA/'))).toBe(false);
}

async function grantee() {
  return connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
}

describe('§11.3 fixture — 8 leaf environments are live', () => {
  it('fixture: both organizations publish one instance on every one of the 4 leaves each, plus the catalog topic', async () => {
    expect(leaves('orgA').length + leaves('orgB').length).toBe(8);
    await publishFixtureAndAwaitLiveness('fixture');
    record({
      leg: 'fixture', attempted: 'publish health+placement on 8 leaves + 2 catalog topics; two internal witnesses on sm/v1/>',
      expected: 'each witness receives exactly its own organization\'s 9 topics, none of the other\'s',
      observed: { orgA: heardA.length, orgB: heardB.length }, pass: true,
    });
  }, 60000);
});

describe('§11.3 legs 1-3 — exactly-granted and narrower subscriptions ALLOW, and deliver exactly the granted leaf', () => {
  async function runAllowLeg(leg, filter, expected) {
    const { client, session } = await grantee();
    const got = [];
    const r = await trySubscribe(client, filter, (m) => got.push(m.topic));
    expect(r, JSON.stringify(r)).toMatchObject({ outcome: 'allowed' });
    const txBase = await brokerTx('orgA', session.clientName);
    await publishFixtureAndAwaitLiveness(`leg${leg}`);
    await sleep(600);
    // ROUND 3: delivery measured AT THE BROKER (per-client dataTxMsgCount), not only at the SDK
    // handler, which filters by its own local pattern and so cannot show a leaked message.
    const brokerDelivered = await settledDelta('orgA', session.clientName, txBase, expected.length);
    const orgBSeen = got.filter((t) => t.startsWith('sm/v1/orgB/'));
    record({
      leg, attempted: `subscribe ${filter}`, expected: `allowed; delivers exactly ${expected.length} topic(s) of the granted leaf, none of the other 7 leaves; broker transmitted exactly ${expected.length} message(s) to this session while both organizations' 18-topic fixture was published`,
      observed: { subscribe: r, delivered: [...got].sort(), brokerDataTxMsgCountDelta: brokerDelivered, fixtureTopicsPublished: 18, orgBDelivered: orgBSeen.length, aclProfile: session.aclProfileName, clientProfile: session.clientProfileName },
      pass: JSON.stringify([...got].sort()) === JSON.stringify(expected.sort()) && brokerDelivered === expected.length,
    });
    expect(brokerDelivered, `leg ${leg}: broker-side dataTxMsgCount delta`).toBe(expected.length);
    expect([...got].sort()).toEqual([...expected].sort());
    expect(orgBSeen).toEqual([]);
    await client.disconnect();
  }

  it('leg 1: sm/v1/orgA/su1/dev/notify/> — exact grant (health + placement of su1/dev ONLY)', async () => {
    await runAllowLeg(1, 'sm/v1/orgA/su1/dev/notify/>', SCOPE_TOPICS);
  }, 60000);

  it('leg 2: narrower filter (health) inside the granted scope', async () => {
    await runAllowLeg(2, 'sm/v1/orgA/su1/dev/notify/solution/*/instance/*/health', [healthTopic(SCOPE)]);
  }, 60000);

  it('leg 3: placement facts beneath the granted scope (downward inheritance)', async () => {
    await runAllowLeg(3, 'sm/v1/orgA/su1/dev/notify/solution/*/instance/*/placement/*/health', [placementTopic(SCOPE)]);
  }, 60000);
});

describe('§11.3 legs 4-10 — every sibling and every wider scope REFUSE, and nothing leaks', () => {
  const REFUSED = [
    [4, 'sm/v1/orgA/su1/prod/notify/>', 'sibling environment (prod)'],
    [5, 'sm/v1/orgA/su2/dev/notify/>', 'sibling sub-organization (su2)'],
    [6, 'sm/v1/orgA/su2/prod/notify/>', 'sibling on both levels'],
    [7, 'sm/v1/orgA/*/dev/notify/>', 'wildcard at the sub-organization level'],
    [8, 'sm/v1/orgA/su1/*/notify/>', 'wildcard at the environment level'],
    [9, 'sm/v1/orgA/>', 'organization-wide (the posture OR-5/OR-6 replaces)'],
    [10, 'sm/v1/>', 'everything'],
  ];

  it('legs 4-10 on ONE session that also holds the leg-1 grant: each refusal is SUBSCRIBE_ACL_DENIED at subscribe time, the resolved profiles and broker-held subscription set are unchanged, and afterwards ONLY the granted leaf is delivered', async () => {
    const { client, session } = await grantee();
    const got = [];
    const own = await trySubscribe(client, 'sm/v1/orgA/su1/dev/notify/>', (m) => got.push(m.topic));
    expect(own).toMatchObject({ outcome: 'allowed' });

    const baseline = await subscriptionsOf('orgA', session.clientName);
    const nSessions = (await sessions('orgA')).length;
    const results = [];

    for (const [leg, topic, why] of REFUSED) {
      const before = await sessionNow('orgA', session.clientName);
      const r = await trySubscribe(client, topic, (m) => got.push(m.topic));
      const after = await sessionNow('orgA', session.clientName);
      const subsAfter = await subscriptionsOf('orgA', session.clientName);

      // Resolved profiles asserted on the BROKER's view of the session (review MINOR 7):
      // the refusal must not be attributable to a session that resolved to some other profile.
      expect(after.aclProfileName, `leg ${leg} resolved ACL profile`).toBe('acl-orgA-su1-dev');
      expect(after.clientProfileName, `leg ${leg} resolved client profile`).toBe('sm-browser');
      expect(before.aclProfileName).toBe(after.aclProfileName);
      expect(client.isConnected(), `leg ${leg} session still up`).toBe(true);
      expect((await sessions('orgA')).length, `leg ${leg} session count`).toBe(nSessions);
      expect(subsAfter, `leg ${leg}: refused subscribe must not add a broker-held subscription`).toEqual(baseline);

      const pass = r.outcome === 'acl-denied' && r.code === 'SUBSCRIBE_ACL_DENIED';
      results.push({ leg, topic, why, r, pass });
      record({
        leg, attempted: `subscribe ${topic} (${why})`, expected: 'refuse: SUBSCRIBE_ACL_DENIED at subscribe time, no broker-held subscription added',
        observed: { result: r, resolvedAcl: after.aclProfileName, resolvedClientProfile: after.clientProfileName, sessionUp: true, brokerHeldSubscriptionsUnchanged: true },
        pass,
      });
      expect(r, `leg ${leg}: ${JSON.stringify(r)}`).toMatchObject(ACL_DENIED);
    }

    // Delivery half: the refusals above must not have left ANY partial subscription behind.
    // Round 3: counted at the BROKER as well as at the handler (the handler filters by its own
    // local pattern, so an out-of-scope message the broker sent would be dropped before `got`).
    got.length = 0;
    const txBase = await brokerTx('orgA', session.clientName);
    await publishFixtureAndAwaitLiveness('legs4-10');
    await sleep(600);
    const brokerDelivered = await settledDelta('orgA', session.clientName, txBase, SCOPE_TOPICS.length);
    record({
      leg: '4-10 (delivery)', attempted: 'publish all 8 leaves (both organizations, 18 topics) after the 7 refused subscribes were attempted on the same session',
      expected: `session holds only the leg-1 grant: receives exactly ${SCOPE_TOPICS.length} topics; broker transmitted exactly ${SCOPE_TOPICS.length} message(s)`,
      observed: { delivered: [...got].sort(), brokerDataTxMsgCountDelta: brokerDelivered, fixtureTopicsPublished: 18 }, pass: JSON.stringify([...got].sort()) === JSON.stringify(SCOPE_TOPICS) && brokerDelivered === SCOPE_TOPICS.length,
    });
    expect(brokerDelivered, 'legs 4-10: broker-side dataTxMsgCount delta').toBe(SCOPE_TOPICS.length);
    expect([...got].sort()).toEqual(SCOPE_TOPICS);
    await client.disconnect();
  }, 90000);
});

describe('§11.3 leg 11a/11b — the sibling-organization topic, and the VPN boundary itself', () => {
  it('leg 11a (as attempted by the ACTUAL grant): the ACL already refuses it, so 11b is degenerate for the grantee', async () => {
    const { client, session } = await grantee();
    const got = [];
    const r = await trySubscribe(client, 'sm/v1/orgB/su1/dev/notify/>', (m) => got.push(m.topic));
    const txBase = await brokerTx('orgA', session.clientName);
    await publishFixtureAndAwaitLiveness('leg11a');
    await sleep(400);
    const brokerDelivered = await settledDelta('orgA', session.clientName, txBase, 0);
    record({
      leg: '11a (grantee)', attempted: 'subscribe sm/v1/orgB/su1/dev/notify/> as the [orgA,su1,dev] grant (br-...d001); both organizations\' 18-topic fixture published',
      expected: 'either answer acceptable (§11.3)', observed: { result: r, delivered: got, brokerDataTxMsgCountDelta: brokerDelivered }, pass: null,
    });
    expect(r).toMatchObject(ACL_DENIED);
    expect(got).toEqual([]);
    expect(brokerDelivered, '11a: the broker transmitted nothing to a session holding no subscription').toBe(0);
    await client.disconnect();
  }, 60000);

  it('leg 11a/11b (wide probe, WITH liveness control on the orgA side): subscribe succeeds; a publish inside VPN orgA on that literal topic IS heard; the same literal published inside VPN orgB is NOT', async () => {
    const { client: probe, session: probeSession } = await _co('orgA', () => internal('orgA'));
    const heard = [];
    const LITERAL = 'sm/v1/orgB/su1/dev/notify/solution/s1/instance/i-ctl/health';
    const r = await trySubscribe(probe, 'sm/v1/orgB/su1/dev/notify/>', (m) => heard.push({ topic: m.topic, from: m.payload?.from }));
    expect(r, '11a: wide probe subscribe').toMatchObject({ outcome: 'allowed' });

    // --- orgA-side LIVENESS CONTROL (review MAJOR 4): same literal topic string, published
    // INSIDE VPN orgA. If the probe's subscription were not live, this would not arrive and
    // "nothing from orgB" below could not be attributed to the VPN boundary.
    await pubA.publish(LITERAL, { from: 'orgA-control' });
    const live = await waitFor(() => heard.length >= 1, { timeoutMs: 4000 });
    expect(live, 'orgA-side liveness control: probe must hear a same-VPN publish on the literal topic').toBe(true);
    expect(heard.map((h) => h.topic)).toEqual([LITERAL]);
    const afterControl = heard.length;
    const txAfterControl = await brokerTx('orgA', probeSession.clientName);

    // --- the leg-11b measurement: the SAME literal, plus a full org fixture, published inside orgB.
    const heardBWitness = [];
    const wB = await internal('orgB');
    await wB.subscribe('sm/v1/orgB/su1/dev/notify/>', (m) => heardBWitness.push(m.topic));
    await pubB.publish(LITERAL, { from: 'orgB-real' });
    await publishOrgFixture(pubB, 'orgB', 'leg11b');
    const bLive = await waitFor(() => heardBWitness.includes(LITERAL), { timeoutMs: 4000 });
    expect(bLive, 'orgB-side positive control: orgB witness must hear the orgB publish').toBe(true);
    await sleep(800);
    const probeTxAfterOrgB = await brokerTx('orgA', probeSession.clientName);

    record({
      leg: '11b', attempted: 'orgB publishes the literal topic + its 9-topic fixture; orgA wide probe (sm/v1/>-class ACL) has subscribed to sm/v1/orgB/su1/dev/notify/>',
      expected: 'nothing from orgB delivered into VPN orgA',
      observed: {
        orgALivenessControlHeard: afterControl, probeHeardAfterOrgBPublish: heard.length,
        orgBWitnessHeard: heardBWitness.length,
        brokerDataTxMsgCountToOrgAProbe: { afterOrgAControl: txAfterControl, afterOrgBPublishes: probeTxAfterOrgB },
      },
      pass: heard.length === afterControl && probeTxAfterOrgB === txAfterControl,
    });
    expect(probeTxAfterOrgB, 'leg 11b: the BROKER transmitted nothing more to the orgA probe after orgB published').toBe(txAfterControl);
    expect(heard.length, 'leg 11b: nothing from orgB may arrive inside orgA').toBe(afterControl);
    expect(heard.every((h) => h.from === 'orgA-control')).toBe(true);

    await Promise.all([probe.disconnect(), wB.disconnect()]);
  }, 60000);
});
