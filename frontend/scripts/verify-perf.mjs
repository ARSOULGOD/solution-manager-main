#!/usr/bin/env node
/**
 * `npm run verify:perf` (FE-103) — the performance verification suite
 * (design §17, §22, `dynamic-metrics` §13).
 *
 * FE-103 is *"tests, not assertions"*: unlike `verify:production` or
 * `verify:csp`, there is no artefact to inspect, only behaviour to prove.
 * What this wrapper adds over `npm test` is a **named** set — the six
 * guarantees the task lists, gathered in one command so that "is the
 * performance contract still held?" has an answer you can run, rather than
 * six test files someone has to know to look for.
 *
 * Four of the six were already proven by earlier tasks and are named here
 * rather than reimplemented — duplicating them would create two places to
 * update and no extra confidence:
 *
 *   1. off-screen widgets issue no request   → `WidgetFrame.test.tsx`      (086)
 *   2. 8 widgets / 4 metrics → 4 requests    → `dashboardPerformance.test.tsx` (this task)
 *   3. refresh floored at the served bucket  → `refreshPolicy.test.ts`     (085)
 *   3b. refresh paused while hidden          → `dashboardPerformance.test.tsx` (this task)
 *   4. render budgets                        → `renderBudgets.test.tsx`    (this task)
 *   5. the legend virtualises, never truncates → `ChartRenderer.test.tsx`  (075)
 *   6. free text debounced, enums not        → `FilterBar.test.tsx`        (020)
 *
 * A file named here that stops existing fails the run rather than silently
 * shrinking the suite — the failure mode a bare `vitest <globs>` has, where
 * a renamed file just stops being checked and nothing goes red.
 */
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const vitestBin = join(frontendRoot, 'node_modules/vitest/vitest.mjs');

/** @type {ReadonlyArray<{ file: string, proves: string }>} */
const TARGETS = [
  {
    file: 'src/features/monitoring/dashboard/WidgetFrame.test.tsx',
    proves: 'off-screen widgets issue no request (visibility gating)',
  },
  {
    file: 'src/features/monitoring/dashboard/dashboardPerformance.test.tsx',
    proves: '8 widgets over 4 metrics issue 4 requests (dedup); refresh pauses while hidden',
  },
  {
    file: 'src/features/monitoring/dashboard/refreshPolicy.test.ts',
    proves: 'refresh is floored at the served bucket, and off stays off',
  },
  {
    file: 'src/test/performance/renderBudgets.test.tsx',
    proves: 'the 12-row grid and the ~100-field projection stay inside their recorded budgets',
  },
  {
    file: 'src/features/monitoring/chart/ChartRenderer.test.tsx',
    proves: 'the legend scrolls and never truncates membership',
  },
  {
    file: 'src/shared/ui/FilterBar/FilterBar.test.tsx',
    proves: 'free-text inputs are debounced and enum inputs are not',
  },
];

const missing = TARGETS.filter(({ file }) => !existsSync(join(frontendRoot, file)));
if (missing.length > 0) {
  console.error('verify-perf: a named target no longer exists — the suite has silently shrunk:');
  for (const { file, proves } of missing) {
    console.error(`  - ${file}\n      was proving: ${proves}`);
  }
  console.error(
    '\nIf the file moved, update this list. If the guarantee moved, say where.\n' +
      'Do not delete the entry to go green.',
  );
  process.exit(1);
}

console.log('verify-perf: the performance contract (design §17, §22)');
for (const { file, proves } of TARGETS) {
  console.log(`  ${file}\n      ${proves}`);
}
console.log('');

const result = spawnSync(
  process.execPath,
  [
    vitestBin,
    'run',
    '--project',
    'unit',
    ...TARGETS.map(({ file }) => file),
    ...process.argv.slice(2),
  ],
  { cwd: frontendRoot, stdio: 'inherit' },
);

if (result.error) {
  console.error(result.error);
  process.exit(1);
}

if (result.status !== 0) {
  console.error(
    '\nverify-perf: FAIL (FE-103). One of the performance guarantees no longer holds.\n' +
      'If a render budget is the failure: the ceilings are ~4x (grid) and ~15x\n' +
      '(projection) over a recorded baseline, so a breach is an order-of-magnitude\n' +
      'regression, not noise. Find what grew before raising the number, and record\n' +
      'the reason in renderBudgets.test.tsx alongside the existing baseline table.',
  );
}

process.exit(result.status ?? 1);
