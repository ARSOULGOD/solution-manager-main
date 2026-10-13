import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { RouteTabs } from './RouteTabs';

const modes = ['light', 'dark'] as const;

describe('RouteTabs a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <RouteTabs tabs={[{ to: '/', label: 'Home' }]}>Content</RouteTabs>,
      { mode, route: '/' },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
