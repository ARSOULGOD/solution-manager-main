/**
 * Task 098's own named target: "Axe runs over the component gallery" —
 * `Gallery.tsx`'s header comment already calls this file's route "the
 * surface the automated a11y sweep (task 098) walks". One pass over the
 * WHOLE assembled gallery (every Phase 1 primitive on one page) rather
 * than per-component only — a composition-level defect (duplicate ids,
 * a heading-order jump across two unrelated sections) can exist here
 * even when every individual component's own `.a11y.test.tsx` is clean.
 *
 * Uses the same plain `react-dom` render helper `Gallery.test.tsx` already
 * established (this file predates `renderWithProviders`, task 008) rather
 * than inventing a second one — parametrized over `mode` here since that
 * one is hardcoded to `'light'`.
 */
import { ThemeProvider } from '@mui/material';
import { axe } from 'jest-axe';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';

import { useUiStore } from '@shared/state/uiStore';
import { createSmTheme, type SmThemeMode } from '@shared/theme';

import Gallery from './Gallery';

// Same requirement `Gallery.test.tsx` already documents — plain
// `react-dom`, not RTL, so `act` needs this flag set itself.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement | null = null;
let root: Root | null = null;

afterEach(() => {
  if (root) {
    act(() => {
      root?.unmount();
    });
  }
  container?.remove();
  container = null;
  root = null;
  useUiStore.setState({ mode: 'light' });
});

function render(mode: SmThemeMode): HTMLDivElement {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  act(() => {
    root?.render(
      <ThemeProvider theme={createSmTheme(mode)}>
        <MemoryRouter>
          <Gallery />
        </MemoryRouter>
      </ThemeProvider>,
    );
  });
  return container;
}

const modes = ['light', 'dark'] as const;

describe('Gallery a11y', () => {
  // A single axe() pass over every Phase 1 primitive at once is the
  // heaviest tree in the suite — `Gallery.test.tsx`'s own render-only
  // case already needs an explicit 20s timeout; this needs more headroom
  // still (same reasoning `vitest.config.ts`'s `a11y` project's own
  // `testTimeout` records).
  it.each(modes)('the assembled gallery has no violations in %s mode', { timeout: 60_000 }, async (mode) => {
    const el = render(mode);
    expect(await axe(el)).toHaveNoViolations();
  });
});
