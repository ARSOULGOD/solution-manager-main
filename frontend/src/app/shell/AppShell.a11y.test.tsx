import { axe } from 'jest-axe';
import { Route, Routes } from 'react-router';
import { describe, expect, it } from 'vitest';

import { reflowFloorPx } from '@shared/theme/breakpoints';

import { setViewportWidth } from '../../test/matchMedia';
import { fireEvent, renderWithProviders, screen, waitFor } from '../../test/renderWithProviders';

import { AppShell } from './AppShell';

const modes = ['light', 'dark'] as const;

describe('AppShell a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <Routes>
        <Route path="/" element={<AppShell />}>
          <Route index element={<h1>Home</h1>} />
        </Route>
      </Routes>,
      { mode },
    );

    // Wait for the DEV mock identity to resolve so the account menu's
    // avatar (an icon-only trigger) has settled before axe inspects it.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Account' })).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });
});

// Task 106, `FE-D29`: the four widths its own Testing line names, in both
// themes — the shell's chrome composition changes at each (permanent vs
// overlay drawer at `lg`; organization/theme/support collapse at `md`), so a
// single-width a11y pass would miss violations specific to a narrower
// branch (e.g. the collapsed app bar's menu button, or the "…" breadcrumb
// trigger).
const widths = [320, 600, 905, 1240] as const;
const combinations = widths.flatMap((width) => modes.map((mode) => [width, mode] as const));

describe('AppShell responsive a11y (task 106, FE-D29)', () => {
  it.each(combinations)('has no violations at %ipx in %s mode', async (width, mode) => {
    setViewportWidth(width);
    const { container } = renderWithProviders(
      <Routes>
        <Route path="/" element={<AppShell />}>
          <Route index element={<h1>Home</h1>} />
        </Route>
      </Routes>,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Account' })).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it(`has no violations with the overlay drawer open at ${String(reflowFloorPx)}px`, async () => {
    setViewportWidth(reflowFloorPx);
    const { container } = renderWithProviders(
      <Routes>
        <Route path="/" element={<AppShell />}>
          <Route index element={<h1>Home</h1>} />
        </Route>
      </Routes>,
    );

    const menuButton = await screen.findByRole('button', { name: 'Open navigation' });
    fireEvent.click(menuButton);
    await screen.findByRole('navigation', { name: 'Solution Manager' });

    expect(await axe(container)).toHaveNoViolations();
  });
});
