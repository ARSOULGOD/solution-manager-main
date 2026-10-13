import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { LayerBadge } from './LayerBadge';

describe('LayerBadge', () => {
  it('renders "package" for product-default', () => {
    renderWithProviders(<LayerBadge layer="product-default" />);
    expect(screen.getByText('package')).toBeInTheDocument();
  });

  it('renders "shared" for shared', () => {
    renderWithProviders(<LayerBadge layer="shared" />);
    expect(screen.getByText('shared')).toBeInTheDocument();
  });
});
