#!/usr/bin/env node
/**
 * Proves each lint-fixture still fails for the rule it exists to violate.
 * `npm run lint` ignores these files (they are supposed to be dirty).
 * `npm run lint:fixtures` must exit non-zero; this script is the green
 * CI check that the failures are the intended ones, not a random parse error.
 */
import { spawnSync } from 'node:child_process';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const fixturesRoot = join(frontendRoot, 'src/test/lint-fixtures');

/** @type {ReadonlyArray<{ file: string, rule: string }>} */
const expected = [
  { file: 'feature/inventory/deep-import.ts', rule: 'boundaries/dependencies' },
  { file: 'shared/imports-feature.ts', rule: 'boundaries/dependencies' },
  { file: 'app/imports-echarts.ts', rule: 'no-restricted-imports' },
  { file: 'shared-ui/calls-api.ts', rule: 'no-restricted-imports' },
  { file: 'app/imports-pro.ts', rule: 'no-restricted-imports' },
  { file: 'app/raw-colour.ts', rule: 'no-restricted-syntax' },
  { file: 'app/raw-media.ts', rule: 'no-restricted-syntax' },
  { file: 'app/vite-plane-host.ts', rule: 'no-restricted-syntax' },
  { file: 'app/demo-states.ts', rule: 'no-restricted-syntax' },
  { file: 'app/logs-secret.ts', rule: 'no-restricted-syntax' },
  { file: 'app/raw-console.ts', rule: 'no-restricted-syntax' },
  { file: 'observability/log-transport.ts', rule: 'no-restricted-syntax' },
  { file: 'contracts/imports-react.ts', rule: 'no-restricted-imports' },
];

const result = spawnSync(
  process.execPath,
  [
    join(frontendRoot, 'node_modules/eslint/bin/eslint.js'),
    'src/test/lint-fixtures',
    '--no-ignore',
    '--max-warnings=0',
    '-f',
    'json',
  ],
  { cwd: frontendRoot, encoding: 'utf8' },
);

if (result.error) {
  console.error(result.error);
  process.exit(1);
}

if (result.status === 0) {
  console.error('lint-fixtures passed — they must fail. A passing run means a guardrail is dead.');
  process.exit(1);
}

/** @type {Array<{ filePath: string, messages: Array<{ ruleId: string | null, message: string }> }>} */
let reports;
try {
  reports = JSON.parse(result.stdout);
} catch {
  console.error('eslint JSON parse failed. stderr:\n', result.stderr);
  console.error('stdout:\n', result.stdout);
  process.exit(1);
}

const byRel = new Map(
  reports.map((report) => [relative(fixturesRoot, report.filePath).split('\\').join('/'), report]),
);

let failed = false;
for (const { file, rule } of expected) {
  const report = byRel.get(file);
  if (!report) {
    console.error(`missing eslint report for ${file}`);
    failed = true;
    continue;
  }
  const ids = new Set(report.messages.map((m) => m.ruleId));
  if (!ids.has(rule)) {
    console.error(`${file}: expected ${rule}, got ${[...ids].join(', ') || '(no messages)'}`);
    failed = true;
  }
}

if (failed) {
  process.exit(1);
}

console.log(`lint-fixtures: ${String(expected.length)} files failed on the intended rule`);
