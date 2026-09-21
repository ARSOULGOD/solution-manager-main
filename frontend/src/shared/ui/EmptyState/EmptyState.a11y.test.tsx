import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { EmptyState } from './EmptyState';

const modes = ['light', 'dark'] as const;

describe('EmptyState a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <EmptyState
        title="No instances declared yet"
        body="Declare an instance, then apply and restart."
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
