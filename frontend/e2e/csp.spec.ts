/**
 * FE-100 · Content-Security-Policy and air-gap verification (design §21,
 * `REQ-003`).
 *
 * Three things get proved here, against the **production preview build** —
 * the only server in this repo that serves the artefact a customer would
 * deploy, `config.json` and all:
 *
 * 1. **The policy is actually delivered**, byte-for-byte as `index.html`
 *    writes it. A policy a build step silently dropped is what this catches.
 * 2. **Nothing in the app violates it, and nothing leaves the origin.** No
 *    analytics, no beacon, no CDN, no Google Fonts (task 002 self-hosts
 *    them). An air-gapped install has no egress, so one third-party request
 *    is a broken deployment, not a slow one.
 * 3. **ECharts runs under it without `unsafe-eval`** — design §26 item 10,
 *    left open as *"CSP vs ECharts — recorded exception if `unsafe-eval` is
 *    required"*. The preview build has no backend, so no chart screen is
 *    reachable to answer that by navigation. The harness below answers it
 *    directly: a same-origin page carrying the **production policy
 *    verbatim**, importing the **real built echarts chunk**, rendering a
 *    real chart. Had ECharts needed `eval`, this is where it would fail —
 *    and the answer would be an entry in `csp-exceptions.json` with a
 *    written reason, never a quiet relaxation.
 *
 * The screens this build cannot reach are covered by `csp-screens.spec.ts`
 * on the fixture server. The static half of the gate — policy shape, the
 * exceptions registry, the built bundle's contents, egress-capable
 * dependencies — is `scripts/verify-csp.mjs`. `npm run verify:csp` runs
 * every part.
 */
import { readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { expect, test } from '@playwright/test';

import {
  collectViolations,
  deliveredPolicy,
  provokeForbiddenImage,
  readViolations,
  shippedPolicy,
  trackForeignRequests,
} from './csp/harness';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

/** The built echarts chunk — `scripts/assert-echarts-chunk.mjs` guarantees one exists. */
function echartsChunkUrl(): string {
  const assets = join(frontendRoot, 'dist/assets');
  const chunk = readdirSync(assets).find((file) => /^echarts-.*\.js$/.test(file));
  if (!chunk) {
    throw new Error(
      `no echarts-*.js chunk in ${assets} — run \`npm run build\` first (the preview webServer does)`,
    );
  }
  return `/assets/${chunk}`;
}

test('the served document carries the policy from index.html, and it is not relaxed', async ({
  page,
}) => {
  await page.goto('/');

  const delivered = await deliveredPolicy(page);

  expect(delivered).toBe(shippedPolicy());
  expect(delivered).toContain("default-src 'self'");
  // `unsafe-eval` is not merely absent: `csp-exceptions.json` records no
  // entry for it and `scripts/verify-csp.mjs` fails the build on any
  // unrecorded relaxation, so adding it would have to be a decision.
  expect(delivered).not.toContain('unsafe-eval');
});

test('the harness itself: a forbidden image is blocked and recorded', async ({ page }) => {
  await collectViolations(page);
  await page.goto('/login');

  await provokeForbiddenImage(page);

  await expect
    .poll(async () => (await readViolations(page)).map((violation) => violation.directive))
    .toContain('img-src');
});

test('booting, signing in and failing a submit raises no CSP violation and no third-party request', async ({
  page,
  baseURL,
}) => {
  const origin = new URL(baseURL ?? 'http://localhost:4173').origin;
  await collectViolations(page);
  const foreign = trackForeignRequests(page, origin);

  // The unauthenticated boot path: `/` redirects to `/login` (no backend
  // exists — `e2e/smoke.spec.ts`), the runtime config is fetched from the
  // same origin, and MUI/Emotion inject the app's styles at runtime, which
  // is the interesting half for `style-src`.
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();

  // An arrival state, so the alert components mount too.
  await page.goto('/login?arrival=idle');
  await expect(page.getByText(/signed out after \d+ minutes? idle/i)).toBeVisible();

  // A real submit: proves `connect-src 'self'` permits the app's own API
  // call, and that its failure path renders rather than being blocked.
  await page.getByLabel('Username').fill('devon.ramirez');
  await page.getByLabel(/^password/i).fill('whatever');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(
    page.getByText('Username or password is incorrect.').or(page.getByRole('alert')),
  ).toBeVisible({ timeout: 10_000 });

  const violations = await readViolations(page);
  expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
  expect(foreign, foreign.join('\n')).toEqual([]);
});

test('ECharts renders under the production policy — no unsafe-eval required (design §26 item 10)', async ({
  page,
  baseURL,
}) => {
  const origin = new URL(baseURL ?? 'http://localhost:4173').origin;
  const policy = shippedPolicy();
  const chunk = echartsChunkUrl();

  // A same-origin harness page, served from the preview origin so it is
  // subject to the same `'self'` the app is. Both documents are fulfilled by
  // Playwright rather than checked into `public/`: they must never exist in
  // a shipped build (`FE-D24`; FE-101 asserts the absence of dev-only
  // artefacts).
  //
  // Neither carries an inline script nor an inline `style` attribute — a
  // harness that needed them would be testing its own scaffolding instead of
  // ECharts. The element is sized through the CSSOM for the same reason.
  await page.route(`${origin}/__csp-echarts/`, (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="${policy}">
<title>echarts under CSP</title></head>
<body><div id="chart"></div><script type="module" src="./main.js"></script></body></html>`,
    }),
  );
  await page.route(`${origin}/__csp-echarts/main.js`, (route) =>
    route.fulfill({
      contentType: 'text/javascript',
      body: `
const echarts = await import('${chunk}');
const el = document.getElementById('chart');
el.style.width = '640px';
el.style.height = '360px';
const chart = echarts.init(el, undefined, { renderer: 'canvas' });
chart.setOption({
  xAxis: { type: 'category', data: ['a', 'b', 'c'] },
  yAxis: { type: 'value' },
  series: [{ type: 'line', data: [1, 2, 3] }],
});
window.__chartWidth = chart.getWidth();
`,
    }),
  );

  await collectViolations(page);
  const foreign = trackForeignRequests(page, origin);

  await page.goto('/__csp-echarts/');

  // The harness is under the real policy, not an empty one.
  expect(await deliveredPolicy(page)).toBe(policy);
  // And the chart really rendered: a canvas at the size ECharts measured.
  await expect(page.locator('#chart canvas')).toBeVisible();
  expect(
    await page.evaluate(() => (window as unknown as { __chartWidth?: number }).__chartWidth ?? 0),
  ).toBe(640);

  const violations = await readViolations(page);
  expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
  expect(foreign, foreign.join('\n')).toEqual([]);
});
