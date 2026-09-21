#!/usr/bin/env node
/**
 * `npm run test:a11y` (task 098). A wrapper, not a raw `vitest` CLI line
 * in `package.json`, because the plan's own verification protocol
 * (`implementation.md` §6.0.8) uses this two ways: `npm run test:a11y --
 * <path>` (per-task, scoped — same shape as `npm test -- <path>`) and
 * `npm run test:a11y -- --all` (task 098's own full sweep). Both need to
 * compose correctly with the `a11y` Vitest project (`vitest.config.ts`,
 * `include: ['src/**\/*.a11y.test.tsx']`):
 *
 * - A path/pattern arg on the CLI narrows an *already-selected* project's
 *   file set (AND) — verified empirically against this repo
 *   (`vitest run --project a11y src/app/dev` only matched a11y files
 *   under that one directory, never anything outside it).
 * - `--all` is not a real Vitest flag — passing it straight through
 *   fails with `CACError: Unknown option --all`. It means the same thing
 *   as no extra args at all (the `a11y` project's `include` already IS
 *   "every a11y test file"), so it's dropped here rather than forwarded.
 */
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const vitestBin = join(frontendRoot, 'node_modules/vitest/vitest.mjs');

const passthrough = process.argv.slice(2).filter((arg) => arg !== '--all');

const result = spawnSync(process.execPath, [vitestBin, 'run', '--project', 'a11y', ...passthrough], {
  cwd: frontendRoot,
  stdio: 'inherit',
});

if (result.error) {
  console.error(result.error);
  process.exit(1);
}

process.exit(result.status ?? 1);
