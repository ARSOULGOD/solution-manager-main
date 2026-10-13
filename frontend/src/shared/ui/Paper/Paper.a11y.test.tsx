import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { Paper } from './Paper';

const modes = ['light', 'dark'] as const;

describe('Paper a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <Paper surface="flat">Flat</Paper>
        <Paper surface="padded">Padded</Paper>
        <Paper surface="hoverable">Hoverable</Paper>
      </div>,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
