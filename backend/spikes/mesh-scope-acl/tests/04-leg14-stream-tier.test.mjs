// §11.3 leg 14 — throwaway spike test, NOT product code. EXPLORATORY.
//
// Stream tier: bind the VPN's `default` client username to ONE profile carrying ONE exception,
// sm/v1/orgA/stream/$client-username/>, and see whether every OAuth subject with NO dedicated
// client-username object still gets a branch named after its own `sub`, at ZERO additional
// broker writes per subject (G1's mechanism, spike-authz-result.md "Chosen mechanism" #1).
//
// Round 1 review MAJOR 2: that zero-write route holds ONLY if `sub` cannot contain "/". Solace
// documents that substitution variables must "appear alone at any level" and that client
// usernames must not contain "/" (https://docs.solace.com/Security/Granting-Clients-Access.htm);
// the broker does NOT enforce it. This file therefore also:
//   (1) shows the escape is REAL when the ticket issuer does not filter (raw endpoint) —
//       at subscribe level AND at delivery level;
//   (2) shows the spike issuer's SUB RULE rejects the hostile values, and that the rejection is
//       an HTTP 400 with no token, so the attack cannot even reach the broker;
//   (3) asserts the invariant that makes the rule sufficient for the corpus: every sub that
//       escapes on the broker is rejected by the rule, and every benign sub the rule accepts
//       stays confined.
// It does not ratify BE-D5 and does not close G1's gaps.

import { describe, it, expect } from 'vitest';
import {
  BR, internal, oauthClient, connectObserved, trySubscribe, ACL_DENIED, record, sleep, waitFor, tryMint,
  sempConfig, sessions, brokerTx, settledDelta,
} from './lib/common.mjs';

describe('§11.3 leg 14 (EXPLORATORY) — stream-tier substitution, zero-write route, benign subs', () => {
  it('two sessions fall through to `default`, each substituted to ITS OWN sub — no dedicated object exists for either', async () => {
    const a = await connectObserved('orgA', () => oauthClient('orgA', BR.STREAM_A));
    const b = await connectObserved('orgA', () => oauthClient('orgA', BR.STREAM_B));
    for (const [s, sub] of [[a.session, BR.STREAM_A], [b.session, BR.STREAM_B]]) {
      expect(s.clientUsername).toBe('default');
      expect(s.aclProfileName).toBe('acl-orgA-stream-subst');
      expect(s.clientProfileName).toBe('sm-browser');
      expect(s.originalClientUsername).toBe(sub);
    }
    record({ leg: 14, attempted: 'connect sub=BR.STREAM_A and sub=BR.STREAM_B (no dedicated client-username objects)', expected: 'both fall through to `default`; originalClientUsername = own sub', observed: [a.session, b.session], pass: true });
    await Promise.all([a.client.disconnect(), b.client.disconnect()]);
  }, 30000);

  it('each session may subscribe ONLY its own branch, and receives ONLY its own branch\'s traffic', async () => {
    const { client: a, session: sa } = await connectObserved('orgA', () => oauthClient('orgA', BR.STREAM_A));
    const { client: b, session: sb } = await connectObserved('orgA', () => oauthClient('orgA', BR.STREAM_B));
    const gotA = []; const gotB = [];
    const ownA = await trySubscribe(a, `sm/v1/orgA/stream/${BR.STREAM_A}/s1`, (m) => gotA.push(m.topic));
    const othersA = await trySubscribe(a, `sm/v1/orgA/stream/${BR.STREAM_B}/s1`);
    const ownB = await trySubscribe(b, `sm/v1/orgA/stream/${BR.STREAM_B}/s1`, (m) => gotB.push(m.topic));
    const othersB = await trySubscribe(b, `sm/v1/orgA/stream/${BR.STREAM_A}/s1`);
    expect(ownA).toMatchObject({ outcome: 'allowed' });
    expect(ownB).toMatchObject({ outcome: 'allowed' });
    expect(othersA).toMatchObject(ACL_DENIED);
    expect(othersB).toMatchObject(ACL_DENIED);

    const pub = await internal('orgA');
    const txA = await brokerTx('orgA', sa.clientName); const txB = await brokerTx('orgA', sb.clientName);
    await pub.publish(`sm/v1/orgA/stream/${BR.STREAM_A}/s1`, { for: 'A' });
    await pub.publish(`sm/v1/orgA/stream/${BR.STREAM_B}/s1`, { for: 'B' });
    await waitFor(() => gotA.length >= 1 && gotB.length >= 1);
    await sleep(500);
    const brokerA = await settledDelta('orgA', sa.clientName, txA, 1); const brokerB = await settledDelta('orgA', sb.clientName, txB, 1);
    record({ leg: 14, attempted: 'cross-branch subscribe + delivery for BR.STREAM_A / BR.STREAM_B (each branch published once)', expected: 'each refused the other\'s branch; each receives only its own; broker transmitted exactly 1 message to each session', observed: { ownA, othersA, ownB, othersB, gotA, gotB, brokerDataTxMsgCountDelta: { A: brokerA, B: brokerB } }, pass: gotA.length === 1 && gotB.length === 1 && brokerA === 1 && brokerB === 1 });
    expect([brokerA, brokerB], 'leg 14: broker-side dataTxMsgCount deltas').toEqual([1, 1]);
    expect(gotA).toEqual([`sm/v1/orgA/stream/${BR.STREAM_A}/s1`]);
    expect(gotB).toEqual([`sm/v1/orgA/stream/${BR.STREAM_B}/s1`]);
    await Promise.all([a.disconnect(), b.disconnect(), pub.disconnect()]);
  }, 30000);

  it('the object cost of the zero-write route: no client-username object exists for either subject', async () => {
    const ex = await sempConfig('/msgVpns/orgA/aclProfiles/acl-orgA-stream-subst/subscribeTopicExceptions');
    expect(ex.data.length).toBe(1);
    expect(ex.data[0].subscribeTopicException).toBe('sm/v1/orgA/stream/$client-username/>');
    for (const name of [BR.STREAM_A, BR.STREAM_B]) {
      const r = await sempConfig(`/msgVpns/orgA/clientUsernames/${name}`);
      expect(r.status, `no dedicated client-username object for ${name}`).toBe(400);
    }
    record({ leg: 14, attempted: 'GET clientUsernames/BR.STREAM_A, /BR.STREAM_B', expected: 'SEMP 400 NOT_FOUND for both', observed: 'both 400', pass: true });
  }, 30000);
});

describe('§11.3 leg 14 / round-1 MAJOR 2 — a `sub` containing "/" escapes the branch (rule-free issuer)', () => {
  const VICTIM = `sm/v1/orgA/stream/${BR.STREAM_B}/s1`;

  it('WITHOUT the sub rule: sub="<victim>/s1" connects, falls through to `default`, is ALLOWED the victim branch, and RECEIVES the victim\'s traffic', async () => {
    const victim = await oauthClient('orgA', BR.STREAM_B);
    const gotV = [];
    await victim.subscribe(VICTIM, (m) => gotV.push(m.payload));

    const { client: atk, session } = await connectObserved('orgA', () => oauthClient('orgA', `${BR.STREAM_B}/s1`, { raw: true }));
    expect(session.clientUsername).toBe('default');
    expect(session.originalClientUsername).toBe(`${BR.STREAM_B}/s1`);
    const gotA = [];
    const r1 = await trySubscribe(atk, VICTIM, (m) => gotA.push(m.payload));
    const r2 = await trySubscribe(atk, `${VICTIM}/x`);
    const r3 = await trySubscribe(atk, `sm/v1/orgA/stream/${BR.STREAM_B}/>`);

    const pub = await internal('orgA');
    await pub.publish(VICTIM, { secret: 'victim-only' });
    await waitFor(() => gotV.length >= 1 && gotA.length >= 1);
    await sleep(300);
    // broker-side: the broker itself transmits ONE MORE victim message to the attacker session
    const txAtk = await brokerTx('orgA', session.clientName);
    await pub.publish(VICTIM, { secret: 'victim-only-2' });
    await waitFor(() => gotV.length >= 2 && gotA.length >= 2);
    await sleep(300);
    const brokerToAttacker = (await brokerTx('orgA', session.clientName)) - txAtk;
    record({
      leg: '14-escape', attempted: `raw (rule-free) token sub=${BR.STREAM_B}/s1; subscribe the victim branch sm/v1/orgA/stream/${BR.STREAM_B}/s1 (+ /x, + ${BR.STREAM_B}/>); victim traffic published`,
      expected: 'REFUSED (this is what confinement is supposed to give)', observed: { session, r1, r2, r3, attackerReceived: gotA, victimReceived: gotV, brokerTransmittedToAttackerForOneMoreVictimPublish: brokerToAttacker },
      pass: false, note: 'ESCAPE MEASURED: the broker does not enforce the documented "/" prohibition',
    });
    // The escape is the finding: assert it EXISTS, so that the rule below is provably load-bearing.
    expect(r1).toMatchObject({ outcome: 'allowed' });
    expect(r2).toMatchObject({ outcome: 'allowed' });
    expect(r3).toMatchObject({ outcome: 'allowed' });
    expect(gotA.slice(0, 1)).toEqual([{ secret: 'victim-only' }]);
    expect(brokerToAttacker, 'the broker itself transmitted the victim branch to the attacker').toBeGreaterThanOrEqual(1);
    await Promise.all([victim.disconnect(), atk.disconnect(), pub.disconnect()]);
  }, 60000);

  it('WITH the sub rule: the same hostile subs get HTTP 400 and NO token, so the escape cannot reach the broker', async () => {
    const hostile = [`${BR.STREAM_B}/s1`, `${BR.STREAM_B}/>`, `${BR.STREAM_B}/`, `/${BR.STREAM_B}`, `${BR.STREAM_B}/*`, 'a*', '*', '>', '#', '+', 'a#b', 'a+b', 'a b', 'a\tb', 'a\nb', '', 'x'.repeat(129)];
    const results = [];
    for (const sub of hostile) {
      const m = await tryMint({ org: 'orgA', sub });
      results.push({ sub, status: m.status, error: m.error, reason: m.reason, gotToken: !!m.token });
      expect(m.status, `sub ${JSON.stringify(sub)}`).toBe(400);
      expect(m.token).toBeUndefined();
    }
    record({ leg: '14-rule', attempted: `POST /token with ${hostile.length} hostile subs`, expected: 'HTTP 400, no token', observed: results, pass: true });
  }, 30000);

  it('the rule ACCEPTS exactly the browser grammar (provisioned grants, unprovisioned stream subjects, issuer-generated ids) and REFUSES every other username shape, including the old spike names', async () => {
    const accepted = [BR.STREAM_A, BR.STREAM_B, BR.GRANT, BR.CATALOG, BR.COMP1, 'br-0123456789abcdef'];
    for (const sub of accepted) {
      const m = await tryMint({ org: 'orgA', sub });
      expect(m.ok, `sub ${sub}: ${m.reason}`).toBe(true);
    }
    // sub omitted: the issuer generates it from the grammar (a server-generated id)
    const gen = await tryMint({ org: 'orgA' });
    expect(gen.ok).toBe(true);
    const genSub = JSON.parse(Buffer.from(gen.token.split('.')[1], 'base64url').toString()).sub;
    expect(genSub).toMatch(/^br-[0-9a-f]{16}$/);
    const refused = ['orgA-su1-dev', 'orgA-su1-dev-catalog', 'stream-A', 'user.name@x-y_z', 'a=b', 'a:b', 'a;b', '$SYS', '..', 'default',
      'BR-000000000000d001', 'br-000000000000D001', 'br-000000000000d00', 'br-000000000000d0011', ' br-000000000000d001'];
    for (const sub of refused) {
      const m = await tryMint({ org: 'orgA', sub });
      expect(m.status, `sub ${JSON.stringify(sub)}`).toBe(400);
      expect(m.token).toBeUndefined();
    }
    record({ leg: '14-grammar', attempted: `POST /token with ${accepted.length} grammar subs, one omitted sub, ${refused.length} non-grammar subs`, expected: 'grammar subs and the generated sub minted; every non-grammar sub HTTP 400, no token', observed: { generated: genSub, refused: refused.length }, pass: true });
  }, 30000);

  it('invariant over the measured corpus: EVERY sub that escapes on the broker is rejected by the rule; every rule-accepted sub in the corpus stays confined', async () => {
    const VICTIMS = [VICTIM, `sm/v1/orgA/stream/${BR.STREAM_B}/>`, 'sm/v1/orgA/stream/>', 'sm/v1/orgA/stream/*/s1'];
    // Only values that connect quickly are in-line here; the full corpus (incl. values whose
    // connect hangs — non-ASCII, tab, '?' — none of which escaped or connected) is in
    // tools/hostile-sub-corpus.mjs and evidence/hostile-sub-corpus.log.
    const corpus = [`${BR.STREAM_B}/s1`, `${BR.STREAM_B}/>`, `${BR.STREAM_B}/`, `/${BR.STREAM_B}`, '>', '#', '+', 'a b',
      '$SYS', '$client-username', '.', '..', 'a%2Fb', 'a\\b', 'a;b', `${BR.STREAM_B}%`, 'user.name@x-y_z', 'a=b', 'a:b', 'orgA-su1-dev', 'sm-internal-orgA', '#client-username',
      // grammar-valid subs: the ONLY values the ruled issuer accepts; the rule-accepted half of the invariant is non-vacuous only with these
      'br-0123456789abcdef', 'br-ffffffffffffffff', BR.STREAM_A];
    const rows = [];
    for (const sub of corpus) {
      const rule = await tryMint({ org: 'orgA', sub });
      const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', sub, { raw: true }));
      const out = {};
      for (const v of VICTIMS) out[v] = (await trySubscribe(client, v)).outcome;
      const escaped = Object.values(out).includes('allowed');
      rows.push({ sub, ruleAccepts: rule.ok, resolvedTo: session.clientUsername, escaped });
      if (escaped) expect(rule.ok, `escaping sub ${JSON.stringify(sub)} must be rejected by the rule`).toBe(false);
      if (rule.ok) expect(escaped, `rule-accepted sub ${JSON.stringify(sub)} must stay confined`).toBe(false);
      await client.disconnect();
    }
    const escapers = rows.filter((r) => r.escaped).map((r) => r.sub);
    record({ leg: '14-invariant', attempted: `connect ${corpus.length} raw subs, attempt 4 victim-branch subscriptions each`, expected: 'escapers ⊆ rule-rejected; rule-accepted ⊆ confined', observed: { escapers, rows }, pass: escapers.length > 0 });
    expect(escapers.length, 'the corpus must contain real escapes, or the invariant is vacuous').toBeGreaterThan(0);
  }, 200000);
});
