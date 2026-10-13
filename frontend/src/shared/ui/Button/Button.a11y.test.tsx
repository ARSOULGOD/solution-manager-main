import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { Button, IconButton } from './Button';

const modes = ['light', 'dark'] as const;

describe('Button a11y', () => {
  it.each(modes)('has no violations in %s mode across all variants', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <Button variant="contained">Save</Button>
        <Button variant="outlined">Cancel</Button>
        <Button variant="text">Skip</Button>
        <Button variant="danger">Delete</Button>
        <Button variant="dangerContained">Force raw</Button>
        <Button disabled>Disabled</Button>
        <IconButton aria-label="Close">
          <span aria-hidden>×</span>
        </IconButton>
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
