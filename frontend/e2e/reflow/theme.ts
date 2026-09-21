/**
 * Forces `useUiStore`'s persisted colour mode before the app boots, so an
 * axe pass can cover both themes without clicking `ThemeToggle` on every
 * screen. Mirrors the exact persisted shape `uiStore.test.ts` asserts
 * (`{state:{mode, navCollapsed}, version}` under the `sm-ui-store` key) —
 * a shape this file does not own, so if `uiStore.ts`'s persist key or
 * version ever changes, that test breaks first.
 */
import type { Page } from '@playwright/test';

import type { SmThemeMode } from '../../src/shared/theme/createSmTheme';

export async function setThemeMode(page: Page, mode: SmThemeMode): Promise<void> {
  await page.addInitScript((themeMode: string) => {
    window.localStorage.setItem(
      'sm-ui-store',
      JSON.stringify({ state: { mode: themeMode, navCollapsed: false }, version: 0 }),
    );
  }, mode);
}
