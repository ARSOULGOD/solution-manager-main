// Round 2 MINOR — leg 14 in the REAL shape: a browser session that holds BOTH a notify grant AND
// a stream branch. Throwaway spike test, NOT product code.
//
// Round 1 ran leg 14 only on default-fallthrough subjects (no notify grant). A subject WITH a
// grant resolves to its dedicated username, whose ACL profile then has no stream exception
// unless one is added to it. Here two granted subjects (COMP1 = [orgA,su1,dev], COMP2 =
// [orgA,su1,prod]) each have ONE dedicated ACL profile carrying the notify literal exception AND
// `sm/v1/orgA/stream/$client-username/>`. §11.3 says leg 14 needs "two sessions ... each with a
// grant" — this is that.
//
// Measured: the session resolves to the dedicated username (not `default`); the substitution
// yields the dedicated username (== the ticket sub); each subject subscribes its own notify scope
// and its own stream branch and receives exactly those; refused: the other subject's notify scope,
// the other's stream branch, sm/v1/orgA/stream/> and wildcards over both.

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  BR, internal, oauthClient, connectObserved, trySubscribe, ACL_DENIED, record, sleep, waitFor,
  healthTopic, placementTopic, subscriptionsOf, brokerTx, settledDelta,
} from './lib/common.mjs';

const streamTopic = (u, tail = 's1') => `sm/v1/orgA/stream/${u}/${tail}`;
const SCOPE1 = { org: 'orgA', su: 'su1', env: 'dev' };
const SCOPE2 = { org: 'orgA', su: 'su1', env: 'prod' };
let pub;
beforeAll(async () => { pub = await internal('orgA'); }, 60000);
afterAll(async () => { await pub?.disconnect(); });

describe('round 2 MINOR — leg 14 composed: notify grant + stream branch on ONE session', () => {
  it('two granted subjects: resolution, allow/refuse matrix, and delivery', async () => {
    const s1 = await connectObserved('orgA', () => oauthClient('orgA', BR.COMP1));
    const s2 = await connectObserved('orgA', () => oauthClient('orgA', BR.COMP2));
    for (const [s, u, acl] of [[s1.session, BR.COMP1, `acl-${BR.COMP1}`], [s2.session, BR.COMP2, `acl-${BR.COMP2}`]]) {
      expect(s.clientUsername, 'a granted subject resolves to its DEDICATED username, not default').toBe(u);
      expect(s.aclProfileName).toBe(acl);
      expect(s.clientProfileName).toBe('sm-browser');
    }
    const got1 = []; const got2 = [];
    const m1 = {}; const m2 = {};
    m1.ownNotify = await trySubscribe(s1.client, 'sm/v1/orgA/su1/dev/notify/>', (m) => got1.push(m.topic));
    m1.ownStream = await trySubscribe(s1.client, streamTopic(BR.COMP1, '>'), (m) => got1.push(m.topic));
    m2.ownNotify = await trySubscribe(s2.client, 'sm/v1/orgA/su1/prod/notify/>', (m) => got2.push(m.topic));
    m2.ownStream = await trySubscribe(s2.client, streamTopic(BR.COMP2, '>'), (m) => got2.push(m.topic));
    const base1 = await subscriptionsOf('orgA', s1.session.clientName);
    const refusals = [
      ['1 -> 2 notify scope', s1.client, 'sm/v1/orgA/su1/prod/notify/>'],
      ['2 -> 1 notify scope', s2.client, 'sm/v1/orgA/su1/dev/notify/>'],
      ['1 -> 2 stream branch', s1.client, streamTopic(BR.COMP2)],
      ['2 -> 1 stream branch', s2.client, streamTopic(BR.COMP1)],
      ['1 -> 2 stream branch (>)', s1.client, streamTopic(BR.COMP2, '>')],
      ['1 -> all streams', s1.client, 'sm/v1/orgA/stream/>'],
      ['2 -> all streams (*)', s2.client, 'sm/v1/orgA/stream/*/s1'],
      ['1 -> default fallthrough subject\'s branch', s1.client, streamTopic(BR.STREAM_A)],
      ['1 -> org-wide', s1.client, 'sm/v1/orgA/>'],
    ];
    const refused = [];
    for (const [label, c, topic] of refusals) {
      const r = await trySubscribe(c, topic);
      refused.push({ label, topic, r });
      expect(r, `${label}: ${JSON.stringify(r)}`).toMatchObject(ACL_DENIED);
    }
    expect(await subscriptionsOf('orgA', s1.session.clientName), 'refusals added no broker-held subscription').toEqual(base1);
    expect(m1.ownNotify).toMatchObject({ outcome: 'allowed' });
    expect(m1.ownStream).toMatchObject({ outcome: 'allowed' });
    expect(m2.ownNotify).toMatchObject({ outcome: 'allowed' });
    expect(m2.ownStream).toMatchObject({ outcome: 'allowed' });

    // delivery: the 4 orgA leaves (health + placement = 8 topics; NO orgB leaf, NO catalog topic) and 3 stream topics
    const tx1 = await brokerTx('orgA', s1.session.clientName); const tx2 = await brokerTx('orgA', s2.session.clientName);
    for (const l of ['dev', 'prod']) for (const su of ['su1', 'su2']) {
      await pub.publish(healthTopic({ org: 'orgA', su, env: l }), { leaf: `${su}/${l}` });
      await pub.publish(placementTopic({ org: 'orgA', su, env: l }), { leaf: `${su}/${l}` });
    }
    await pub.publish(streamTopic(BR.COMP1), { for: 1 });
    await pub.publish(streamTopic(BR.COMP2), { for: 2 });
    await pub.publish(streamTopic(BR.STREAM_A), { for: 'other' });
    const want1 = [healthTopic(SCOPE1), placementTopic(SCOPE1), streamTopic(BR.COMP1)].sort();
    const want2 = [healthTopic(SCOPE2), placementTopic(SCOPE2), streamTopic(BR.COMP2)].sort();
    await waitFor(() => got1.length >= 3 && got2.length >= 3, { timeoutMs: 6000 });
    await sleep(700);
    const b1 = await settledDelta('orgA', s1.session.clientName, tx1, 3); const b2 = await settledDelta('orgA', s2.session.clientName, tx2, 3);
    record({
      leg: '14-composed', attempted: 'two subjects with notify grant + stream substitution on ONE dedicated ACL profile each: own scope/branch, every cross scope/branch, then publish the 4 orgA leaves (8 topics: health + placement each) + 3 stream topics',

      expected: 'each resolves to its dedicated username; own notify + own stream allowed and delivered; every cross-scope / cross-branch / wildcard refused',
      observed: { s1: s1.session, s2: s2.session, allowed: { m1, m2 }, refused, delivered1: [...got1].sort(), delivered2: [...got2].sort(), brokerDataTxMsgCountDelta: { s1: b1, s2: b2 }, topicsPublished: 11 },
      pass: JSON.stringify([...got1].sort()) === JSON.stringify(want1) && JSON.stringify([...got2].sort()) === JSON.stringify(want2) && b1 === 3 && b2 === 3,
    });
    expect([b1, b2], '14-composed: broker-side dataTxMsgCount deltas').toEqual([3, 3]);
    expect([...got1].sort()).toEqual(want1);
    expect([...got2].sort()).toEqual(want2);
    await Promise.all([s1.client.disconnect(), s2.client.disconnect()]);
  }, 90000);

  it('a default-fallthrough subject (no grant) and the granted subjects coexist in one VPN: each remains confined to its own branch, and the fallthrough subject has NO notify access', async () => {
    const gObs = await connectObserved('orgA', () => oauthClient('orgA', BR.COMP1));
    const g = gObs.client;
    const f = await connectObserved('orgA', () => oauthClient('orgA', BR.STREAM_A));
    expect(f.session.clientUsername).toBe('default');
    const gotG = []; const gotF = [];
    const a = await trySubscribe(g, streamTopic(BR.COMP1), (m) => gotG.push(m.topic));
    const b = await trySubscribe(f.client, streamTopic(BR.STREAM_A), (m) => gotF.push(m.topic));
    const c = await trySubscribe(f.client, 'sm/v1/orgA/su1/dev/notify/>');
    const d = await trySubscribe(f.client, streamTopic(BR.COMP1));
    const e = await trySubscribe(g, streamTopic(BR.STREAM_A));
    const txG = await brokerTx('orgA', gObs.session.clientName); const txF = await brokerTx('orgA', f.session.clientName);
    await pub.publish(streamTopic(BR.COMP1), { x: 1 });
    await pub.publish(streamTopic(BR.STREAM_A), { x: 2 });
    await waitFor(() => gotG.length >= 1 && gotF.length >= 1);
    await sleep(400);
    const bG = await settledDelta('orgA', gObs.session.clientName, txG, 1); const bF = await settledDelta('orgA', f.session.clientName, txF, 1);
    record({ leg: '14-composed-coexist', attempted: 'granted COMP1 + fallthrough STREAM_A in one VPN: own branches, cross branches, fallthrough subject subscribes a notify scope; each stream branch published once', expected: 'own allowed and delivered (broker transmitted exactly 1 to each); all cross refused', observed: { a, b, c, d, e, gotG, gotF, brokerDataTxMsgCountDelta: { granted: bG, fallthrough: bF } }, pass: gotG.length === 1 && gotF.length === 1 && bG === 1 && bF === 1 });
    expect([bG, bF], 'coexist: broker-side dataTxMsgCount deltas').toEqual([1, 1]);
    expect(a).toMatchObject({ outcome: 'allowed' }); expect(b).toMatchObject({ outcome: 'allowed' });
    for (const r of [c, d, e]) expect(r).toMatchObject(ACL_DENIED);
    expect(gotG).toEqual([streamTopic(BR.COMP1)]); expect(gotF).toEqual([streamTopic(BR.STREAM_A)]);
    await Promise.all([g.disconnect(), f.client.disconnect()]);
  }, 60000);
});
