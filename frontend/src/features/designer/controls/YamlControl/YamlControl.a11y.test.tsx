import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../../test/renderWithProviders';

import { YamlControl } from './YamlControl';

const modes = ['light', 'dark'] as const;

describe('YamlControl a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <YamlControl label="Advanced config" />
        <YamlControl label="Advanced config" helperText="Contact your administrator for changes." />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
