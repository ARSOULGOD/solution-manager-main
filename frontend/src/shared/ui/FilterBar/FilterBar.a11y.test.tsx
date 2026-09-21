import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { FilterBar } from './FilterBar';

const modes = ['light', 'dark'] as const;

describe('FilterBar a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <FilterBar resultCount={2} search={{ value: '', onChange: () => undefined }} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
