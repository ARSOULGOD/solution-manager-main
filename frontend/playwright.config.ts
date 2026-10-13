/**
 * Two projects, because the suite has two genuinely different subjects.
 *
 * **`preview`** — the Vite **preview** server (a production build, static
 * `config.json` and all — design §21's real deployment shape), not the dev
 * server, per task 008's Implementation. No backend exists in this repo yet,
 * so `smoke.spec.ts`/`login.spec.ts` prove the boot path and the honest
 * failure state — never a fabricated success (each file's own header).
 *
 * **`fixture`** (task 125, `execution.md` Gate D) — the Vite **dev** server,
 * the only place MSW and the dev-mock auth adapter exist at all
 * (`import.meta.env.DEV`, `FE-D24` — both are dead-code-eliminated from the
 * `preview` build). Gate D is named a "**fixture** e2e journey" in the plan
 * itself: login → instance → edit → save (generation moves) → apply
 * (`APPLY_INDETERMINATE`) is not reachable against a backend-less
 * production build, so it needs its own server. This is the one deliberate
 * exception to the `preview` project's "no fabricated success" rule — it is
 * not pretending a backend exists; it is exercising the SAME fixture layer
 * the unit suite already runs `msw/node` against, in a real browser.
 *
 * `config-replaceable.spec.ts` (task 101, `FE-D14`) joins `preview` only to
 * inherit its `webServer`: it needs `dist/` to exist, then serves its OWN
 * copy of it on its own port with an edited `config.json`. Mutating the
 * shared preview server's `dist/` would change what `csp.spec.ts` and
 * `smoke.spec.ts` are looking at.
 *
 * `reflow.spec.ts` (task 110, `FE-D29`) joins `fixture` for the same
 * reason as `designer.spec.ts`: walking Designer/Explore/Dashboard at six
 * widths needs the MSW-backed dev server, not a backend-less production
 * build. `reflow.selftest.spec.ts` (proving the checker itself catches an
 * undeclared scroller) never navigates — `page.setContent` only — so
 * which project runs it doesn't matter; it rides along with `fixture`.
 */
import { defineConfig, devices } from '@playwright/test';

const PREVIEW_PORT = 4173;
const DEV_PORT = 5173;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 2 : 0,
  // Playwright's default assertion timeout is 5 s, which is a browser
  // default rather than a considered value for this setup: the `fixture`
  // project drives the **dev server**, so the first visit to a heavy
  // route (Designer, Explore) waits on Vite compiling that route's chunk
  // on demand, and the journeys run several workers against one server.
  // Raised so a cold compile is not read as a missing element; still far
  // short of the per-test timeout, so a genuine hang still fails.
  expect: { timeout: 15_000 },
  reporter: 'list',
  use: {
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'preview',
      testMatch: /(smoke|login|csp|config-replaceable)\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], baseURL: `http://localhost:${PREVIEW_PORT.toString()}` },
    },
    {
      name: 'fixture',
      testMatch: /(designer|journeys|connections|reflow|reflow\.selftest|csp-screens|monitoring-visual)\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], baseURL: `http://localhost:${DEV_PORT.toString()}` },
    },
  ],
  webServer: [
    {
      // --strictPort: fail rather than silently binding a different port,
      // which would desync from the fixed baseURL above.
      command: `npm run build && npm run preview -- --port ${PREVIEW_PORT.toString()} --strictPort`,
      url: `http://localhost:${PREVIEW_PORT.toString()}`,
      reuseExistingServer: !process.env['CI'],
      timeout: 120_000,
    },
    {
      command: `npm run dev -- --port ${DEV_PORT.toString()} --strictPort`,
      url: `http://localhost:${DEV_PORT.toString()}`,
      reuseExistingServer: !process.env['CI'],
      timeout: 120_000,
    },
  ],
});
