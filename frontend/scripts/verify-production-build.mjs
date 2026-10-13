#!/usr/bin/env node
/**
 * `npm run verify:production` (FE-101) — the production-build assertions.
 *
 * Design §21 lists what a shipped console must not contain and one thing it
 * must: no auth mock, no dev gallery route, no MSW, no fixture dataset, no
 * secret, no plane hostname, and a `config.json` an operator can replace
 * without a rebuild. Each of those is a build failure here, never a warning
 * — every one of them is a control that other decisions (`FE-D8`, `FE-D14`,
 * `FE-D19`, `FE-D24`, `I6`) assume is already true.
 *
 * Three parts, run in this order:
 *
 *   1. **The instrument, first.** `production-assertions.selftest.mjs`
 *      drives all seven assertions with builds that violate them — six
 *      described inputs and one REAL Vite production build of an entry that
 *      imports the mock, the gallery, MSW and a fixture dataset on purpose.
 *      An assertion that has never been seen failing proves nothing, and
 *      this suite is what makes *"each demonstrably fails when violated"*
 *      (the task's second acceptance bullet) a thing you can run rather
 *      than a thing you claim. Same argument as `verify:palette` running
 *      the contrast maths' own tests before trusting the contrast gate.
 *   2. **The real build, written out.** `vite build` with the same
 *      `vite.config.ts` the shipped artefact is built with, into a throwaway
 *      output directory — then a walk of what it wrote.
 *
 *      Written, not `write: false`, because an in-memory bundle is not the
 *      artefact. It omits everything `publicDir` copies in and everything a
 *      `closeBundle` hook adds or removes — which between them cover two of
 *      the seven assertions outright: MSW's service-worker script never
 *      enters the module graph (it shipped for months precisely because
 *      bundle analysis cannot see it), and `config.json` does not exist
 *      until the copy happens. A throwaway directory rather than `dist/` so
 *      the gate never disturbs the build the `preview` server is serving.
 *   3. **The runtime proof.** `e2e/config-replaceable.spec.ts`: a built
 *      `dist/` is copied, its `config.json` is edited, the copy is served,
 *      and the console is observed calling the new prefix. Static analysis
 *      can show a value was not compiled in; only that shows the artefact
 *      genuinely retargets.
 */
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { build } from 'vite';

import { ASSERTIONS, assertProductionBuild, describeBundle } from './production-assertions.mjs';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

/** Extensions we do not try to read as text; a font is not a place a grep can help. */
const BINARY = /\.(?:woff2?|ttf|otf|eot|png|jpe?g|gif|ico|webp|avif|pdf|zip|gz|mp4|webm)$/i;

function walk(dir) {
  const files = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      files.push(...walk(path));
    } else {
      files.push(path);
    }
  }
  return files;
}

/** Every file the build wrote — chunks, assets, and everything publicDir copied in. */
function readArtefact(outDir) {
  let entries;
  try {
    entries = walk(outDir);
  } catch {
    return [];
  }
  return entries.map((path) => {
    const rel = relative(outDir, path).split(sep).join('/');
    if (BINARY.test(rel)) {
      return { path: rel, text: null };
    }
    const text = readFileSync(path, 'utf8');
    // A decode full of U+FFFD replacement characters is binary under another
    // extension, not text a pattern could say anything useful about.
    return { path: rel, text: text.includes('�') ? null : text };
  });
}

// ── 1 · prove the instrument ─────────────────────────────────────────────
const selftest = spawnSync(
  process.execPath,
  [join(frontendRoot, 'scripts/production-assertions.selftest.mjs')],
  { cwd: frontendRoot, stdio: 'inherit' },
);
if (selftest.error) {
  console.error(selftest.error);
  process.exit(1);
}
if (selftest.status !== 0) {
  console.error(
    '\nverify-production: the assertions could not be shown failing. Until one\n' +
      'fails on a violation it is not a check — fix the assertion, never the\n' +
      'self-test, and never skip this step to get a green run.',
  );
  process.exit(1);
}

// ── 2 · the real build ───────────────────────────────────────────────────
const outDir = mkdtempSync(join(tmpdir(), 'sm-verify-production-'));
let subject;
try {
  const result = await build({
    logLevel: 'silent',
    build: { outDir, emptyOutDir: true },
  });
  const output = (Array.isArray(result) ? result[0] : result).output;
  subject = describeBundle(output, readArtefact(outDir));
} finally {
  rmSync(outDir, { recursive: true, force: true });
}

console.log('\nverify-production: subject');
console.log(`  chunks         ${String(subject.chunks.length).padStart(3)}`);
console.log(`  assets         ${String(subject.assets.length).padStart(3)}`);
console.log(
  `  artefact files ${String(subject.artefactFiles.length).padStart(3)} (the written build output)`,
);

const failures = assertProductionBuild(subject);
const byAssertion = new Map(ASSERTIONS.map((assertion) => [assertion.id, []]));
for (const failure of failures) {
  byAssertion.get(failure.id)?.push(failure.message);
}

console.log('\nverify-production: assertions');
for (const assertion of ASSERTIONS) {
  const found = byAssertion.get(assertion.id) ?? [];
  console.log(
    `  ${found.length === 0 ? 'ok  ' : 'FAIL'} ${assertion.id.padEnd(20)} ${assertion.title}`,
  );
  for (const message of found) {
    console.log(`         - ${message}`);
  }
  if (found.length > 0) {
    console.log(`         why: ${assertion.why}`);
  }
}

if (failures.length > 0) {
  console.error(
    `\nverify-production: FAIL — ${String(failures.length)} violation(s) in the production build.`,
  );
  process.exit(1);
}

// ── 3 · the runtime proof ────────────────────────────────────────────────
console.log('\nverify-production: config.json replaceability (e2e, against a real dist/ copy)');
const playwrightBin = join(frontendRoot, 'node_modules/.bin/playwright');
const e2e = spawnSync(
  playwrightBin,
  ['test', 'e2e/config-replaceable.spec.ts', '--project=preview'],
  { cwd: frontendRoot, stdio: 'inherit' },
);
if (e2e.error) {
  console.error(e2e.error);
  process.exit(1);
}
if (e2e.status !== 0) {
  process.exit(e2e.status ?? 1);
}

console.log('\nverify-production: OK — all seven assertions hold.');
