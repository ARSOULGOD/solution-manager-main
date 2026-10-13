#!/usr/bin/env node
/**
 * FE-101 · proof that each of the seven production assertions fails when
 * violated — the task's second acceptance bullet, run rather than claimed.
 *
 * Two halves, because they answer two different doubts:
 *
 *   **Described violations** (all seven). A minimal build description that
 *   breaks exactly one rule, and a clean baseline that breaks none. This is
 *   what proves an assertion is *discriminating*: a check that fires on
 *   everything is no better than one that fires on nothing, so the baseline
 *   matters as much as the violations. Three of the seven — the secret, the
 *   plane hostname, the un-replaceable config — have no natural home in a
 *   real build fixture anyway: you cannot commit a credential to prove a
 *   credential is detected.
 *
 *   **A real violating build** (the four that are tree-shaking guarantees).
 *   `src/test/production-fixtures/ships-dev-code.ts` imports the auth mock,
 *   the dev gallery, MSW and a fixture dataset with no `import.meta.env.DEV`
 *   guard, and is built here through the real `vite.config.ts` in production
 *   mode. Described inputs test the logic against a shape this file invents;
 *   this tests it against the shape the bundler actually produces — which is
 *   the one that changes under us when Vite, Rolldown or a plugin changes.
 *
 * Run by `verify-production-build.mjs` BEFORE the real gate. Nothing here
 * touches `dist/`; both builds are in-memory.
 */
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { build } from 'vite';

import { ASSERTIONS, assertProductionBuild, describeBundle } from './production-assertions.mjs';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

const problems = [];
const results = [];

function record(label, ok, detail) {
  results.push({ label, ok, detail });
  if (!ok) {
    problems.push(`${label}: ${detail}`);
  }
}

// ── the clean baseline ───────────────────────────────────────────────────

/** A believable, entirely legitimate build: no dev code, no secret, no host, a replaceable config. */
function cleanSubject() {
  return {
    chunks: [
      {
        fileName: 'assets/index-aaaa.js',
        code: 'const c=await fetch("/config.json");export{c};',
        moduleIds: [`${frontendRoot}/src/app/boot/loadConfig.ts`],
        modules: {
          [`${frontendRoot}/src/app/boot/loadConfig.ts`]: {
            code: 'const c=await fetch("/config.json");',
          },
        },
      },
      {
        // A dependency carrying an absolute URL and a `token:` assignment —
        // both legitimate, both must stay silent, or the gate is unusable.
        fileName: 'assets/vendor-bbbb.js',
        code: 'let o="http://localhost";let token="identifier";',
        moduleIds: [`${frontendRoot}/node_modules/react-router/dist/index.mjs`],
        modules: {
          [`${frontendRoot}/node_modules/react-router/dist/index.mjs`]: {
            code: 'let o="http://localhost";let token="identifier";',
          },
        },
      },
    ],
    assets: [
      { fileName: 'index.html', text: '<script src="/assets/index-aaaa.js"></script>' },
      { fileName: 'assets/index-cccc.css', text: 'body{margin:0}' },
    ],
    artefactFiles: [
      { path: 'index.html', text: '<script src="/assets/index-aaaa.js"></script>' },
      { path: 'assets/index-aaaa.js', text: 'const c=await fetch("/config.json");export{c};' },
      {
        path: 'config.json',
        text: '{\n  "apiBasePath": "/api",\n  "idleTimeoutMinutes": 30,\n  "channel": "stable"\n}\n',
      },
      { path: 'fonts/Inter-Regular.woff2', text: null },
    ],
  };
}

const baselineFailures = assertProductionBuild(cleanSubject());
record(
  'baseline (a clean build stays green)',
  baselineFailures.length === 0,
  baselineFailures.map((failure) => `${failure.id}: ${failure.message}`).join(' | '),
);

// ── one described violation per assertion ────────────────────────────────

/** @type {ReadonlyArray<{ id: string, what: string, mutate: (subject: ReturnType<typeof cleanSubject>) => void }>} */
const VIOLATIONS = [
  {
    id: 'no-auth-mock',
    what: 'the dev auth adapter is in the graph',
    mutate(subject) {
      const id = `${frontendRoot}/src/shared/auth/devMockAdapter.ts`;
      subject.chunks[0].moduleIds.push(id);
      subject.chunks[0].modules[id] = { code: 'function a(){return{name:"x"}}' };
    },
  },
  {
    id: 'no-dev-gallery',
    what: 'the gallery route module is in the graph',
    mutate(subject) {
      const id = `${frontendRoot}/src/app/dev/gallery.routes.ts`;
      subject.chunks[0].moduleIds.push(id);
      subject.chunks[0].modules[id] = { code: 'function g(){return[]}' };
    },
  },
  {
    id: 'no-msw',
    what: 'the built artefact ships the service-worker script',
    mutate(subject) {
      // Exactly what `dropDevOnlyAssetsFromBuild` not running looks like:
      // publicDir's copy sitting in the output directory.
      subject.artefactFiles.push({
        path: 'mockServiceWorker.js',
        text: '/* Mock Service Worker. */',
      });
    },
  },
  {
    id: 'no-fixture-dataset',
    what: 'a fixture data module is in the graph',
    mutate(subject) {
      const id = `${frontendRoot}/src/shared/testdoubles/data/instances.ts`;
      subject.chunks[0].moduleIds.push(id);
      subject.chunks[0].modules[id] = { code: 'const rows=[]' };
    },
  },
  {
    id: 'no-secret',
    what: 'our own module compiles in a credential',
    mutate(subject) {
      const id = `${frontendRoot}/src/shared/api/http.ts`;
      subject.chunks[0].moduleIds.push(id);
      // A fake value of a shape the scanner must catch; it is not, and must
      // never be, a real credential — see the header.
      subject.chunks[0].modules[id] = { code: 'const clientSecret="not-a-real-value-1234";' };
    },
  },
  {
    id: 'no-plane-hostname',
    what: 'our own module compiles in a plane origin',
    mutate(subject) {
      const id = `${frontendRoot}/src/shared/api/dpClient.ts`;
      subject.chunks[0].moduleIds.push(id);
      subject.chunks[0].modules[id] = {
        code: 'const base="https://dp.internal.example:8443/api";',
      };
    },
  },
  {
    id: 'config-replaceable',
    what: "config.json's contents were inlined into a chunk",
    mutate(subject) {
      subject.chunks[0].code += JSON.stringify({
        apiBasePath: '/api',
        idleTimeoutMinutes: 30,
        channel: 'stable',
      });
    },
  },
];

for (const { id, what, mutate } of VIOLATIONS) {
  const subject = cleanSubject();
  mutate(subject);
  const failures = assertProductionBuild(subject);
  const caught = failures.filter((failure) => failure.id === id);
  const collateral = failures.filter((failure) => failure.id !== id);
  record(
    `${id} — described: ${what}`,
    caught.length > 0 && collateral.length === 0,
    caught.length === 0
      ? 'the violation was NOT caught'
      : `caught, but also fired ${collateral.map((f) => f.id).join(', ')} — the assertions must be independent`,
  );
}

// ── the real violating build ─────────────────────────────────────────────

const violatingEntry = join(frontendRoot, 'src/test/production-fixtures/ships-dev-code.ts');

const result = await build({
  logLevel: 'silent',
  mode: 'production',
  build: {
    write: false,
    // `index.html` is replaced as the entry, so the app's own graph is not
    // built twice — only the violating one is under test here.
    rollupOptions: { input: violatingEntry },
  },
});

const output = (Array.isArray(result) ? result[0] : result).output;
// Described through the SAME `describeBundle` the gate uses — if that reads a
// field the bundler stopped populating, this half must break too, or it would
// go on passing while the gate silently saw an empty build.
// No artefact is written here — the four assertions under test are all
// module-graph ones — so a minimal stand-in keeps the other three quiet
// without pretending to be a real build output.
const violatingSubject = describeBundle(output, [
  { path: 'config.json', text: '{"apiBasePath":"/api","idleTimeoutMinutes":30,"channel":"x"}' },
]);

const realFailures = assertProductionBuild(violatingSubject);

/** Each tree-shaking assertion must fire AND name the module it caught. */
const EXPECTED_IN_REAL_BUILD = [
  { id: 'no-auth-mock', names: /devMockAdapter/ },
  { id: 'no-dev-gallery', names: /src\/app\/dev\// },
  { id: 'no-msw', names: /node_modules\/(?:msw|@mswjs)\// },
  { id: 'no-fixture-dataset', names: /shared\/testdoubles\// },
];

for (const { id, names } of EXPECTED_IN_REAL_BUILD) {
  const caught = realFailures.filter((failure) => failure.id === id);
  record(
    `${id} — real build of src/test/production-fixtures/ships-dev-code.ts`,
    caught.some((failure) => names.test(failure.message)),
    caught.length === 0
      ? 'not caught in a real production build that genuinely contains it'
      : `caught, but no message names ${String(names)}: ${caught.map((f) => f.message).join(' | ')}`,
  );
}

// ── report ───────────────────────────────────────────────────────────────
console.log('production-assertions selftest');
for (const { label, ok } of results) {
  console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${label}`);
}

const covered = new Set(VIOLATIONS.map((violation) => violation.id));
for (const assertion of ASSERTIONS) {
  if (!covered.has(assertion.id)) {
    problems.push(
      `${assertion.id} has no violation fixture — every assertion must be shown failing.`,
    );
  }
}

if (problems.length > 0) {
  console.error('\nproduction-assertions selftest: FAIL');
  for (const problem of problems) {
    console.error(`  - ${problem}`);
  }
  process.exit(1);
}

console.log(
  `\nproduction-assertions selftest: OK — all ${String(ASSERTIONS.length)} assertions fail on a violation ` +
    'and stay silent on a clean build.',
);
