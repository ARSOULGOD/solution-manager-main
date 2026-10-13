/**
 * FE-110: `FE-D29` (console reflows to 320 CSS px, WCAG 2.2 AA SC 1.4.10)
 * as a CI gate, not a claim. Walks every real Phase 0-6 screen
 * (`./reflow/screens.ts`) at the six widths the task names, and checks
 * axe (real `axe-core`, not the jsdom `jest-axe` shim task 098 already
 * covers) at the reflow floor and at desktop, in both themes.
 *
 * Runs on the `fixture` project — the dev server, MSW and all
 * (`playwright.config.ts`'s header explains why `designer.spec.ts`
 * already needs the same thing).
 */
import { expect, test } from '@playwright/test';

import { runAxe } from './reflow/axeCheck';
import { measureReflowViolations, REFLOW_WIDTHS } from './reflow/reflowCheck';
import { gotoScreen, REFLOW_SCREENS } from './reflow/screens';
import { setThemeMode } from './reflow/theme';

for (const screen of REFLOW_SCREENS) {
  test.describe(screen.name, () => {
    for (const width of REFLOW_WIDTHS) {
      test(`reflows cleanly at ${String(width)}px`, async ({ page }) => {
        // Resize BEFORE navigating (`reflowCheck.ts`'s own header) so the
        // first render already happens at the target width, rather than
        // racing `useMediaQuery`/`ResizeObserver` callbacks that fire a
        // tick after a post-navigation resize.
        await page.setViewportSize({ width, height: 900 });
        await gotoScreen(page, screen);
        const violations = await measureReflowViolations(page, width);

        expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
      });
    }
  });
}

const AXE_WIDTHS = [320, 1560] as const;
const AXE_THEMES = ['light', 'dark'] as const;

for (const screen of REFLOW_SCREENS) {
  for (const width of AXE_WIDTHS) {
    for (const themeMode of AXE_THEMES) {
      test(`${screen.name} — axe clean at ${String(width)}px, ${themeMode}`, async ({ page }) => {
        await setThemeMode(page, themeMode);
        await page.setViewportSize({ width, height: 900 });
        await gotoScreen(page, screen);

        const violations = await runAxe(page);

        expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
      });
    }
  }
}

test.describe('orientation (SC 1.3.4)', () => {
  // A narrow *and* short viewport is the closest Playwright gets to a
  // real landscape phone without device emulation. If any screen keyed
  // its layout off orientation rather than width, this is where it would
  // show up as a missing landmark rather than a mere reflow difference.
  for (const screen of REFLOW_SCREENS) {
    test(`${screen.name} renders its main landmark in a landscape-shaped viewport`, async ({
      page,
    }) => {
      await page.setViewportSize({ width: 812, height: 375 });
      await gotoScreen(page, screen);

      await expect(page.locator(screen.readySelector).first()).toBeVisible();
    });
  }
});
