import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { Tooltip } from './Tooltip';

const modes = ['light', 'dark'] as const;

describe('Tooltip a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <Tooltip title="Explains the state">
        <span>Trigger</span>
      </Tooltip>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
