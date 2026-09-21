#!/usr/bin/env node
/**
 * FE-100 · Content-Security-Policy and air-gap gate (design §21, `REQ-003`).
 *
 * Four static checks, then the two runtime specs. Each is a build failure,
 * never a warning — an air-gapped install has no egress, so a third-party
 * request is a broken deployment rather than a slow one.
 *
 *  1. **The policy's shape.** `index.html` is the single source of truth.
 *     `default-src 'self'` must be there, and every source that is not
 *     `'self'` or `'none'` must have a matching entry in
 *     `csp-exceptions.json` carrying a reason, a decision id and a
 *     follow-up. That is the mechanism behind the task's *"if ECharts
 *     requires `unsafe-eval`, that is a recorded exception with a written
 *     reason — not a silent relaxation"*, generalised: it holds for any
 *     relaxation, of any directive, forever. Stale entries fail too, so the
 *     registry cannot outlive the exception it justifies.
 *  2. **The build keeps it.** The real production build is run in-memory and
 *     its emitted `index.html` must carry the identical policy string. A
 *     plugin that drops the tag is otherwise invisible.
 *  3. **No eval in the shipped bundle.** `eval(` / `new Function(` in an
 *     emitted chunk is either a relaxation waiting to happen or dead weight;
 *     either way it is recorded in `codeExceptions` or it fails.
 *  4. **No egress in the shipped bundle or the dependency set** — analytics,
 *     RUM, CDN loaders, Google Fonts (task 002 self-hosts them). Remaining
 *     external origins are *printed* rather than failed: a documentation URL
 *     inside an error string is not a request, and the runtime proof of
 *     "zero third-party requests" is the e2e below, not a grep.
 *
 * Then `e2e/csp.spec.ts` (production build, exact policy, ECharts) and
 * `e2e/csp-screens.spec.ts` (every real screen, fixture server).
 */
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { build } from 'vite';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

/** Sources that need no justification: the origin itself, and nothing at all. */
const UNCONDITIONAL_SOURCES = new Set(["'self'", "'none'"]);

/**
 * Hosts and packages that exist to phone home, plus the CDN pattern the
 * air-gap forbids. Not exhaustive by construction — the runtime assertion in
 * `e2e/csp.spec.ts` is what makes "zero third-party requests" true; this list
 * catches the well-known ones in code paths no e2e can reach.
 */
const EGRESS_HOSTS = [
  'fonts.googleapis.com',
  'fonts.gstatic.com',
  'google-analytics.com',
  'googletagmanager.com',
  'cdn.jsdelivr.net',
  'unpkg.com',
  'cdnjs.cloudflare.com',
  'sentry.io',
  'datadoghq.com',
  'newrelic.com',
  'segment.com',
  'segment.io',
  'mixpanel.com',
  'hotjar.com',
  'bugsnag.com',
  'launchdarkly.com',
  'amplitude.com',
  'fullstory.com',
  'posthog.com',
  'intercom.io',
];

const EGRESS_PACKAGES = [
  '@sentry/browser',
  '@sentry/react',
  '@datadog/browser-rum',
  '@amplitude/analytics-browser',
  '@fullstory/browser',
  '@microsoft/applicationinsights-web',
  'react-ga',
  'react-ga4',
  'mixpanel-browser',
  'posthog-js',
  'logrocket',
  'launchdarkly-js-client-sdk',
];

const EVAL_PATTERNS = [/\beval\s*\(/, /\bnew\s+Function\s*\(/];

const failures = [];
const fail = (message) => failures.push(message);

// ── 1 · the policy's shape ──────────────────────────────────────────────
const indexHtml = readFileSync(join(frontendRoot, 'index.html'), 'utf8');
const policyMatch = /<meta\s+http-equiv="Content-Security-Policy"\s+content="([^"]+)"/i.exec(
  indexHtml,
);
if (!policyMatch) {
  console.error('verify-csp: index.html carries no Content-Security-Policy meta tag (FE-100).');
  process.exit(1);
}
const policy = policyMatch[1];

const registry = JSON.parse(readFileSync(join(frontendRoot, 'csp-exceptions.json'), 'utf8'));
const exceptions = registry.exceptions ?? [];
const codeExceptions = registry.codeExceptions ?? [];

const directives = policy
  .split(';')
  .map((part) => part.trim())
  .filter(Boolean)
  .map((part) => {
    const [name, ...sources] = part.split(/\s+/);
    return { name, sources };
  });

if (!directives.some(({ name, sources }) => name === 'default-src' && sources.includes("'self'"))) {
  fail(`the policy does not start from \`default-src 'self'\` (design §21): ${policy}`);
}

const matched = new Set();
for (const { name, sources } of directives) {
  for (const source of sources) {
    if (UNCONDITIONAL_SOURCES.has(source)) {
      continue;
    }
    const index = exceptions.findIndex(
      (entry) => entry.directive === name && entry.source === source,
    );
    if (index === -1) {
      fail(
        `\`${name} ${source}\` is a relaxation with no entry in csp-exceptions.json. ` +
          'Record it — directive, source, reason, decision id, follow-up — or remove it.',
      );
      continue;
    }
    matched.add(index);
    const entry = exceptions[index];
    if (!/^FE-D\d+$/.test(entry.decision ?? '')) {
      fail(`csp-exceptions.json: \`${name} ${source}\` needs a decision id of the form FE-D<n>.`);
    }
    if ((entry.reason ?? '').length < 80) {
      fail(
        `csp-exceptions.json: \`${name} ${source}\` needs a reason that says what breaks without it.`,
      );
    }
    if (!(entry.followUp ?? '').trim()) {
      fail(
        `csp-exceptions.json: \`${name} ${source}\` needs a followUp — an exception with no way out is a permanent hole.`,
      );
    }
  }
}
for (const [index, entry] of exceptions.entries()) {
  if (!matched.has(index)) {
    fail(
      `csp-exceptions.json records \`${entry.directive} ${entry.source}\`, which the policy no longer contains. Delete the entry.`,
    );
  }
}

console.log(`verify-csp: policy (from index.html)\n  ${policy.replaceAll('; ', ';\n  ')}`);
for (const entry of exceptions) {
  console.log(
    `verify-csp: recorded exception \`${entry.directive} ${entry.source}\` (${entry.decision})`,
  );
}

// ── 2-4 · what the build actually emits ─────────────────────────────────
const result = await build({ logLevel: 'silent', build: { write: false } });
const output = (Array.isArray(result) ? result[0] : result).output;

const emittedHtml = output.find(
  (item) => item.type === 'asset' && item.fileName.endsWith('index.html'),
);
if (!emittedHtml) {
  fail('the production build emitted no index.html.');
} else if (!String(emittedHtml.source).includes(`content="${policy}"`)) {
  fail(
    'the built index.html does not carry the policy from the source index.html — a build step is dropping or rewriting it.',
  );
}

const emitted = output.map((item) => ({
  fileName: item.fileName,
  text: item.type === 'chunk' ? item.code : String(item.source ?? ''),
}));

for (const { fileName, text } of emitted) {
  if (!/\.(js|css|html)$/.test(fileName)) {
    continue;
  }
  for (const pattern of EVAL_PATTERNS) {
    const hit = pattern.exec(text);
    if (!hit) {
      continue;
    }
    const recorded = codeExceptions.some(
      (entry) => entry.file === fileName || entry.pattern === hit[0].trim(),
    );
    if (!recorded) {
      fail(
        `${fileName} contains \`${hit[0].trim()}\`. The policy has no \`unsafe-eval\`, so this either ` +
          'never runs (record it in csp-exceptions.json codeExceptions, with why) or the policy needs a ' +
          'recorded exception. Do not add `unsafe-eval` silently.',
      );
    }
  }
  for (const host of EGRESS_HOSTS) {
    if (text.includes(host)) {
      fail(`${fileName} references ${host}. An air-gapped install cannot reach it (REQ-003).`);
    }
  }
  if (/<(script|link)[^>]+(src|href)="https?:\/\//i.test(text)) {
    fail(`${fileName} loads a script or stylesheet from an absolute URL — no CDN (REQ-003).`);
  }
  if (/@import\s+(url\()?["']?https?:\/\//i.test(text) || /url\(\s*["']?https?:\/\//i.test(text)) {
    fail(`${fileName} pulls a CSS resource from an absolute URL — no CDN, no web fonts (REQ-003).`);
  }
}

const manifest = readFileSync(join(frontendRoot, 'package.json'), 'utf8');
const lockfile = readFileSync(join(frontendRoot, 'package-lock.json'), 'utf8');
for (const pkg of EGRESS_PACKAGES) {
  if (manifest.includes(`"${pkg}"`) || lockfile.includes(`node_modules/${pkg}`)) {
    fail(
      `${pkg} is installed. Production error shipping waits on a backend log endpoint (design §20, FE-102) — no SaaS RUM in an air-gapped deployment.`,
    );
  }
}

// Informational: every other external origin the bundle mentions. These are
// strings — documentation URLs in framework error messages, the SVG
// namespace — not requests. The e2e is what proves no request is made.
const origins = new Map();
for (const { fileName, text } of emitted) {
  for (const [, origin] of text.matchAll(/https?:\/\/([a-z0-9.-]+)/gi)) {
    if (origin === 'localhost' || origin.startsWith('127.')) {
      continue;
    }
    origins.set(origin, (origins.get(origin) ?? new Set()).add(fileName));
  }
}
console.log('verify-csp: external origins mentioned in the build (strings, not requests):');
for (const [origin, files] of [...origins].sort()) {
  console.log(`  ${origin.padEnd(24)} ${[...files].join(', ')}`);
}

if (failures.length > 0) {
  console.error('\nverify-csp: FAIL');
  for (const failure of failures) {
    console.error(`  - ${failure}`);
  }
  process.exit(1);
}
console.log('\nverify-csp: static checks OK — running the runtime specs');

const playwright = spawnSync(
  join(frontendRoot, 'node_modules/.bin/playwright'),
  ['test', 'e2e/csp.spec.ts', '--project=preview', 'e2e/csp-screens.spec.ts', '--project=fixture'],
  { cwd: frontendRoot, stdio: 'inherit' },
);
if (playwright.error) {
  console.error(playwright.error);
  process.exit(1);
}
process.exit(playwright.status ?? 1);
