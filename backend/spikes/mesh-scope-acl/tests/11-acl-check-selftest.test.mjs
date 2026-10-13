// Round 3 MINOR 5 — the browser-facing ACL check must be able to FAIL. Throwaway spike test.
// ROUND 4 (MINOR 2): there is now ONE implementation (tests/lib/acl-check.mjs) behind 12A-all, 16-config AND
// provision.sh (via tools/acl-check.mjs). Every plant below is therefore also run through that install-time entry
// point (exit code + printed violation), three plants cover the checks the old Python copy lacked (V12 default-username
// pin, V13 audience, V14 exception syntax), and the last test runs ./provision.sh itself against a plant.
//
// tests/02 (12A-all) and tests/07 (16-config) assert `browserAclViolations(vpn)` is empty. An
// assertion that is never shown to fire proves nothing, so this file PLANTS each kind of
// violation the round-3 review named, requires the check to report it, removes it, and requires
// the check to be clean again. It also forces SEMP paging (page size 2) to show the reader
// follows the cursor and returns the same set as an unpaged read.

import { describe, it, expect, afterAll } from 'vitest';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { browserAclViolations, sempAll, sempWrite, sempConfig, record, sleep } from './lib/common.mjs';

const SPIKE = new URL('../', import.meta.url).pathname;
const execFileP = promisify(execFile);
/** Run an executable exactly as an operator would; never throws. {code, out}. */
async function run(cmd, args, { timeout = 60000 } = {}) {
  try { const r = await execFileP(cmd, args, { cwd: SPIKE, timeout, maxBuffer: 8 * 1024 * 1024 }); return { code: 0, out: r.stdout + r.stderr }; }
  catch (e) { return { code: typeof e.code === 'number' ? e.code : -1, out: `${e.stdout ?? ''}${e.stderr ?? ''}` }; }
}
/** The install-time entry point provision.sh runs (round 4 MINOR 2). */
const cli = () => run('node', ['tools/acl-check.mjs', 'orgA', 'orgB']);

const U = (n) => `br-0000000f${String(n).padStart(8, '0')}`;      // grammar-matching, unprovisioned elsewhere
const created = [];                                                  // cleanup, LIFO; an entry is a SEMP path to DELETE or an async undo function
const del = async (path) => (typeof path === 'function' ? path() : sempWrite('DELETE', path));
afterAll(async () => { for (const p of created.reverse()) await del(p); });

async function mkAcl(vpn, name, { publishDefault = 'disallow', subscribeDefault = 'disallow' } = {}) {
  const r = await sempWrite('POST', `/msgVpns/${vpn}/aclProfiles`, { msgVpnName: vpn, aclProfileName: name, clientConnectDefaultAction: 'allow', publishTopicDefaultAction: publishDefault, subscribeTopicDefaultAction: subscribeDefault });
  expect(r.ok, JSON.stringify(r.error)).toBe(true);
  created.push(`/msgVpns/${vpn}/aclProfiles/${name}`);
}
async function mkUser(vpn, name, acl, clientProfile = 'sm-browser') {
  const r = await sempWrite('POST', `/msgVpns/${vpn}/clientUsernames`, { msgVpnName: vpn, clientUsername: name, aclProfileName: acl, clientProfileName: clientProfile, enabled: true });
  expect(r.ok, JSON.stringify(r.error)).toBe(true);
  created.push(`/msgVpns/${vpn}/clientUsernames/${name}`); // LIFO cleanup: the user is removed BEFORE its ACL
}
const subEx = (vpn, acl, topic) => sempWrite('POST', `/msgVpns/${vpn}/aclProfiles/${acl}/subscribeTopicExceptions`, { msgVpnName: vpn, aclProfileName: acl, subscribeTopicExceptionSyntax: 'smf', subscribeTopicException: topic });
const pubEx = (vpn, acl, topic) => sempWrite('POST', `/msgVpns/${vpn}/aclProfiles/${acl}/publishTopicExceptions`, { msgVpnName: vpn, aclProfileName: acl, publishTopicExceptionSyntax: 'smf', publishTopicException: topic });
const enc = (t) => encodeURIComponent(t);

describe('round 3 MINOR 5 — the check fires on planted violations, and is clean again after each is removed', () => {
  it('baseline: both VPNs clean, by the function AND by the install-time entry point', async () => {
    expect(await browserAclViolations('orgA')).toEqual([]);
    expect(await browserAclViolations('orgB')).toEqual([]);
    const c = await cli();
    record({ leg: '12A-plant-baseline', attempted: 'node tools/acl-check.mjs orgA orgB on the provisioned state', expected: 'exit 0', observed: { exitCode: c.code, out: c.out.trim().split('\n') }, pass: c.code === 0 });
    expect(c.code, c.out).toBe(0);
  }, 60000);

  const PLANTS = [
    ['V1 a grammar username whose ACL allows subscribing sm/v1/> (sub exception)', 'orgA', async () => { await mkAcl('orgA', 'acl-plant-v1'); await subEx('orgA', 'acl-plant-v1', 'sm/v1/>'); await mkUser('orgA', U(1), 'acl-plant-v1'); }, /acl-plant-v1: subscribe exception sm\/v1\/> is outside/],
    ['V2 a grammar username whose ACL carries ANOTHER organization\'s prefix', 'orgA', async () => { await mkAcl('orgA', 'acl-plant-v2'); await subEx('orgA', 'acl-plant-v2', 'sm/v1/orgB/su1/dev/notify/>'); await mkUser('orgA', U(2), 'acl-plant-v2'); }, /acl-plant-v2: subscribe exception sm\/v1\/orgB\/su1\/dev\/notify\/> is outside/],
    ['V3 an organization-wide wildcard sm/v1/orgA/> on a grammar username', 'orgA', async () => { await mkAcl('orgA', 'acl-plant-v3'); await subEx('orgA', 'acl-plant-v3', 'sm/v1/orgA/>'); await mkUser('orgA', U(3), 'acl-plant-v3'); }, /acl-plant-v3: subscribe exception sm\/v1\/orgA\/> is outside/],
    ['V4 a PUBLISH exception on a grammar username\'s ACL', 'orgA', async () => { await mkAcl('orgA', 'acl-plant-v4'); await pubEx('orgA', 'acl-plant-v4', 'sm/v1/orgA/su1/dev/notify/>'); await mkUser('orgA', U(4), 'acl-plant-v4'); }, /acl-plant-v4: publish exception/],
    ['V5 publish default ALLOW on a grammar username\'s ACL', 'orgA', async () => { await mkAcl('orgA', 'acl-plant-v5', { publishDefault: 'allow' }); await mkUser('orgA', U(5), 'acl-plant-v5'); }, /acl-plant-v5: publishTopicDefaultAction=allow/],
    ['V6 a publish exception on the ACL behind orgB\'s `default` client username (the one orgB browsers actually land on)', 'orgB', async () => { const r = await pubEx('orgB', 'acl-orgB-stream-subst', 'sm/v1/orgB/>'); expect(r.ok, JSON.stringify(r.error)).toBe(true); created.push(`/msgVpns/orgB/aclProfiles/acl-orgB-stream-subst/publishTopicExceptions/smf,${enc('sm/v1/orgB/>')}`); }, /orgB\/acl-orgB-stream-subst: publish exception sm\/v1\/orgB\/>/],
    ['V7 a widened subscribe exception added to a COMPOSED grant\'s ACL', 'orgA', async () => { const r = await subEx('orgA', 'acl-br-000000000000c101', 'sm/v1/orgA/>'); expect(r.ok, JSON.stringify(r.error)).toBe(true); created.push(`/msgVpns/orgA/aclProfiles/acl-br-000000000000c101/subscribeTopicExceptions/smf,${enc('sm/v1/orgA/>')}`); }, /acl-br-000000000000c101: subscribe exception sm\/v1\/orgA\/> is outside/],
    ['V8 a subscribe exception on orgB\'s `default` ACL reaching sm/v1/>', 'orgB', async () => { const r = await subEx('orgB', 'acl-orgB-stream-subst', 'sm/v1/>'); expect(r.ok, JSON.stringify(r.error)).toBe(true); created.push(`/msgVpns/orgB/aclProfiles/acl-orgB-stream-subst/subscribeTopicExceptions/smf,${enc('sm/v1/>')}`); }, /acl-orgB-stream-subst: subscribe exception sm\/v1\/> is outside/],
    ['V9 a non-grammar username on the browser client profile', 'orgA', async () => { await mkAcl('orgA', 'acl-plant-v9'); await mkUser('orgA', 'plant-not-grammar', 'acl-plant-v9'); }, /plant-not-grammar: not browser-facing/],
    ['V10 a grammar username on the permissive sm-internal client profile', 'orgA', async () => { await mkAcl('orgA', 'acl-plant-v10'); await mkUser('orgA', U(10), 'acl-plant-v10', 'sm-internal'); }, /client profile sm-internal, not sm-browser/],
    ['V11 the stock `default` ACL PROFILE given a subscribe exception', 'orgB', async () => { const r = await subEx('orgB', 'default', 'sm/v1/>'); expect(r.ok, JSON.stringify(r.error)).toBe(true); created.push(`/msgVpns/orgB/aclProfiles/default/subscribeTopicExceptions/smf,${enc('sm/v1/>')}`); }, /orgB\/default ACL profile: 1 subscribeTopicExceptions/],
    // ---- round 4 MINOR 2: the checks the old Python copy in provision.sh did not make (or made more weakly) ----
    ['V12 (round 4) orgB\'s `default` client username re-pointed at a DIFFERENT, harmless, closed ACL (the pin the install check must hold)', 'orgB', async () => {
      await mkAcl('orgB', 'acl-plant-v12');
      const r = await sempWrite('PATCH', '/msgVpns/orgB/clientUsernames/default', { aclProfileName: 'acl-plant-v12' });
      expect(r.ok, JSON.stringify(r.error)).toBe(true);
      created.push(async () => { const u = await sempWrite('PATCH', '/msgVpns/orgB/clientUsernames/default', { aclProfileName: 'acl-orgB-stream-subst' }); expect(u.ok, JSON.stringify(u.error)).toBe(true); });
    }, /orgB\/default: client username uses ACL acl-plant-v12, expected the pinned acl-orgB-stream-subst/],
    ['V13 (round 4) orgB\'s OAuth profile audience relaxed to orgA\'s', 'orgB', async () => {
      const path = '/msgVpns/orgB/authenticationOauthProfiles/sm_scope_oauth';
      const set = async (aud) => { let r = await sempWrite('PATCH', path, { resourceServerRequiredAudience: aud }); if (!r.ok) { await sempWrite('PATCH', path, { enabled: false }); r = await sempWrite('PATCH', path, { resourceServerRequiredAudience: aud }); await sempWrite('PATCH', path, { enabled: true }); } expect(r.ok, JSON.stringify(r.error)).toBe(true); };
      await set('sm-mesh-acl-orgA');
      created.push(async () => { await set('sm-mesh-acl-orgB'); });
    }, /orgB\/OAuth profile sm_scope_oauth: required audience is "sm-mesh-acl-orgA", expected sm-mesh-acl-orgB/],
    ['V14 (round 4) an otherwise allowed subscribe exception written in the wrong SYNTAX (mqtt, not smf)', 'orgA', async () => {
      await mkAcl('orgA', 'acl-plant-v14');
      const t = 'sm/v1/orgA/su1/dev/notify/>';
      const r = await sempWrite('POST', '/msgVpns/orgA/aclProfiles/acl-plant-v14/subscribeTopicExceptions', { msgVpnName: 'orgA', aclProfileName: 'acl-plant-v14', subscribeTopicExceptionSyntax: 'mqtt', subscribeTopicException: t });
      expect(r.ok, JSON.stringify(r.error)).toBe(true);
      created.push(`/msgVpns/orgA/aclProfiles/acl-plant-v14/subscribeTopicExceptions/mqtt,${enc(t)}`);
      await mkUser('orgA', U(14), 'acl-plant-v14');
    }, /acl-plant-v14: subscribe exception sm\/v1\/orgA\/su1\/dev\/notify\/> has syntax mqtt/],
  ];
  for (const [label, vpn, plant, expected] of PLANTS) {
    it(`${label}: reported; clean after removal`, async () => {
      const mark = created.length;
      await plant();
      const v = await browserAclViolations(vpn);
      record({ leg: `12A-plant`, attempted: `plant: ${label}`, expected: 'the ACL check reports it', observed: { violations: v }, pass: v.some((x) => expected.test(x)) });
      expect(v.some((x) => expected.test(x)), `expected ${expected} in ${JSON.stringify(v)}`).toBe(true);
      // the install-time entry point (what provision.sh runs) must report the SAME violation and exit non-zero
      const c1 = await cli();
      record({ leg: '12A-plant-cli', attempted: `same plant, run through tools/acl-check.mjs (the entry point provision.sh runs): ${label}`, expected: 'exit 1 and the violation printed', observed: { exitCode: c1.code, reported: c1.out.split('\n').filter((l) => expected.test(l)) }, pass: c1.code === 1 && expected.test(c1.out) });
      expect(c1.code, c1.out).toBe(1);
      expect(expected.test(c1.out), c1.out).toBe(true);
      // remove exactly what this plant created (LIFO), then the check is clean again
      while (created.length > mark) await del(created.pop());
      expect(await browserAclViolations(vpn), 'clean after removal').toEqual([]);
      const c2 = await cli();
      expect(c2.code, c2.out).toBe(0);
    }, 120000);
  }
});

describe('round 3 MINOR 5 — SEMP paging is followed', () => {
  it('paging with page size 2 returns exactly the objects an unpaged read returns, over several pages', async () => {
    const small = await sempAll('config', '/msgVpns/orgA/aclProfiles', { pageSize: 2 });
    const big = await sempAll('config', '/msgVpns/orgA/aclProfiles', { pageSize: 500 });
    const names = (x) => x.data.map((a) => a.aclProfileName).sort();
    record({ leg: '12A-paging', attempted: 'read orgA ACL profiles with page size 2 and 500', expected: 'same set; more than one page at size 2', observed: { count: big.data.length, pagesAtSize2: small.pages, pagesAtSize500: big.pages }, pass: small.pages > 1 && JSON.stringify(names(small)) === JSON.stringify(names(big)) });
    expect(small.pages).toBeGreaterThan(1);
    expect(names(small)).toEqual(names(big));
    expect(big.pages).toBe(1);
    // and the default page (no count) is SMALLER than the fixture would need for a large VPN — the reason a fixed read is not enough
    expect((await sempConfig('/msgVpns/orgA/aclProfiles')).data.length).toBeLessThanOrEqual(big.data.length);
  }, 30000);

  it('the checks work with a tiny page size (every reader follows the cursor)', async () => {
    expect(await browserAclViolations('orgA', { pageSize: 2 })).toEqual([]);
    expect(await browserAclViolations('orgB', { pageSize: 1 })).toEqual([]);
  }, 60000);
});


describe('round 4 MINOR 2 — provision.sh itself fails on a planted violation', () => {
  it('a planted violation (V1: a grammar username whose ACL allows subscribing sm/v1/>) makes `./provision.sh` exit 1 with the identity-binding assertion FAILED; with the plant removed the assertion section reports clean', async () => {
    const mark = created.length;
    await mkAcl('orgA', 'acl-plant-e2e'); await subEx('orgA', 'acl-plant-e2e', 'sm/v1/>'); await mkUser('orgA', 'br-0000000f0000e2e0', 'acl-plant-e2e');
    const bad = await run('./provision.sh', [], { timeout: 240000 });
    while (created.length > mark) await del(created.pop());
    await sleep(300);
    const good = await run('node', ['tools/acl-check.mjs', 'orgA', 'orgB']);
    record({ leg: '12A-plant-provision', attempted: 'plant V1 (br-0000000f0000e2e0 -> ACL with subscribe exception sm/v1/>), run ./provision.sh end to end; remove; run the assertion again', expected: 'provision.sh exits 1 and prints the violation and "IDENTITY-BINDING ASSERTION FAILED"; clean afterwards', observed: { provisionExitCode: bad.code, printedViolation: bad.out.split('\n').filter((l) => /acl-plant-e2e/.test(l)), printedFailure: /IDENTITY-BINDING ASSERTION FAILED/.test(bad.out), afterRemoval: { exitCode: good.code } }, pass: bad.code === 1 && /acl-plant-e2e: subscribe exception sm\/v1\/> is outside/.test(bad.out) && /IDENTITY-BINDING ASSERTION FAILED/.test(bad.out) && good.code === 0 });
    expect(bad.code, bad.out.slice(-2500)).toBe(1);
    expect(bad.out).toMatch(/acl-plant-e2e: subscribe exception sm\/v1\/> is outside/);
    expect(bad.out).toMatch(/IDENTITY-BINDING ASSERTION FAILED/);
    expect(good.code, good.out).toBe(0);
  }, 300000);
});
