import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../test/renderWithProviders';

import { LoginPage } from './LoginPage';

const modes = ['light', 'dark'] as const;

describe('LoginPage a11y', () => {
  it.each(modes)('idle state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<LoginPage idleTimeoutMinutes={30} />, {
      mode,
      route: '/login',
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('session-arrival state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<LoginPage idleTimeoutMinutes={30} />, {
      mode,
      route: '/login?arrival=session',
    });

    expect(await axe(container)).toHaveNoViolations();
  });
});
