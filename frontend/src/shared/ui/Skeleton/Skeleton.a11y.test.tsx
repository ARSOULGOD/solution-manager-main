import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { Skeleton } from './Skeleton';

const modes = ['light', 'dark'] as const;

describe('Skeleton a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<Skeleton width={240} height={48} />, { mode });
    expect(await axe(container)).toHaveNoViolations();
  });
});
