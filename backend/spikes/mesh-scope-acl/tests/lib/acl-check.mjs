// The browser-facing identity / ACL install check — ONE implementation. Throwaway spike code.
//
// SDK-free on purpose (only `fetch`), so `provision.sh` can run it before `npm install` has linked
// the Web App SDK (`node tools/acl-check.mjs`), and the test suite imports the very same function
// (tests/lib/common.mjs re-exports it). Round 4 MINOR 2: until now provision.sh ran its OWN Python
// re-implementation that never checked the stock `default` ACL profile or the exception syntax and
// was never shown able to fail; the doc nevertheless described "one check used by 12A-all,
// 16-config and provision.sh". Now that is true, and tests/11 plants a violation of every kind
// against this function AND against the provision.sh entry point.
//
// What is checked, for every Message VPN passed in (SEMP config, following paging):
//   * every client username a ticket can land on (a name matching the reserved browser grammar,
//     or `default`) uses client profile `sm-browser`; a NON-browser-facing username must not;
//   * `default` is enabled and pinned to the expected stream-substitution ACL `acl-<vpn>-stream-subst`;
//   * the ACL behind each: publish default disallow and ZERO publish exceptions; subscribe default
//     disallow; every subscribe exception has syntax `smf` and is one of exactly three shapes,
//     all inside the VPN's own `sm/v1/<vpn>/` prefix (scoped notify literal | stream
//     substitution | the exact org-terminating catalog topic) — never `sm/v1/>`, never another
//     organization's prefix, never an organization-wide wildcard;
//   * the stock `default` ACL PROFILE (not the client username) is closed and has no publish /
//     subscribe / connect exceptions;
//   * the VPN's OAuth profile `sm_scope_oauth` requires audience `sm-mesh-acl-<vpn>`.

const SEMP = 'http://127.0.0.1:8081/SEMP/v2/config';
const AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');

export const GRAMMAR = /^br-[0-9a-f]{16}$/;

async function pagedAll(path, pageSize) {
  const sep = path.includes('?') ? '&' : '?';
  let url = `${SEMP}${path}${sep}count=${pageSize}`;
  const data = [];
  for (let pages = 0; url; pages += 1) {
    if (pages > 500) throw new Error('acl-check: runaway paging');
    const body = await (await fetch(url, { headers: { Authorization: AUTH } })).json().catch(() => ({}));
    if (body?.meta?.responseCode !== 200) throw new Error(`acl-check: SEMP ${path}: ${JSON.stringify(body?.meta?.error ?? body?.meta)}`);
    data.push(...(body.data ?? []));
    url = body.meta?.paging?.nextPageUri ?? null;
  }
  return data;
}
async function one(path) {
  const r = await fetch(`${SEMP}${path}`, { headers: { Authorization: AUTH } });
  const body = await r.json().catch(() => ({}));
  return { status: r.status, data: body.data };
}

/** {violations, facing, acls}: `facing` = browser-facing usernames examined, `acls` = distinct ACL profiles examined. */
export async function browserAclReport(vpn, { pageSize = 100 } = {}) {
  const v = [];
  const esc = (x) => x.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const shapes = [
    [new RegExp(`^sm/v1/${esc(vpn)}/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/notify/>$`), 'scoped notify literal'],
    [new RegExp(`^sm/v1/${esc(vpn)}/stream/\\$client-username/>$`), 'stream substitution'],
    [new RegExp(`^sm/v1/${esc(vpn)}/notify/catalog/package$`), 'exact org-terminating catalog topic'],
  ];
  const users = await pagedAll(`/msgVpns/${vpn}/clientUsernames`, pageSize);
  const seen = new Set();
  let facing = 0;
  for (const u of users) {
    const name = u.clientUsername;
    const isFacing = GRAMMAR.test(name) || name === 'default';
    if (!isFacing) {
      if (u.clientProfileName === 'sm-browser') v.push(`${vpn}/${name}: not browser-facing (no grammar match) but uses client profile sm-browser`);
      continue;
    }
    facing += 1;
    if (u.clientProfileName !== 'sm-browser') v.push(`${vpn}/${name}: browser-facing username uses client profile ${u.clientProfileName}, not sm-browser`);
    if (name === 'default') {
      if (u.enabled !== true) v.push(`${vpn}/default: not enabled (the pinned stream fallthrough is expected)`);
      if (u.aclProfileName !== `acl-${vpn}-stream-subst`) v.push(`${vpn}/default: client username uses ACL ${u.aclProfileName}, expected the pinned acl-${vpn}-stream-subst`);
    }
    const acl = u.aclProfileName;
    if (seen.has(acl)) continue;
    seen.add(acl);
    const a = await one(`/msgVpns/${vpn}/aclProfiles/${encodeURIComponent(acl)}`);
    if (a.status !== 200) { v.push(`${vpn}/${name}: ACL profile ${acl} unreadable (${a.status})`); continue; }
    if (a.data.publishTopicDefaultAction !== 'disallow') v.push(`${vpn}/${acl}: publishTopicDefaultAction=${a.data.publishTopicDefaultAction}`);
    if (a.data.subscribeTopicDefaultAction !== 'disallow') v.push(`${vpn}/${acl}: subscribeTopicDefaultAction=${a.data.subscribeTopicDefaultAction}`);
    for (const e of await pagedAll(`/msgVpns/${vpn}/aclProfiles/${encodeURIComponent(acl)}/publishTopicExceptions`, pageSize)) v.push(`${vpn}/${acl}: publish exception ${e.publishTopicException}`);
    for (const e of await pagedAll(`/msgVpns/${vpn}/aclProfiles/${encodeURIComponent(acl)}/subscribeTopicExceptions`, pageSize)) {
      const t = e.subscribeTopicException;
      if (e.subscribeTopicExceptionSyntax !== 'smf') v.push(`${vpn}/${acl}: subscribe exception ${t} has syntax ${e.subscribeTopicExceptionSyntax}`);
      if (!shapes.some(([re]) => re.test(t))) v.push(`${vpn}/${acl}: subscribe exception ${t} is outside the allowed shapes (scoped notify literal | stream substitution | exact catalog topic) inside sm/v1/${vpn}/`);
    }
  }
  // the stock `default` ACL PROFILE (not the client username): pinned closed and empty
  const d = await one(`/msgVpns/${vpn}/aclProfiles/default`);
  for (const k of ['clientConnectDefaultAction', 'publishTopicDefaultAction', 'subscribeTopicDefaultAction']) if (d.data?.[k] !== 'disallow') v.push(`${vpn}/default ACL profile: ${k}=${d.data?.[k]}`);
  for (const kind of ['publishTopicExceptions', 'subscribeTopicExceptions', 'clientConnectExceptions']) {
    const ex = await pagedAll(`/msgVpns/${vpn}/aclProfiles/default/${kind}`, pageSize);
    if (ex.length) v.push(`${vpn}/default ACL profile: ${ex.length} ${kind}`);
  }
  // per-organization audience
  const oauth = await one(`/msgVpns/${vpn}/authenticationOauthProfiles/sm_scope_oauth`);
  const aud = oauth.data?.resourceServerRequiredAudience;
  if (aud !== `sm-mesh-acl-${vpn}`) v.push(`${vpn}/OAuth profile sm_scope_oauth: required audience is ${JSON.stringify(aud)}, expected sm-mesh-acl-${vpn}`);
  return { violations: v, facing, acls: seen.size, audience: aud };
}

/** Violation strings only (empty = clean). */
export const browserAclViolations = async (vpn, opts) => (await browserAclReport(vpn, opts)).violations;
