// §11.3 leg 12 — throwaway spike test, NOT product code.
//
// "Publish anything, anywhere: refuse. The browser never publishes (rule 2·11). G1 measured
// that a publish denial is invisible to the client, so this leg must be asserted from SEMP
// config, not from client behaviour."
//
// Round 1 review changes (BLOCKER 1, MINOR 7, MINOR 9):
//  A. CONFIG (unchanged in kind, extended): every browser-facing client username resolves to
//     client profile 'sm-browser'; that profile has guaranteed send / receive / endpoint create
//     ALL false; every browser-facing ACL profile disallows publish with zero exceptions; the
//     stock 'default' client profile is pinned closed on both VPNs.
//  B. RESOLUTION: the config assertion is tied to what each browser session ACTUALLY resolved
//     to on the broker (monitor API), not to what the config says it should resolve to.
//  C. MEASURED non-delivery (not only config): direct publish and guaranteed publish by a
//     browser identity, into its own granted scope and into a sibling environment, are checked
//     for (i) delivery to an in-VPN witness, (ii) the broker's own per-client publish-ACL
//     discard counter, (iii) spool growth on a queue subscribed to the target. Each has a
//     positive control (the internal publisher, same topic, IS delivered / spooled).

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { SolaceWebAppSDK, BR, NON_BROWSER,
} from './lib/common.mjs';
import {
  internal, oauthClient, connectObserved, attempt, record, sleep, waitFor,
  sempConfig, sempMonitor, sempWrite, clientDetail, sessions, spoofCapabilities, browserAclViolations, sempAll,
} from './lib/common.mjs';

const BROWSER_USERS = [BR.GRANT, BR.CATALOG, 'default'];
const BROWSER_ACLS = ['acl-orgA-su1-dev', 'acl-orgA-su1-dev-catalog', 'acl-orgA-stream-subst'];
const CLOSED = {
  allowGuaranteedMsgSendEnabled: false,
  allowGuaranteedMsgReceiveEnabled: false,
  allowGuaranteedEndpointCreateEnabled: false,
  allowSharedSubscriptionsEnabled: false,
  allowTransactedSessionsEnabled: false,
  allowBridgeConnectionsEnabled: false,
};

describe('§11.3 leg 12A — publish refusal and the browser client profile, asserted from SEMP config', () => {
  for (const acl of BROWSER_ACLS) {
    it(`${acl}: publish default is disallow and carries NO publish exceptions`, async () => {
      const p = await sempConfig(`/msgVpns/orgA/aclProfiles/${acl}`);
      expect(p.data.publishTopicDefaultAction).toBe('disallow');
      const ex = await sempConfig(`/msgVpns/orgA/aclProfiles/${acl}/publishTopicExceptions`);
      expect(ex.data ?? []).toEqual([]);
      record({ leg: '12A', attempted: `read config of ACL profile ${acl}`, expected: 'publishTopicDefaultAction=disallow, zero publishTopicExceptions', observed: { publishDefault: p.data.publishTopicDefaultAction, exceptions: (ex.data ?? []).length }, pass: true });
    }, 30000);
  }

  for (const user of BROWSER_USERS) {
    it(`client username ${user} is bound to client profile sm-browser`, async () => {
      const u = await sempConfig(`/msgVpns/orgA/clientUsernames/${user}`);
      expect(u.data.clientProfileName).toBe('sm-browser');
      record({ leg: '12A', attempted: `read config of client username ${user}`, expected: 'clientProfileName=sm-browser', observed: { clientProfileName: u.data.clientProfileName }, pass: true });
    }, 30000);
  }

  for (const vpn of ['orgA', 'orgB']) {
    it(`${vpn}: client profile sm-browser and the pinned stock 'default' profile have ALL guaranteed/shared/transacted/bridge flags off`, async () => {
      for (const name of ['sm-browser', 'default']) {
        const p = await sempConfig(`/msgVpns/${vpn}/clientProfiles/${name}`);
        for (const [k, v] of Object.entries(CLOSED)) expect(p.data[k], `${vpn}/${name}.${k}`).toBe(v);
        record({ leg: '12A', attempted: `read client profile ${vpn}/${name}`, expected: 'guaranteed send/receive/endpoint-create, shared, transacted, bridge all false', observed: Object.fromEntries(Object.keys(CLOSED).map((k) => [k, p.data[k]])), pass: true });
      }
      const b = await sempConfig(`/msgVpns/${vpn}/clientProfiles/sm-browser`);
      expect([b.data.maxEgressFlowCount, b.data.maxIngressFlowCount, b.data.maxEndpointCountPerClientUsername]).toEqual([0, 0, 0]);
    }, 30000);
  }

  // ROUND 3 MINOR: 12A used to read three orgA ACLs by name. It now covers EVERY browser-facing
  // client username in EVERY VPN (grammar-matching names AND each VPN's `default`, whose ACL is
  // the one orgB browsers actually land on), including the composed ACLs, follows SEMP paging,
  // and asserts the SUBSCRIBE side too: every subscribe exception is one of three shapes inside
  // the VPN's own sm/v1/<vpn>/ prefix (never sm/v1/>, never another organization's prefix, never
  // an organization-wide wildcard). tests/11 plants a violation of each kind to prove the check fires.
  for (const vpn of ['orgA', 'orgB']) {
    it(`${vpn}: EVERY browser-facing client username's ACL (incl. \`default\` and composed grants) allows no publish, and every subscribe exception stays inside sm/v1/${vpn}/`, async () => {
      const v = await browserAclViolations(vpn);
      const users = (await sempAll('config', `/msgVpns/${vpn}/clientUsernames`)).data;
      const facing = users.filter((u) => /^br-[0-9a-f]{16}$/.test(u.clientUsername) || u.clientUsername === 'default');
      const acls = [...new Set(facing.map((u) => u.aclProfileName))];
      const subEx = {};
      for (const a of acls) subEx[a] = (await sempAll('config', `/msgVpns/${vpn}/aclProfiles/${encodeURIComponent(a)}/subscribeTopicExceptions`)).data.map((e) => e.subscribeTopicException);
      record({ leg: '12A-all', attempted: `read every browser-facing client username of VPN ${vpn} from SEMP (paged) and the ACL behind each`, expected: 'publish default disallow + zero publish exceptions; subscribe exceptions only scoped-notify / stream-substitution / exact-catalog inside sm/v1/' + vpn + '/; stock default ACL profile closed and empty', observed: { browserFacingUsernames: facing.map((u) => `${u.clientUsername} -> ${u.aclProfileName}`), subscribeExceptionsByAcl: subEx, violations: v }, pass: v.length === 0 });
      expect(v).toEqual([]);
      expect(facing.length).toBeGreaterThan(0);
    }, 60000);
  }

  it('the stock "default" ACL profile on both VPNs is pinned closed (the fail-safe)', async () => {
    for (const vpn of ['orgA', 'orgB']) {
      const body = await sempConfig(`/msgVpns/${vpn}/aclProfiles/default`);
      expect(body.data.clientConnectDefaultAction, vpn).toBe('disallow');
      expect(body.data.publishTopicDefaultAction, vpn).toBe('disallow');
      expect(body.data.subscribeTopicDefaultAction, vpn).toBe('disallow');
    }
  }, 30000);
});

describe('§11.3 leg 12B — what each browser session ACTUALLY resolved to (broker monitor view)', () => {
  const CASES = [
    [BR.GRANT, 'acl-orgA-su1-dev', BR.GRANT],
    [BR.CATALOG, 'acl-orgA-su1-dev-catalog', BR.CATALOG],
    [BR.STREAM_A, 'acl-orgA-stream-subst', 'default'],
  ];
  for (const [sub, acl, cu] of CASES) {
    it(`sub=${sub} -> clientUsername ${cu}, ACL ${acl}, client profile sm-browser`, async () => {
      const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', sub));
      expect(session.clientUsername).toBe(cu);
      expect(session.aclProfileName).toBe(acl);
      expect(session.clientProfileName).toBe('sm-browser');
      record({ leg: '12B', attempted: `connect as sub=${sub}, read the session from SEMP monitor`, expected: `clientUsername=${cu} acl=${acl} clientProfile=sm-browser`, observed: session, pass: true });
      await client.disconnect();
    }, 30000);
  }
});

describe('§11.3 leg 12C — measured non-delivery of browser publishes (direct AND guaranteed)', () => {
  const OWN = 'sm/v1/orgA/su1/dev/notify/solution/s1/instance/forged/health';
  const SIBLING = 'sm/v1/orgA/su1/prod/notify/solution/s1/instance/forged/health';
  const QUEUE = 'sm-q-forge-watch';
  let witness, pub;
  const seen = [];

  beforeAll(async () => {
    // A queue that would ATTRACT any forged event on either topic, so guaranteed-publish
    // acceptance shows up as spool growth even if no direct subscriber is listening.
    await sempWrite('DELETE', `/msgVpns/orgA/queues/${QUEUE}`);
    const q = await sempWrite('POST', '/msgVpns/orgA/queues', { msgVpnName: 'orgA', queueName: QUEUE, owner: 'sm-internal-orgA', permission: 'no-access', ingressEnabled: true, egressEnabled: true, accessType: 'non-exclusive' });
    expect(q.ok, JSON.stringify(q.error)).toBe(true);
    for (const t of [OWN, SIBLING]) {
      const s = await sempWrite('POST', `/msgVpns/orgA/queues/${QUEUE}/subscriptions`, { msgVpnName: 'orgA', queueName: QUEUE, subscriptionTopic: t });
      expect(s.ok, JSON.stringify(s.error)).toBe(true);
    }
    witness = await internal('orgA');
    await witness.subscribe('sm/v1/>', (m) => seen.push(m.topic));
    pub = await internal('orgA');
  }, 60000);

  afterAll(async () => {
    await Promise.all([witness?.disconnect(), pub?.disconnect()]);
    await sempWrite('DELETE', `/msgVpns/orgA/queues/${QUEUE}`);
  });

  const spooled = async () => (await sempMonitor(`/msgVpns/orgA/queues/${QUEUE}/msgs?count=100`)).data?.length ?? 0;

  it('positive controls: the internal publisher\'s direct publish reaches the witness; its guaranteed publish is spooled by the watch queue', async () => {
    seen.length = 0;
    await pub.publish(OWN, { from: 'control' });
    const ok = await waitFor(() => seen.includes(OWN));
    expect(ok, 'witness is live (direct)').toBe(true);
    await pub.publishGuaranteed(SIBLING, { from: 'control' });
    const spool = await waitFor(async () => (await spooled()) >= 1);
    expect(spool, 'watch queue is live (guaranteed spool)').toBe(true);
    record({ leg: '12C-control', attempted: 'sm-internal-orgA direct publish + guaranteed publish', expected: 'delivered to witness / spooled to watch queue', observed: { witnessHeard: seen.length, spooled: await spooled() }, pass: true });
  }, 30000);

  it('browser DIRECT publish (own scope AND sibling env): call resolves silently, NOTHING is delivered, and the broker counts the publish-ACL discards', async () => {
    const before = await spooled();
    seen.length = 0;
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    const heardSelf = [];
    await client.subscribe('sm/v1/orgA/su1/dev/notify/>', (m) => heardSelf.push(m.topic));
    const rOwn = await attempt(() => client.publish(OWN, { from: 'browser' }));
    const rSib = await attempt(() => client.publish(SIBLING, { from: 'browser' }));
    await sleep(1200);
    const d = await clientDetail('orgA', session.clientName);
    record({
      leg: '12C-direct', attempted: `orgA-su1-dev publishes direct to ${OWN} and ${SIBLING}`,
      expected: 'not delivered (witness, self-loop, watch queue); broker publishTopicAclRxDiscardedMsgCount == 2',
      observed: { ownCall: rOwn, siblingCall: rSib, witnessHeard: seen.length, selfLoop: heardSelf.length, spoolDelta: (await spooled()) - before, publishTopicAclRxDiscardedMsgCount: d.publishTopicAclRxDiscardedMsgCount },
      pass: seen.length === 0 && heardSelf.length === 0 && d.publishTopicAclRxDiscardedMsgCount === 2,
    });
    expect(seen).toEqual([]);
    expect(heardSelf).toEqual([]);
    expect((await spooled()) - before).toBe(0);
    expect(d.publishTopicAclRxDiscardedMsgCount).toBe(2);
    await client.disconnect();
  }, 30000);

  it('browser GUARANTEED publish (own scope AND sibling env): refused at the client (sm-browser forbids guaranteed send), NOTHING is spooled or delivered', async () => {
    const before = await spooled();
    seen.length = 0;
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    const rOwn = await attempt(() => client.publishGuaranteed(OWN, { from: 'browser-g' }));
    const rSib = await attempt(() => client.publishGuaranteed(SIBLING, { from: 'browser-g' }));
    await sleep(1200);
    const d = await clientDetail('orgA', session.clientName);
    record({
      leg: '12C-guaranteed', attempted: `orgA-su1-dev publishGuaranteed to ${OWN} and ${SIBLING}`,
      expected: 'refused or dropped; no spool growth; nothing delivered',
      observed: { ownCall: rOwn, siblingCall: rSib, witnessHeard: seen.length, spoolDelta: (await spooled()) - before, publishTopicAclRxDiscardedMsgCount: d.publishTopicAclRxDiscardedMsgCount },
      pass: (await spooled()) === before && seen.length === 0,
    });
    expect(rOwn.outcome).toBe('rejected');
    expect(rSib.outcome).toBe('rejected');
    expect(seen).toEqual([]);
    expect(await spooled()).toBe(before);
    await client.disconnect();
  }, 30000);

  it('SPOOFED browser (capability table forced open so the library sends it): GUARANTEED publish reaches the broker and is still not spooled or delivered — the broker, not the library, refuses', async () => {
    const before = await spooled();
    seen.length = 0;
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', BR.GRANT));
    spoofCapabilities(client);
    const rOwn = await attempt(() => client.publishGuaranteed(OWN, { from: 'browser-g-spoof' }));
    const rSib = await attempt(() => client.publishGuaranteed(SIBLING, { from: 'browser-g-spoof' }));
    await sleep(1500);
    const d = await clientDetail('orgA', session.clientName);
    record({
      leg: '12C-guaranteed-wire', attempted: `orgA-su1-dev (sm-browser), capability table forced to capable: publishGuaranteed to ${OWN} and ${SIBLING}`,
      expected: 'nothing spooled, nothing delivered (broker-side refusal)',
      observed: { ownCall: rOwn, siblingCall: rSib, witnessHeard: seen.length, spoolDelta: (await spooled()) - before, brokerCounters: { publishTopicAclRxDiscardedMsgCount: d.publishTopicAclRxDiscardedMsgCount, rxDiscardedMsgCount: d.rxDiscardedMsgCount, dataRxMsgCount: d.dataRxMsgCount, msgSpoolRxDiscardedMsgCount: d.msgSpoolRxDiscardedMsgCount } },
      pass: seen.length === 0 && (await spooled()) === before,
    });
    expect(seen).toEqual([]);
    expect(await spooled()).toBe(before);
    await client.disconnect();
  }, 30000);

  it('FLAG ISOLATION: an identity whose ACL DOES permit publish (acl-internal) but whose client profile has only guaranteed-send off — control (sm-internal) spools the guaranteed publish; the flag-off identity, capability table forced open, does not', async () => {
    const before = await spooled();
    // control: same ACL, flag ON
    await pub.publishGuaranteed(OWN, { from: 'flag-control' });
    const controlSpooled = await waitFor(async () => (await spooled()) === before + 1);
    // flag OFF, ACL permits publish
    const off = await SolaceWebAppSDK.connect({ url: 'wss://localhost:9444/mesh', vpn: 'orgA', protocol: 'smf', auth: { type: 'basic', username: 'orgA-abl-pub-send-off', password: 'orgA-abl-pub-send-off' } });
    const plain = await attempt(() => off.publishGuaranteed(OWN, { from: 'flag-off-plain' }));
    spoofCapabilities(off);
    const wire = await attempt(() => off.publishGuaranteed(OWN, { from: 'flag-off-wire' }));
    await sleep(1500);
    const after = await spooled();
    record({
      leg: '12C-flag-isolation', attempted: 'orgA-abl-pub-send-off (acl-internal, allowGuaranteedMsgSendEnabled=false) publishGuaranteed to a topic the watch queue subscribes; plain, then capability gate forced open; control = sm-internal-orgA same ACL',
      expected: 'control spools 1; flag-off identity spools 0 (plain: library gate; wire: broker)',
      observed: { controlSpooled, flagOffPlain: plain, flagOffWire: wire, spoolDeltaFromFlagOffIdentity: after - (before + 1) },
      pass: controlSpooled && after === before + 1,
    });
    expect(controlSpooled, 'control must spool, or the flag-off result is unattributable').toBe(true);
    expect(after, 'flag-off identity must add nothing to the spool').toBe(before + 1);
    await off.disconnect();
  }, 30000);

  it('attribution control: the SAME ACL profile under the permissive sm-internal client profile — guaranteed publish is still not spooled (the ACL alone denies publish); the client profile is a second, independent line', async () => {
    const before = await spooled();
    seen.length = 0;
    const { client, session } = await connectObserved('orgA', () => oauthClient('orgA', NON_BROWSER.CTL, { raw: true }));
    expect(session.clientProfileName).toBe('sm-internal');
    expect(session.aclProfileName).toBe('acl-orgA-su1-dev');
    const rOwn = await attempt(() => client.publishGuaranteed(OWN, { from: 'ctl-g' }));
    const rDirect = await attempt(() => client.publish(OWN, { from: 'ctl-d' }));
    await sleep(1200);
    const d = await clientDetail('orgA', session.clientName);
    record({
      leg: '12C-attribution', attempted: 'orgA-su1-dev-ctl (same ACL, sm-internal client profile) publishes guaranteed + direct into its own scope',
      expected: 'still nothing spooled/delivered: ACL publish default disallow denies it independently of the client profile',
      observed: { guaranteedCall: rOwn, directCall: rDirect, witnessHeard: seen.length, spoolDelta: (await spooled()) - before, publishTopicAclRxDiscardedMsgCount: d.publishTopicAclRxDiscardedMsgCount },
      pass: seen.length === 0 && (await spooled()) === before,
    });
    expect(seen).toEqual([]);
    expect(await spooled()).toBe(before);
    await client.disconnect();
  }, 30000);
});
