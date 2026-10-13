import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { Breadcrumbs } from './Breadcrumbs';

const modes = ['light', 'dark'] as const;

describe('Breadcrumbs a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<Breadcrumbs />, {
      mode,
      route: '/solutions',
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});
