/**
 * FE-100 · the pieces both CSP specs share.
 *
 * `csp.spec.ts` runs the production preview build under the **exact**
 * shipped policy; `csp-screens.spec.ts` walks every real screen on the dev
 * server under the same policy plus three named dev allowances. Both need
 * the same three things: the policy as written in `index.html` (the single
 * source of truth — never a copy), a violation recorder installed before the
 * document's first script, and a record of every request that left the
 * origin.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Page } from '@playwright/test';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '../..');

export interface CspViolation {
  directive: string;
  blockedURI: string;
  source: string;
}

/** The Content-Security-Policy exactly as `index.html` declares it. */
export function shippedPolicy(): string {
  const html = readFileSync(join(frontendRoot, 'index.html'), 'utf8');
  const match = /<meta\s+http-equiv="Content-Security-Policy"\s+content="([^"]+)"/i.exec(html);
  if (!match?.[1]) {
    throw new Error('index.html carries no Content-Security-Policy meta tag (FE-100)');
  }
  return match[1];
}

/**
 * Records `securitypolicyviolation` events from the first script the
 * document runs, so a violation raised by the entry chunk itself is caught.
 */
export async function collectViolations(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const store: CspViolation[] = [];
    (window as unknown as { __cspViolations: CspViolation[] }).__cspViolations = store;
    document.addEventListener('securitypolicyviolation', (event) => {
      store.push({
        directive: event.violatedDirective,
        blockedURI: event.blockedURI,
        source: `${event.sourceFile}:${String(event.lineNumber)}`,
      });
    });
  });
}

export const readViolations = (page: Page): Promise<CspViolation[]> =>
  page.evaluate(
    () => (window as unknown as { __cspViolations?: CspViolation[] }).__cspViolations ?? [],
  );

/** The policy the document actually received — empty string if it received none. */
export const deliveredPolicy = async (page: Page): Promise<string> =>
  (await page
    .locator('meta[http-equiv="Content-Security-Policy"]')
    .first()
    .getAttribute('content')) ?? '';

/**
 * Every request the page makes that is not same-origin, as it happens.
 *
 * `data:` and `blob:` carry no origin and never leave the machine — the CSV
 * export builds a `blob:` URL (`DataTable.exportCsv`), which is not egress.
 * `ws:`/`wss:` is the dev server's HMR socket, absent from a build.
 */
export function trackForeignRequests(page: Page, origin: string): string[] {
  const foreign: string[] = [];
  page.on('request', (request) => {
    const url = request.url();
    if (/^(data|blob|about|ws|wss):/i.test(url)) {
      return;
    }
    if (new URL(url).origin !== origin) {
      foreign.push(`${request.resourceType()} ${url}`);
    }
  });
  return foreign;
}

/**
 * The negative control both specs run first. A policy that was never
 * delivered, or a listener that never fired, makes every other assertion in
 * this suite pass by doing nothing — so each spec proves the instrument
 * works by loading an image from an origin the policy forbids and requiring
 * the violation to show up. Same discipline as `reflow.selftest.spec.ts`.
 */
export async function provokeForbiddenImage(page: Page): Promise<void> {
  await page.evaluate(() => {
    const image = document.createElement('img');
    image.src = 'https://third-party.invalid/pixel.png';
    document.body.append(image);
  });
}
