#!/usr/bin/env node
/**
 * `npm run verify:palette` (FE-097) — the palette and contrast gate.
 *
 * Design §18 recorded contrast as something that "cannot be signed off
 * until open item 19 tokens exist". The tokens exist, so this replaces the
 * sign-off with a check: **a failure here is a build failure, not a
 * warning.** It is a required job in `.github/workflows/frontend.yml`.
 *
 * It runs two files, and both matter:
 *
 *   · `src/shared/theme/palette.contrast.test.ts` — the assertions. Every
 *     chart series colour, text colour, tone chip and focus band, in
 *     **both** themes, plus colour-blind separability and the "no state is
 *     colour-only" cross-check against the vocabulary module.
 *   · `src/shared/theme/contrast.test.ts` — the instrument. A
 *     `contrastRatio` that always returned 21 would make the gate above
 *     pass forever while proving nothing, so the maths is checked against
 *     values with independently known answers (black-on-white is exactly
 *     21:1; `#767676` on white is the canonical 4.5:1 boundary grey).
 *
 * A wrapper rather than a raw `vitest` line in `package.json` for the same
 * reason `test-a11y.mjs` is one: the two paths belong together and naming
 * them in one place keeps the CI job and the local command identical. The
 * paths narrow the already-selected `unit` project (AND, not OR) — see
 * `scripts/test-a11y.mjs` for where that behaviour was pinned down.
 *
 * Vitest is spawned rather than imported so the exit code is the process
 * exit code with nothing in between to swallow it.
 */
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const vitestBin = join(frontendRoot, 'node_modules/vitest/vitest.mjs');

const TARGETS = ['src/shared/theme/palette.contrast.test.ts', 'src/shared/theme/contrast.test.ts'];

const result = spawnSync(
  process.execPath,
  [vitestBin, 'run', '--project', 'unit', ...TARGETS, ...process.argv.slice(2)],
  { cwd: frontendRoot, stdio: 'inherit' },
);

if (result.error) {
  console.error(result.error);
  process.exit(1);
}

if (result.status !== 0) {
  console.error(
    '\nPalette gate failed (FE-097). A contrast or colour-blindness assertion\n' +
      'is no longer satisfied. Do not raise the threshold to make this pass —\n' +
      'the numbers are WCAG 2.2 AA (4.5:1 text, 3:1 non-text) and the palette\n' +
      'is expected to meet them in both themes. Fix the colour, and record the\n' +
      'change as a new FE-D decision if it moves paint (I3).',
  );
}

process.exit(result.status ?? 1);
