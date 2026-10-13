import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { LayerBadge } from './LayerBadge';

const modes = ['light', 'dark'] as const;

describe('LayerBadge a11y', () => {
  it.each(modes)('product-default has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<LayerBadge layer="product-default" />, { mode });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('shared has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<LayerBadge layer="shared" />, { mode });
    expect(await axe(container)).toHaveNoViolations();
  });
});
