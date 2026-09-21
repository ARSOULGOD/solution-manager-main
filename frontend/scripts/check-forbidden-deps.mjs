#!/usr/bin/env node
/**
 * Fails CI if a MUI X Pro/Premium package enters package.json or the
 * lockfile (FE-D11, design §25). Import-time enforcement is the matching
 * no-restricted-imports rule; this catches a dependency that is installed
 * but never imported.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const forbidden = Object.freeze([
  '@mui/x-data-grid-pro',
  '@mui/x-data-grid-premium',
  '@mui/x-charts-pro',
]);

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

/** @param {string} text @param {string} pkg */
function mentionsPackage(text, pkg) {
  // Quoted name (package.json keys, lockfile "name") or a node_modules path.
  // Deliberately does not treat Community `@mui/x-data-grid` as a hit.
  return text.includes(`"${pkg}"`) || text.includes(`node_modules/${pkg}`);
}

const files = ['package.json', 'package-lock.json'];
const hits = [];

for (const file of files) {
  const text = readFileSync(join(frontendRoot, file), 'utf8');
  for (const pkg of forbidden) {
    if (mentionsPackage(text, pkg)) {
      hits.push(`${file}: ${pkg}`);
    }
  }
}

if (hits.length > 0) {
  console.error('Forbidden MUI X Pro/Premium dependency (FE-D11):');
  for (const hit of hits) {
    console.error(`  ${hit}`);
  }
  process.exit(1);
}

console.log('check:forbidden-deps: no Pro/Premium packages in package.json or lockfile');
