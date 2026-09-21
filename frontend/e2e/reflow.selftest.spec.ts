/**
 * Proves the checker FE-110 relies on actually catches what it claims to
 * catch — the task's own Testing line: "The job fails on a deliberately
 * overflowing fixture; the allowlist rejects an undeclared scroller."
 * `reflow.spec.ts` only ever sees `violations === []`; if `checkReflow`
 * itself were broken (e.g. inverted comparison) that file would pass for
 * the wrong reason. These specs never navigate the app — `page.setContent`
 * against a static fixture makes them fast and independent of the dev
 * server.
 */
import { expect, test } from '@playwright/test';

import { checkReflow } from './reflow/reflowCheck';

const WIDTH = 320;

test('flags a horizontal scroller that declares no exception', async ({ page }) => {
  await page.setContent(`
    <div style="min-width: 800px;">too wide, no data-reflow-exception</div>
  `);

  const violations = await checkReflow(page, WIDTH);

  expect(violations.some((violation) => violation.kind === 'body-scroll')).toBe(true);
});

test('passes an identical scroller once it declares a known exception', async ({ page }) => {
  await page.setContent(`
    <style>html, body { margin: 0; }</style>
    <div data-reflow-exception="data-table" style="overflow-x: auto; width: ${String(WIDTH)}px;">
      <div style="min-width: 800px; flex-shrink: 0;">wide content, inside a declared scroller</div>
    </div>
  `);

  const violations = await checkReflow(page, WIDTH);

  expect(violations).toEqual([]);
});

test('rejects a scroller that declares an unrecognized exception name', async ({ page }) => {
  await page.setContent(`
    <div data-reflow-exception="carousel" style="overflow-x: auto; width: ${String(WIDTH)}px;">
      <div style="min-width: 800px; flex-shrink: 0;">wide content</div>
    </div>
  `);

  const violations = await checkReflow(page, WIDTH);

  expect(violations.some((violation) => violation.kind === 'unknown-exception')).toBe(true);
});

test('flags a focusable element clipped outside the viewport', async ({ page }) => {
  await page.setContent(`
    <div style="position: absolute; left: -9999px;">
      <button type="button">off-screen but not aria-hidden</button>
    </div>
  `);

  const violations = await checkReflow(page, WIDTH);

  expect(violations.some((violation) => violation.kind === 'clipped-focusable')).toBe(true);
});

test('does not flag a clipped focusable that sits inside a declared exception', async ({ page }) => {
  await page.setContent(`
    <style>html, body { margin: 0; }</style>
    <div data-reflow-exception="data-table" style="overflow-x: auto; width: ${String(WIDTH)}px;">
      <div style="min-width: 800px; flex-shrink: 0;">
        <button type="button" style="margin-left: 700px;">scrolled off until the user scrolls</button>
      </div>
    </div>
  `);

  const violations = await checkReflow(page, WIDTH);

  expect(violations).toEqual([]);
});
