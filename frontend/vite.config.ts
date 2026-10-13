import { existsSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import basicSsl from '@vitejs/plugin-basic-ssl';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv, type Plugin } from 'vite';

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

// Dev-only same-origin backend proxy, so the browser sees ONE origin for both the app and the API.
//
// HTTPS is enabled ONLY in real-backend mode (`VITE_USE_REAL_AUTH=true`, see
// `.env.development.local.example`), because the two dev modes need opposite things:
//
// - Real backend: console-gateway's session cookie is unconditionally Secure (no dev exception —
//   see backend/console-gateway's AuthController/session design), so a plain-HTTP dev server could
//   never carry it back on subsequent requests. `basicSsl()` generates a local self-signed cert
//   (accept the one-time browser warning).
// - Mock/static mode (no backend; MSW): a browser REFUSES to register a service worker over a
//   self-signed certificate ("An SSL certificate error occurred when fetching the script"), and
//   MSW is a service worker — so under HTTPS every fixture-backed API call silently falls through
//   to the network and 404s. `http://localhost` is a secure context, so plain HTTP works. The
//   Playwright e2e config (`playwright.config.ts`) also targets `http://localhost`.
//
// changeOrigin: false (real-backend mode): console-gateway has no CORS support by design — it
// validates the Origin/Referer header on every unsafe request against its own
// sm.gateway.trusted-origins allowlist (backend/launcher's application-dev.yml sets that to
// https://localhost:5173). Rewriting the proxied request's Origin (changeOrigin: true, proxy
// middleware's default) would make the backend see its own address instead of the browser's, and
// trusted-origins compares byte-for-byte — so this must stay false.
const realBackend =
  loadEnv(
    process.env.NODE_ENV === 'production' ? 'production' : 'development',
    process.cwd(),
    'VITE_',
  ).VITE_USE_REAL_AUTH === 'true';

// An object (not a function) on purpose: `vitest.config.ts` `mergeConfig`s this export, and
// `mergeConfig` cannot merge a callback.
export default defineConfig({
  plugins: [
    react(),
    ...(realBackend ? [basicSsl()] : []),
    stripCspInDev(),
    dropDevOnlyAssetsFromBuild(),
  ],
  resolve: { alias },
  server: {
    https: realBackend ? {} : undefined,
    host: '127.0.0.1',
    port: Number(process.env['SM_LOCAL_UI_PORT'] ?? 5173),
    proxy: {
      '/api': {
        target: process.env['SM_LOCAL_BACKEND_TARGET'] ?? 'http://localhost:8081',
        changeOrigin: false,
      },
    },
  },
});
