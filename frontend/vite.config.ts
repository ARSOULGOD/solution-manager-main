import { existsSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';

// Path aliases here MUST stay in sync with the "paths" block in tsconfig.json —
// Vite (dev/build) and tsc resolve modules independently, so each tool needs
// its own copy of the mapping. vitest.config.ts inherits this `resolve.alias`
// via `mergeConfig(this file, ...)` — a dedicated vitest.config.ts makes
// Vitest ignore this file's own config entirely unless merged in explicitly
// (task 008), so the alias must still only be declared once, here.
const alias = {
  '@app': fileURLToPath(new URL('./src/app', import.meta.url)),
  '@shared': fileURLToPath(new URL('./src/shared', import.meta.url)),
  '@features': fileURLToPath(new URL('./src/features', import.meta.url)),
  '@contracts': fileURLToPath(new URL('./contracts', import.meta.url)),
};

// The production Content-Security-Policy lives verbatim in `index.html`
// (FE-100) so that the shipped artefact, the reviewer and SM packaging all
// read the same string. The Vite **dev** server cannot run under it: the
// React plugin injects an inline `<script>` preamble and HMR opens a `ws:`
// connection, which `script-src 'self'` and `connect-src 'self'` both
// forbid. The choice is to loosen the policy for dev — making it untrue of
// the build that ships — or to remove it from the build that never ships.
// This removes it, and `scripts/verify-csp.mjs` asserts the production build
// still carries it byte-for-byte.
const CSP_META = /[ \t]*<meta\s+http-equiv="Content-Security-Policy"[\s\S]*?\/>\n?/i;

const stripCspInDev = (): Plugin => ({
  name: 'sm:strip-csp-in-dev',
  apply: 'serve',
  transformIndexHtml: {
    order: 'pre',
    handler: (html: string) => html.replace(CSP_META, ''),
  },
});

// MSW's service-worker script (`npx msw init public/`) must be served from
// the origin ROOT — `/mockServiceWorker.js`, msw's `DEFAULT_WORKER_URL` — so
// `public/` is where it has to live in DEV. But `public/` is copied verbatim
// into `dist/`, which means the production artefact was shipping MSW's
// interception machinery: found by FE-101's `no-msw` assertion, which walks
// the built artefact.
//
// So it is removed from the OUTPUT rather than moved in the SOURCE. That is
// the opposite of the usual preference for a structural guarantee over a
// cleanup step, and it is deliberate: the structural version was built first
// — the worker in `dev/`, served by an `apply: 'serve'` middleware — and it
// broke the fixture e2e suite. Eleven Designer/journey/reflow tests failed
// with `Failed to fetch dynamically imported module`, non-deterministically,
// under the full suite's five parallel workers; the same tests passed with
// the worker back in `public/` (bisected against a clean `main` run: 166
// passed on HEAD, 11 failed with the middleware, 168 passed with it
// reverted). Serving a service worker correctly under concurrent
// registration is Vite's static middleware's job, and it does it better than
// this file did.
//
// The cleanup step is therefore the mechanism, and FE-101's assertion is what
// keeps it honest: `npm run verify:production` walks the REAL built artefact,
// so if this plugin stops running — removed, renamed, or defeated by a config
// change — the gate goes red. A guard that is verified by the artefact it
// guards is not "one mistake away"; the mistake fails the build.
const DEV_ONLY_BUILD_ARTEFACTS = ['mockServiceWorker.js'];

const dropDevOnlyAssetsFromBuild = (): Plugin => {
  let outDir = '';
  return {
    name: 'sm:drop-dev-only-assets-from-build',
    apply: 'build',
    configResolved(config) {
      outDir = resolve(config.root, config.build.outDir);
    },
    // `closeBundle` runs after the publicDir copy, which is what puts the
    // file there in the first place.
    closeBundle() {
      for (const name of DEV_ONLY_BUILD_ARTEFACTS) {
        const path = join(outDir, name);
        if (existsSync(path)) {
          rmSync(path);
          this.info(`removed dev-only artefact ${name} from the build (FE-101 no-msw)`);
        }
      }
    },
  };
};

export default defineConfig({
  plugins: [react(), stripCspInDev(), dropDevOnlyAssetsFromBuild()],
  resolve: { alias },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
