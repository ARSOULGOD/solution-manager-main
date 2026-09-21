/**
 * FE-101 · `config.json` is externally replaceable without a rebuild
 * (`FE-D14`, design §21) — the runtime half of `npm run verify:production`.
 *
 * `scripts/production-assertions.mjs` proves the *negative*: no chunk was
 * handed the config's values at build time, the file is not content-hashed,
 * `index.html` does not pin its name. Those are necessary and none of them
 * is the claim. The claim is that an operator can drop a different
 * `config.json` next to `index.html`, reload, and have the console talk to
 * the new prefix — and the only way to show that is to do it.
 *
 * So: copy the built `dist/` (the artefact a customer deploys, produced by
 * the `preview` project's own `npm run build`), edit ONE file in the copy,
 * serve the copy, and watch where the console sends its first API call.
 * Nothing is rebuilt between the copy and the load; the bundle under test is
 * byte-identical to the one `dist/` holds.
 *
 * A private static server rather than the shared `preview` one, because
 * mutating `dist/config.json` in place would change what every other spec on
 * that project is looking at. This runs on the `preview` project only to
 * inherit its `webServer`, which is what guarantees `dist/` exists at all;
 * it navigates by absolute URL and never touches `baseURL`.
 */
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

import { expect, test } from '@playwright/test';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const distDir = join(frontendRoot, 'dist');

/**
 * Deliberately unlike the shipped `/api`, and unlike anything a bundler
 * could have guessed: if this string reaches the network, it came from the
 * file that was edited after the build.
 */
const REPLACED_BASE_PATH = '/replaced-without-rebuild/api';

const CONTENT_TYPE: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
};

let root: string;
let server: Server;
let origin: string;

test.beforeAll(async () => {
  root = mkdtempSync(join(tmpdir(), 'sm-config-replaceable-'));
  cpSync(distDir, root, { recursive: true });

  // The one edit. Everything else in the copy — every chunk, the CSS, the
  // HTML — is the build's own output, untouched.
  const configPath = join(root, 'config.json');
  const shipped: unknown = JSON.parse(readFileSync(configPath, 'utf8'));
  writeFileSync(
    configPath,
    JSON.stringify(
      { ...(shipped as Record<string, unknown>), apiBasePath: REPLACED_BASE_PATH },
      null,
      2,
    ),
  );

  server = createServer((req, res) => {
    const requested = decodeURIComponent((req.url ?? '/').split('?')[0] ?? '/');
    // `normalize` then a prefix check: a served directory must not be a path
    // traversal, even in a test server that lives for four seconds.
    const candidate = normalize(join(root, requested === '/' ? 'index.html' : requested));
    if (!candidate.startsWith(root)) {
      res.writeHead(403).end();
      return;
    }
    let body: Buffer;
    let path = candidate;
    try {
      body = readFileSync(path);
    } catch {
      // SPA fallback — /login is a client route, not a file.
      path = join(root, 'index.html');
      body = readFileSync(path);
    }
    res.writeHead(200, {
      'Content-Type': CONTENT_TYPE[extname(path)] ?? 'application/octet-stream',
    });
    res.end(body);
  });

  await new Promise<void>((resolve) => {
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  if (address === null || typeof address === 'string') {
    throw new Error('the static server did not bind a port');
  }
  origin = `http://127.0.0.1:${String(address.port)}`;
});

test.afterAll(async () => {
  await new Promise<void>((resolve) => {
    server.close(() => {
      resolve();
    });
  });
  rmSync(root, { recursive: true, force: true });
});

test('an edited config.json retargets the built console with no rebuild', async ({ page }) => {
  const apiCalls: string[] = [];
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    if (path.includes('/auth/') || path.startsWith('/api/')) {
      apiCalls.push(path);
    }
  });

  await page.goto(`${origin}/login`);
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();

  // A real submit, so the failure is observable in the network rather than
  // inferred: no backend exists, so this call fails — where it was SENT is
  // the whole point.
  await page.getByLabel('Username').fill('devon.ramirez');
  await page.getByLabel(/^password/i).fill('whatever');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect
    .poll(() => apiCalls, { message: 'the console never called an API path' })
    .toContain(`${REPLACED_BASE_PATH}/cp/auth/login`);

  // And the build-time value is genuinely gone, not merely also present.
  expect(apiCalls.filter((path) => path.startsWith('/api/'))).toEqual([]);
});

test('the served artefact is the build output — only config.json differs', () => {
  const shipped: unknown = JSON.parse(readFileSync(join(distDir, 'config.json'), 'utf8'));
  const served: unknown = JSON.parse(readFileSync(join(root, 'config.json'), 'utf8'));

  // Guards the premise of the test above: if the copy had been rebuilt, or
  // dist/ had been mutated, the two would not differ in exactly one field.
  expect((shipped as Record<string, unknown>)['apiBasePath']).not.toBe(REPLACED_BASE_PATH);
  expect((served as Record<string, unknown>)['apiBasePath']).toBe(REPLACED_BASE_PATH);
  expect(readFileSync(join(root, 'index.html'), 'utf8')).toBe(
    readFileSync(join(distDir, 'index.html'), 'utf8'),
  );
});
