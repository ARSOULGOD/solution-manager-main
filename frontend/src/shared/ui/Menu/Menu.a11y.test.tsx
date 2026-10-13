import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import { Button } from '../Button';

import { Menu } from './Menu';

const modes = ['light', 'dark'] as const;

describe('Menu a11y', () => {
  it.each(modes)('has no violations in %s mode when open', async (mode) => {
    const { container } = renderWithProviders(
      <Menu
        trigger={<Button>More</Button>}
        entries={[
          { kind: 'caption', label: 'Capabilities' },
          { label: 'Edit' },
          { label: 'Delete', danger: true },
        ]}
      />,
      { mode },
    );
    screen.getByRole('button', { name: 'More' }).click();
    expect(await axe(container)).toHaveNoViolations();
  });
});
