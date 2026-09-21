/**
 * FE-100 · the policy walked across every real screen.
 *
 * `csp.spec.ts` runs the **production** build under the **exact** shipped
 * policy, which is the only honest way to prove what a customer receives —
 * but that build has no backend, so the only screen it can reach is sign-in.
 * A policy whose remaining directives were never exercised against a chart,
 * a data grid or the Designer is a policy that breaks a screen after
 * release, which is what this file exists to prevent.
 *
 * So this walks `reflow/screens.ts`'s twelve real screens on the `fixture`
 * (dev) project — MSW data, charts and grids actually rendering — with the
 * shipped policy applied to the document, plus **three named, derived
 * dev-server allowances and nothing else**:
 *
 * | Addition | Why it is dev-only |
 * |---|---|
 * | `script-src 'unsafe-inline'` | `@vitejs/plugin-react` injects an inline React-Refresh preamble. A build emits external module scripts only (`dist/index.html`). |
 * | `connect-src ws:` | HMR's websocket. There is no HMR in a build. |
 * | `worker-src 'self'` | MSW registers `mockServiceWorker.js`. Dev-only by `FE-D24`; the shipped policy keeps `worker-src 'none'`. |
 *
 * The additions are computed from the shipped policy at runtime, never
 * copy-pasted, and a directive that disappears from `index.html` fails this
 * file rather than silently leaving a stale allowance behind. Everything the
 * additions do not name — `img-src`, `font-src`, `object-src`, `frame-src`,
 * `base-uri`, `form-action`, and `connect-src` for anything that is not the
 * HMR socket — is enforced here exactly as it ships.
 */
import { expect, test, type Page } from '@playwright/test';

import {
  collectViolations,
  deliveredPolicy,
  provokeForbiddenImage,
  readViolations,
  shippedPolicy,
  trackForeignRequests,
} from './csp/harness';
import { gotoScreen, REFLOW_SCREENS } from './reflow/screens';

/** Dev-server-only sources, keyed by directive. See the table in the header. */
const DEV_ADDITIONS: Readonly<Record<string, readonly string[]>> = {
  'script-src': ["'unsafe-inline'"],
  'connect-src': ['ws:'],
  'worker-src': ["'self'"],
};

/** The shipped policy with those three allowances merged in. */
function devPolicy(): string {
  const seen = new Set<string>();
  const merged = shippedPolicy()
    .split(';')
    .map((directive) => directive.trim())
    .filter(Boolean)
    .map((directive) => {
      const [name = '', ...sources] = directive.split(/\s+/);
      seen.add(name);
      const additions = DEV_ADDITIONS[name] ?? [];
      // `'none'` is not a list that can be extended — it is replaced.
      const base = sources.filter((source) => !(source === "'none'" && additions.length > 0));
      return [name, ...base, ...additions].join(' ');
    });

  for (const name of Object.keys(DEV_ADDITIONS)) {
    if (!seen.has(name)) {
      throw new Error(
        `csp-screens: the shipped policy no longer declares "${name}", so its dev allowance is stale. Update DEV_ADDITIONS.`,
      );
    }
  }
  return merged.join('; ');
}

const POLICY = devPolicy();

/**
 * Applies the policy to the dev server's HTML document and starts recording
 * violations before the first script runs. Only the top-level document is
 * rewritten; every other request is left alone.
 */
async function applyPolicy(page: Page): Promise<void> {
  await page.route('**/*', async (route) => {
    if (route.request().resourceType() !== 'document') {
      await route.fallback();
      return;
    }
    const response = await route.fetch();
    const body = (await response.text()).replace(
      '<head>',
      `<head><meta http-equiv="Content-Security-Policy" content="${POLICY}">`,
    );
    await route.fulfill({ response, body });
  });
  await collectViolations(page);
}

test('the harness itself: the policy is applied to the dev document and a forbidden image is recorded', async ({
  page,
}) => {
  await applyPolicy(page);
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();

  // Vacuously-passing sweeps are the risk here: if the injection missed or
  // the listener never ran, every test below would pass by doing nothing.
  expect(await deliveredPolicy(page)).toBe(POLICY);
  expect(POLICY).toContain("default-src 'self'");
  expect(POLICY).not.toContain('unsafe-eval');

  await provokeForbiddenImage(page);
  await expect
    .poll(async () => (await readViolations(page)).map((violation) => violation.directive))
    .toContain('img-src');
});

for (const screen of REFLOW_SCREENS) {
  test(`${screen.name} renders under the shipped policy, with no request leaving the origin`, async ({
    page,
    baseURL,
  }) => {
    const origin = new URL(baseURL ?? 'http://localhost:5173').origin;
    const foreign = trackForeignRequests(page, origin);

    await applyPolicy(page);
    await gotoScreen(page, screen);
    // Charts mount asynchronously behind `ChartRenderer`'s `import('echarts')`
    // (FE-D16); without this the monitoring screens would be measured before
    // the one dependency most likely to need a relaxation had even loaded.
    await page.waitForLoadState('networkidle');

    expect(await deliveredPolicy(page)).toBe(POLICY);

    const violations = await readViolations(page);
    expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
    expect(foreign, foreign.join('\n')).toEqual([]);
  });
}
