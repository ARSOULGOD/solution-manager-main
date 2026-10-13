// Round 1 review MINOR 8 — MEASURED broker-write counts per grant and per revocation.
// Throwaway spike test, NOT product code.
//
// Method. A "write" here is a SEMP config POST/PATCH/DELETE that the broker answered with
// responseCode 200 (`sempWrite()` counts exactly those). Each count is cross-checked against an
// INVENTORY DIFF read back from SEMP (objects present before vs after), so the number is what
// the broker holds, not what the harness believes it sent. The subject is named `w1 (BR.W1)`, NOT
// after an environment, so "same subject, second environment" is a real scenario (the old
// fixture named the user `orgA-su1-dev`, which made that scenario self-contradictory).
//
// Shared per-VPN objects (VPN, OAuth profile, client profiles, acl-internal, ...) are provisioned
// once per organization and are NOT counted per grant.

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  internal, oauthClient, connectObserved, trySubscribe, record, sleep, waitFor,
  sempConfig, sempWrite, sempAction, writeCount, resetWriteCount, sessions, subscriptionsOf, ACL_DENIED, BR, NON_BROWSER,
} from './lib/common.mjs';

const SUBJ = BR.W1;
const ACL = `acl-${SUBJ}`;
const topicFor = (su, env) => `sm/v1/orgA/${su}/${env}/notify/>`;
const excPath = (su, env) => `/msgVpns/orgA/aclProfiles/${ACL}/subscribeTopicExceptions/smf,${encodeURIComponent(topicFor(su, env))}`;

async function inventory() {
  const acls = ((await sempConfig('/msgVpns/orgA/aclProfiles?count=200')).data ?? []).map((a) => a.aclProfileName);
  const users = ((await sempConfig('/msgVpns/orgA/clientUsernames?count=200')).data ?? []).map((u) => u.clientUsername);
  const exc = (await sempConfig(`/msgVpns/orgA/aclProfiles/${ACL}/subscribeTopicExceptions?count=50`)).data?.map((e) => e.subscribeTopicException) ?? [];
  return { acls, users, exc };
}
const diff = (a, b) => ({
  aclProfiles: b.acls.length - a.acls.length,
  clientUsernames: b.users.length - a.users.length,
  exceptions: b.exc.length - a.exc.length,
});

async function addException(su, env) {
  return sempWrite('POST', `/msgVpns/orgA/aclProfiles/${ACL}/subscribeTopicExceptions`, { msgVpnName: 'orgA', aclProfileName: ACL, subscribeTopicExceptionSyntax: 'smf', subscribeTopicException: topicFor(su, env) });
}

let pub;
beforeAll(async () => {
  pub = await internal('orgA');
  await sempWrite('DELETE', `/msgVpns/orgA/clientUsernames/${SUBJ}`);
  await sempWrite('DELETE', `/msgVpns/orgA/clientUsernames/${BR.W2}`);
  await sempWrite('DELETE', `/msgVpns/orgA/clientUsernames/${BR.W3}`);
  await sempWrite('DELETE', `/msgVpns/orgA/aclProfiles/acl-${BR.W3}`);
  await sempWrite('DELETE', `/msgVpns/orgA/aclProfiles/${ACL}`);
  await sempWrite('DELETE', `/msgVpns/orgA/aclProfiles/acl-${BR.W2}`);
}, 30000);
afterAll(async () => {
  await pub?.disconnect();
  for (const p of [`/msgVpns/orgA/clientUsernames/${BR.W3}`, `/msgVpns/orgA/aclProfiles/acl-${BR.W3}`, `/msgVpns/orgA/clientUsernames/${SUBJ}`, `/msgVpns/orgA/clientUsernames/${BR.W2}`, `/msgVpns/orgA/aclProfiles/${ACL}`, `/msgVpns/orgA/aclProfiles/acl-${BR.W2}`]) await sempWrite('DELETE', p);
});

describe('MINOR 8 — measured write counts (grant, extension, revocation)', () => {
  it('W1: a NEW subject at ONE [sub-org, env] costs 3 writes (ACL profile + exception + client username)', async () => {
    const before = await inventory(); resetWriteCount();
    const a = await sempWrite('POST', '/msgVpns/orgA/aclProfiles', { msgVpnName: 'orgA', aclProfileName: ACL, clientConnectDefaultAction: 'allow', publishTopicDefaultAction: 'disallow', subscribeTopicDefaultAction: 'disallow' });
    const e = await addException('su1', 'dev');
    const u = await sempWrite('POST', '/msgVpns/orgA/clientUsernames', { msgVpnName: 'orgA', clientUsername: SUBJ, aclProfileName: ACL, clientProfileName: 'sm-browser', enabled: true });
    expect([a.ok, e.ok, u.ok], JSON.stringify([a.error, e.error, u.error])).toEqual([true, true, true]);
    const n = writeCount(); const d = diff(before, await inventory());
    record({ leg: 'W1', attempted: 'grant new subject w1 (BR.W1) at [orgA,su1,dev]', expected: '3 writes', observed: { writes: n, inventoryDiff: d }, pass: n === 3 });
    expect(n).toBe(3);
    expect(d).toEqual({ aclProfiles: 1, clientUsernames: 1, exceptions: 1 });

    const c = await oauthClient('orgA', SUBJ);
    expect(await trySubscribe(c, topicFor('su1', 'dev'))).toMatchObject({ outcome: 'allowed' });
    expect(await trySubscribe(c, topicFor('su1', 'prod'))).toMatchObject(ACL_DENIED);
    await c.disconnect();
  }, 60000);

  it('W2/W3: each ADDITIONAL [sub-org, env] for the SAME subject costs 1 write (one more exception); a session connected BEFORE the write picks it up without reconnecting', async () => {
    const early = await connectObserved('orgA', () => oauthClient('orgA', SUBJ));
    expect(await trySubscribe(early.client, topicFor('su1', 'prod'))).toMatchObject(ACL_DENIED);

    for (const [su, env, w] of [['su1', 'prod', 'W2'], ['su2', 'dev', 'W3']]) {
      const before = await inventory(); resetWriteCount();
      const e = await addException(su, env);
      expect(e.ok, JSON.stringify(e.error)).toBe(true);
      const n = writeCount(); const d = diff(before, await inventory());
      record({ leg: w, attempted: `extend w1 (BR.W1) with [orgA,${su},${env}]`, expected: '1 write', observed: { writes: n, inventoryDiff: d }, pass: n === 1 });
      expect(n).toBe(1);
      expect(d).toEqual({ aclProfiles: 0, clientUsernames: 0, exceptions: 1 });
    }
    const liveLater = await trySubscribe(early.client, topicFor('su1', 'prod'));
    const fresh = await oauthClient('orgA', SUBJ);
    const freshProd = await trySubscribe(fresh, topicFor('su1', 'prod'));
    const freshSu2 = await trySubscribe(fresh, topicFor('su2', 'dev'));
    const freshSu2Prod = await trySubscribe(fresh, topicFor('su2', 'prod'));
    record({ leg: 'W2/W3-effect', attempted: 'after the two exception writes: (a) the pre-existing session subscribes su1/prod; (b) a fresh session subscribes su1/prod, su2/dev, su2/prod', expected: 'allowed, allowed, allowed, refused (su2/prod was never granted)', observed: { preExistingSession: liveLater, freshProd, freshSu2, freshSu2Prod }, pass: liveLater.outcome === 'allowed' && freshSu2Prod.outcome === 'acl-denied' });
    expect(freshProd).toMatchObject({ outcome: 'allowed' });
    expect(freshSu2).toMatchObject({ outcome: 'allowed' });
    expect(freshSu2Prod).toMatchObject(ACL_DENIED);
    await Promise.all([early.client.disconnect(), fresh.disconnect()]);
  }, 60000);

  it('W4: REVOKING one environment from a subject costs 1 write; a NEW subscribe is refused — and the effect on an ALREADY-ACTIVE subscription is measured', async () => {
    const live = await connectObserved('orgA', () => oauthClient('orgA', SUBJ));
    const got = [];
    const T = 'sm/v1/orgA/su1/prod/notify/solution/s1/instance/i-w4/health';
    expect(await trySubscribe(live.client, topicFor('su1', 'prod'), (m) => got.push(m.payload))).toMatchObject({ outcome: 'allowed' });
    await pub.publish(T, { n: 'before-revoke' });
    expect(await waitFor(() => got.length === 1)).toBe(true);

    const before = await inventory(); resetWriteCount();
    const del = await sempWrite('DELETE', excPath('su1', 'prod'));
    expect(del.ok, JSON.stringify(del.error)).toBe(true);
    const n = writeCount(); const d = diff(before, await inventory());
    const fresh = await oauthClient('orgA', SUBJ);
    const freshProd = await trySubscribe(fresh, topicFor('su1', 'prod'));
    await pub.publish(T, { n: 'after-revoke' });
    await sleep(1500);
    const stillConnected = live.client.isConnected();
    record({
      leg: 'W4', attempted: 'DELETE the [su1,prod] exception of w1 (BR.W1)', expected: '1 write; new subscribe refused',
      observed: { writes: n, inventoryDiff: d, freshSubscribeAfterRevoke: freshProd, activeSubscriptionSessionStillConnected: stillConnected, activeSubscriptionDeliveredAfterRevoke: got.length === 2 ? 'YES — revoked subscription kept delivering' : 'no', received: got },
      pass: n === 1 && freshProd.outcome === 'acl-denied',
    });
    expect(n).toBe(1);
    expect(d).toEqual({ aclProfiles: 0, clientUsernames: 0, exceptions: -1 });
    expect(freshProd).toMatchObject(ACL_DENIED);
    await Promise.all([live.client.disconnect(), fresh.disconnect()]);
  }, 60000);

  it('W4b: revoking an environment AND cutting the live session costs 2 writes (delete exception + disconnect client); only then does the revoked subscription stop delivering', async () => {
    // re-grant su1/prod so there is something to revoke (this write is setup, not counted)
    const re = await addException('su1', 'prod');
    expect(re.ok, JSON.stringify(re.error)).toBe(true);
    const live = await connectObserved('orgA', () => oauthClient('orgA', SUBJ));
    const got = [];
    const T = 'sm/v1/orgA/su1/prod/notify/solution/s1/instance/i-w4b/health';
    expect(await trySubscribe(live.client, topicFor('su1', 'prod'), (m) => got.push(m.payload))).toMatchObject({ outcome: 'allowed' });
    await pub.publish(T, { n: 'before' });
    expect(await waitFor(() => got.length === 1)).toBe(true);

    resetWriteCount();
    const del = await sempWrite('DELETE', excPath('su1', 'prod'));
    const kick = await sempAction(`/msgVpns/orgA/clients/${encodeURIComponent(live.session.clientName)}/disconnect`);
    expect([del.ok, kick.ok], JSON.stringify([del.error, kick.error])).toEqual([true, true]);
    const n = writeCount();
    await sleep(500);
    await pub.publish(T, { n: 'after-kick' });
    await sleep(2500);
    const onBroker = (await sessions('orgA')).filter((s) => s.originalClientUsername === SUBJ);
    // If the SDK reconnected on its own, what did the new session resolve to and hold?
    const resubs = await Promise.all(onBroker.map(async (s) => ({ clientName: s.clientName, subscriptions: (await subscriptionsOf('orgA', s.clientName)).filter((x) => !x.startsWith('#P2P')) })));
    const deliveredAfter = got.filter((g) => g.n === 'after-kick').length;
    record({
      leg: 'W4b', attempted: 'DELETE the [su1,prod] exception, then SEMP disconnect the live session', expected: '2 writes; the revoked subscription no longer delivers',
      observed: { writes: n, deliveredAfterKick: deliveredAfter, sessionsOfSubjectStillOnBroker: onBroker.length, theirBrokerHeldSubscriptions: resubs },
      pass: n === 2 && deliveredAfter === 0,
    });
    expect(n).toBe(2);
    expect(deliveredAfter).toBe(0);
    await live.client.disconnect().catch(() => {});
  }, 60000);

  it('W5: REVOKING the whole subject (delete client username, then ACL profile) costs 2 writes; the effect on an already-connected session is measured', async () => {
    const live = await connectObserved('orgA', () => oauthClient('orgA', SUBJ));
    const got = [];
    const T = 'sm/v1/orgA/su1/dev/notify/solution/s1/instance/i-w5/health';
    expect(await trySubscribe(live.client, topicFor('su1', 'dev'), (m) => got.push(m.payload))).toMatchObject({ outcome: 'allowed' });

    const before = await inventory(); resetWriteCount();
    const du = await sempWrite('DELETE', `/msgVpns/orgA/clientUsernames/${SUBJ}`);
    const da = await sempWrite('DELETE', `/msgVpns/orgA/aclProfiles/${ACL}`);
    expect([du.ok, da.ok], JSON.stringify([du.error, da.error])).toEqual([true, true]);
    const n = writeCount();
    const after = await inventory();
    await sleep(1500);
    const sess = (await sessions('orgA')).find((s) => s.clientName === live.session.clientName);
    const heldSubs = sess ? (await subscriptionsOf('orgA', sess.clientName)).filter((x) => !x.startsWith('#P2P')) : null;
    await pub.publish(T, { n: 'after-subject-revoke' });
    await sleep(1000);
    // A fresh login after revocation falls through to `default` (the stream profile): it must not
    // carry the revoked notify grant.
    const fresh = await connectObserved('orgA', () => oauthClient('orgA', SUBJ));
    const freshDev = await trySubscribe(fresh.client, topicFor('su1', 'dev'));
    record({
      leg: 'W5', attempted: 'DELETE clientUsernames/w1 (BR.W1) then aclProfiles/acl-w1 (BR.W1)', expected: '2 writes; fresh login has no notify grant',
      observed: { writes: n, aclProfilesRemoved: before.acls.length - after.acls.length, usersRemoved: before.users.length - after.users.length, oldSessionStillListedOnBroker: !!sess, oldSessionResolvedTo: sess ? { clientUsername: sess.clientUsername, acl: sess.aclProfileName } : null, oldSessionBrokerHeldSubscriptions: heldSubs, oldSessionDeliveredAfterRevoke: got.length > 0, freshLoginResolvedTo: { clientUsername: fresh.session.clientUsername, acl: fresh.session.aclProfileName }, freshSubscribeSu1Dev: freshDev },
      pass: n === 2 && freshDev.outcome === 'acl-denied',
    });
    expect(n).toBe(2);
    expect(freshDev).toMatchObject(ACL_DENIED);
    await Promise.all([live.client.disconnect().catch(() => {}), fresh.client.disconnect()]);
  }, 60000);

  it('W6: revoking by DISABLING the client username is 1 write — and whether it drops the live session is measured (compare W5)', async () => {
    resetWriteCount();
    const p = await sempWrite('POST', '/msgVpns/orgA/aclProfiles', { msgVpnName: 'orgA', aclProfileName: `acl-${BR.W2}`, clientConnectDefaultAction: 'allow', publishTopicDefaultAction: 'disallow', subscribeTopicDefaultAction: 'disallow' });
    const e = await sempWrite('POST', `/msgVpns/orgA/aclProfiles/acl-${BR.W2}/subscribeTopicExceptions`, { msgVpnName: 'orgA', aclProfileName: `acl-${BR.W2}`, subscribeTopicExceptionSyntax: 'smf', subscribeTopicException: topicFor('su1', 'dev') });
    const u = await sempWrite('POST', '/msgVpns/orgA/clientUsernames', { msgVpnName: 'orgA', clientUsername: BR.W2, aclProfileName: `acl-${BR.W2}`, clientProfileName: 'sm-browser', enabled: true });
    expect([p.ok, e.ok, u.ok]).toEqual([true, true, true]);
    const live = await connectObserved('orgA', () => oauthClient('orgA', BR.W2));
    resetWriteCount();
    const dis = await sempWrite('PATCH', `/msgVpns/orgA/clientUsernames/${BR.W2}`, { enabled: false });
    expect(dis.ok, JSON.stringify(dis.error)).toBe(true);
    const n = writeCount();
    await sleep(2000);
    const sess = (await sessions('orgA')).find((s) => s.clientName === live.session.clientName);
    let reLogin;
    try { const c = await Promise.race([oauthClient('orgA', BR.W2), new Promise((_, r) => setTimeout(() => r(new Error('login hung/refused')), 8000))]); reLogin = 'connected'; await c.disconnect(); } catch (err) { reLogin = `refused: ${err.message}`; }
    record({ leg: 'W6', attempted: 'PATCH clientUsernames/BR.W2 enabled=false', expected: '1 write', observed: { writes: n, liveSessionStillOnBroker: !!sess, liveClientStillConnected: live.client.isConnected(), reLogin }, pass: n === 1 });
    expect(n).toBe(1);
    await live.client.disconnect().catch(() => {});
  }, 60000);

  it('W7: a stream-tier subject (zero-write route) costs 0 writes and adds 0 objects', async () => {
    const before = await inventory(); resetWriteCount();
    const c = await oauthClient('orgA', BR.STREAM_Z);
    const r = await trySubscribe(c, `sm/v1/orgA/stream/${BR.STREAM_Z}/s1`);
    const after = await inventory();
    record({ leg: 'W7', attempted: 'connect sub=BR.STREAM_Z and subscribe its branch', expected: '0 writes, 0 new objects', observed: { writes: writeCount(), aclProfilesDelta: after.acls.length - before.acls.length, clientUsernamesDelta: after.users.length - before.users.length, subscribe: r }, pass: writeCount() === 0 && after.users.length === before.users.length });
    expect(writeCount()).toBe(0);
    expect(after.users.length).toBe(before.users.length);
    expect(after.acls.length).toBe(before.acls.length);
    expect(r).toMatchObject({ outcome: 'allowed' });
    await c.disconnect();
  }, 30000);
  it('W8 (round 2): a COMPOSED grant (notify literal exception + stream $client-username exception on one dedicated profile) costs 4 writes, and the session it produces is confined on both branches', async () => {
    const ACL3 = `acl-${BR.W3}`;
    const before = await inventory(); resetWriteCount();
    const a = await sempWrite('POST', '/msgVpns/orgA/aclProfiles', { msgVpnName: 'orgA', aclProfileName: ACL3, clientConnectDefaultAction: 'allow', publishTopicDefaultAction: 'disallow', subscribeTopicDefaultAction: 'disallow' });
    const e1 = await sempWrite('POST', `/msgVpns/orgA/aclProfiles/${ACL3}/subscribeTopicExceptions`, { msgVpnName: 'orgA', aclProfileName: ACL3, subscribeTopicExceptionSyntax: 'smf', subscribeTopicException: topicFor('su1', 'dev') });
    const e2 = await sempWrite('POST', `/msgVpns/orgA/aclProfiles/${ACL3}/subscribeTopicExceptions`, { msgVpnName: 'orgA', aclProfileName: ACL3, subscribeTopicExceptionSyntax: 'smf', subscribeTopicException: 'sm/v1/orgA/stream/$client-username/>' });
    const u = await sempWrite('POST', '/msgVpns/orgA/clientUsernames', { msgVpnName: 'orgA', clientUsername: BR.W3, aclProfileName: ACL3, clientProfileName: 'sm-browser', enabled: true });
    expect([a.ok, e1.ok, e2.ok, u.ok], JSON.stringify([a.error, e1.error, e2.error, u.error])).toEqual([true, true, true, true]);
    const n = writeCount();
    const after = await inventory();
    const c = await oauthClient('orgA', BR.W3);
    const own = await trySubscribe(c, topicFor('su1', 'dev'));
    const stream = await trySubscribe(c, `sm/v1/orgA/stream/${BR.W3}/x`);
    const other = await trySubscribe(c, `sm/v1/orgA/stream/${BR.W2}/x`);
    const prod = await trySubscribe(c, topicFor('su1', 'prod'));
    record({ leg: 'W8', attempted: 'grant a NEW subject notify [su1,dev] AND the stream branch on one dedicated profile', expected: '4 writes (ACL profile, notify exception, stream exception, client username)', observed: { writes: n, aclProfilesAdded: after.acls.length - before.acls.length, clientUsernamesAdded: after.users.length - before.users.length, own, stream, other, prod }, pass: n === 4 });
    expect(n).toBe(4);
    expect(own).toMatchObject({ outcome: 'allowed' }); expect(stream).toMatchObject({ outcome: 'allowed' });
    expect(other).toMatchObject(ACL_DENIED); expect(prod).toMatchObject(ACL_DENIED);
    await c.disconnect();
  }, 60000);
});
