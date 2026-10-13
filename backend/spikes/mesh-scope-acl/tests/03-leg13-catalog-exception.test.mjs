// §11.3 leg 13 — throwaway spike test, NOT product code.
//
// §11.4: leg 13 has NO expected value — it is a design gap, not a measurement gap. This file
// does NOT pass/fail leg 13's design question. It MEASURES candidate (a) from §11.4's table —
// "a second, organization-level allow exception... alongside its scoped one" — in the
// EXACT-topic form: sm/v1/orgA/notify/catalog/package, no wildcard.
//
// Subject: 'orgA-su1-dev-catalog' (ACL acl-orgA-su1-dev-catalog = the leg-1 scoped exception
// PLUS the one exact exception; client profile sm-browser).
//
// What is measured: does the exception admit EXACTLY that one topic and deliver exactly that
// fact (round 1: delivery now asserted, with the 8-leaf fixture live), and refuse every wider
// or merely-adjacent topic. A "yes" does not settle §11.4.

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  internal, oauthClient, connectObserved, brokerTx, settledDelta, trySubscribe, ACL_DENIED, record, sleep, waitFor,
  publishOrgFixture, catalogTopic, healthTopic, placementTopic, BR, NON_BROWSER,
} from './lib/common.mjs';

const subject = () => oauthClient('orgA', BR.CATALOG);
let pubA;
beforeAll(async () => { pubA = await internal('orgA'); });
afterAll(async () => { await pubA?.disconnect(); });

describe('§11.3 leg 13 — MEASUREMENT of candidate (a), no pass/fail verdict on the design question', () => {
  it('the exact topic is admitted AND delivered; the scoped grant still delivers its leaf; nothing else arrives', async () => {
    const { client: c, session } = await connectObserved('orgA', subject);
    const got = [];
    const exact = await trySubscribe(c, catalogTopic('orgA'), (m) => got.push(m.topic));
    const scoped = await trySubscribe(c, 'sm/v1/orgA/su1/dev/notify/>', (m) => got.push(m.topic));
    expect(exact).toMatchObject({ outcome: 'allowed' });
    expect(scoped).toMatchObject({ outcome: 'allowed' });

    const txBase = await brokerTx('orgA', session.clientName);
    await publishOrgFixture(pubA, 'orgA', 'leg13');
    const want = [catalogTopic('orgA'), healthTopic({ org: 'orgA', su: 'su1', env: 'dev' }), placementTopic({ org: 'orgA', su: 'su1', env: 'dev' })].sort();
    await waitFor(() => got.length >= 3, { timeoutMs: 5000 });
    await sleep(600);
    const brokerDelivered = await settledDelta('orgA', session.clientName, txBase, 3);
    record({ leg: 13, attempted: 'subscribe exact org-terminating topic + scoped grant; publish the 9-topic orgA fixture', expected: 'no expected value (§11.4 undecided) — measured only', observed: { exact, scoped, delivered: [...got].sort(), brokerDataTxMsgCountDelta: brokerDelivered, fixtureTopicsPublished: 9 }, pass: null });
    expect([...got].sort()).toEqual(want);
    expect(brokerDelivered, 'leg 13: broker-side dataTxMsgCount delta').toBe(3);
    await c.disconnect();
  }, 60000);

  for (const [what, topic] of [
    ['a DIFFERENT organization-terminating fact under the same parent', 'sm/v1/orgA/notify/catalog/other'],
    ['a wildcard one level up (sm/v1/orgA/notify/catalog/*)', 'sm/v1/orgA/notify/catalog/*'],
    ['the organization-wide notify wildcard (sm/v1/orgA/notify/>)', 'sm/v1/orgA/notify/>'],
    ['sm/v1/orgA/> (leg 9 topic)', 'sm/v1/orgA/>'],
  ]) {
    it(`${what} is REFUSED`, async () => {
      const c = await subject();
      const r = await trySubscribe(c, topic);
      record({ leg: 13, attempted: `subscribe ${topic}`, expected: 'refused (exception did not widen)', observed: r, pass: r.outcome === 'acl-denied' });
      expect(r, JSON.stringify(r)).toMatchObject(ACL_DENIED);
      await c.disconnect();
    }, 30000);
  }
});
