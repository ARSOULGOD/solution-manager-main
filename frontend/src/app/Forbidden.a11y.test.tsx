import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../test/renderWithProviders';

import { Forbidden } from './Forbidden';

const modes = ['light', 'dark'] as const;

describe('Forbidden a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<Forbidden correlationId="corr-403-a1b2" />, {
      mode,
    });

    expect(await axe(container)).toHaveNoViolations();
  });
});
