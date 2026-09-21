#!/usr/bin/env node
/**
 * `npm run verify:reflow` (task 110, `FE-D29`). Two checks:
 *
 * 1. **SC 1.3.4 (orientation).** A screen must never key its layout off
 *    device orientation — width alone drives reflow (`e2e/reflow.spec.ts`'s
 *    own "orientation" describe block proves the *rendered* side of that;
 *    this proves the *source* never reaches for the mechanism that would
 *    break it: `screen.orientation.lock(…)`, or a CSS
 *    `@media (orientation: …)` query gating what renders). Static and
 *    instant — no browser needed for a guarantee about what the source
 *    does NOT contain.
 * 2. **The Playwright reflow/axe/orientation suite** — `e2e/reflow.spec.ts`
 *    and its self-test (`e2e/reflow.selftest.spec.ts`, proving the checker
 *    itself rejects an undeclared scroller before trusting it against the
 *    real app). Both run on the `fixture` Playwright project explicitly —
 *    not `--project=fixture` alone, which would also pull in
 *    `designer.spec.ts`, a different task's journey test with its own
 *    place in `npm run e2e`.
 */
import { spawnSync } from 'node:child_process';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const srcRoot = join(frontendRoot, 'src');

function collectSourceFiles(dir) {
  const files = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    const stats = statSync(path);
    if (stats.isDirectory()) {
      files.push(...collectSourceFiles(path));
    } else if (/\.(ts|tsx|css)$/.test(entry)) {
      files.push(path);
    }
  }
  return files;
}

const ORIENTATION_LOCK_PATTERN = /\.orientation\s*\.\s*lock\s*\(/;
const ORIENTATION_MEDIA_PATTERN = /@media[^{]*orientation\s*:/;

const offenders = [];
for (const file of collectSourceFiles(srcRoot)) {
  if (file.endsWith('.test.ts') || file.endsWith('.test.tsx')) {
    continue;
  }
  const text = readFileSync(file, 'utf8');
  if (ORIENTATION_LOCK_PATTERN.test(text)) {
    offenders.push(`${file}: calls screen.orientation.lock() (SC 1.3.4 forbids orientation lock)`);
  }
  if (ORIENTATION_MEDIA_PATTERN.test(text)) {
    offenders.push(`${file}: an @media (orientation: …) query — layout must key off width only`);
  }
}

if (offenders.length > 0) {
  console.error('verify-reflow: orientation guard failed (SC 1.3.4):');
  for (const offender of offenders) {
    console.error(`  - ${offender}`);
  }
  process.exit(1);
}
console.log('verify-reflow: no orientation lock / orientation media query in src/ — SC 1.3.4 clear');

const playwrightBin = join(frontendRoot, 'node_modules/.bin/playwright');
const result = spawnSync(
  playwrightBin,
  ['test', 'e2e/reflow.spec.ts', 'e2e/reflow.selftest.spec.ts', '--project=fixture'],
  { cwd: frontendRoot, stdio: 'inherit' },
);

if (result.error) {
  console.error(result.error);
  process.exit(1);
}

process.exit(result.status ?? 1);
