import { ThemeProvider } from '@mui/material';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';

import { useUiStore } from '@shared/state/uiStore';
import { createSmTheme } from '@shared/theme';

import Gallery from './Gallery';

// React 18+ requires this flag for `act` to work outside a test-library
// wrapper (this file predates `renderWithProviders`, task 008, and stays
// on plain react-dom, same as src/app/router.test.tsx).
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

function render(): HTMLDivElement {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  act(() => {
    root?.render(
      <ThemeProvider theme={createSmTheme('light')}>
        <MemoryRouter>
          <Gallery />
        </MemoryRouter>
      </ThemeProvider>,
    );
  });
  return container;
}

describe('Gallery', () => {
  it('renders each registered Phase 1 component as its own section', { timeout: 20_000 }, () => {
    const el = render();

    expect(el.textContent).toContain('Component gallery');
    expect(el.querySelector('h2')?.textContent).toBe('Button');
  });

  it('toggles the colour mode via the store when the switch button is used', () => {
    const el = render();

    expect(useUiStore.getState().mode).toBe('light');
    expect(el.textContent).toContain('Current theme: light');

    const button = el.querySelector('button');
    expect(button).not.toBeNull();
    act(() => {
      button?.click();
    });

    expect(useUiStore.getState().mode).toBe('dark');
    expect(el.textContent).toContain('Current theme: dark');
  });
});
