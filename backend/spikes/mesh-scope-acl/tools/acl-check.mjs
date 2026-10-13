// The install-time identity / ACL assertion `provision.sh` runs — the SAME function the test suite
// asserts and plants violations against (tests/lib/acl-check.mjs). Exit 0 = clean, 1 = violations.
// Usage: node tools/acl-check.mjs [vpn ...]   (default: orgA orgB)
import { browserAclReport } from '../tests/lib/acl-check.mjs';

const vpns = process.argv.slice(2).length ? process.argv.slice(2) : ['orgA', 'orgB'];
let bad = 0;
for (const vpn of vpns) {
  const r = await browserAclReport(vpn);
  if (r.violations.length === 0) {
    console.log(`  ok    ${vpn}: OAuth audience ${r.audience}; ${r.facing} browser-facing username(s) (grammar or default), ${r.acls} distinct ACL profile(s): sm-browser, publish disallow + no exceptions, subscribe exceptions smf and inside sm/v1/${vpn}/; stock default ACL profile closed and empty`);
  } else {
    for (const m of r.violations) console.log(`  FAIL  ${m}`);
    bad += r.violations.length;
  }
}
console.log(bad === 0 ? '  --    identity/ACL check clean' : `  --    identity/ACL check: ${bad} violation(s)`);
process.exit(bad === 0 ? 0 : 1);
