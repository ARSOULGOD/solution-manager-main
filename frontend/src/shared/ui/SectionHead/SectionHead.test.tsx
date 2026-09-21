import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { SectionHead } from './SectionHead';

describe('SectionHead', () => {
  it('renders a title and caption', () => {
    renderWithProviders(<SectionHead title="Replicas" caption="3 of 3 reporting" />);
    expect(screen.getByRole('heading', { name: 'Replicas' })).not.toBeNull();
    expect(screen.getByText('3 of 3 reporting')).not.toBeNull();
  });
});
