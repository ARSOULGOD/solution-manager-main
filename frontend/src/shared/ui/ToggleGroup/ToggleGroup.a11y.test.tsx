import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { ToggleGroup } from './ToggleGroup';

const modes = ['light', 'dark'] as const;

describe('ToggleGroup a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <ToggleGroup
        aria-label="Environment"
        value="all"
        onChange={() => undefined}
        options={[
          { value: 'all', label: 'All' },
          { value: 'dev', label: 'DEV' },
        ]}
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
