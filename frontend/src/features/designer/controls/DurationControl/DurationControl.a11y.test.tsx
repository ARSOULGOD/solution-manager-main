import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../../test/renderWithProviders';

import { DurationControl } from './DurationControl';

const modes = ['light', 'dark'] as const;

describe('DurationControl a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <DurationControl label="Poll interval" value="120m" onChange={() => {}} />
        <DurationControl
          label="Timeout"
          value=""
          onChange={() => {}}
          error="Not a valid duration."
        />
        <DurationControl label="Disabled" value="" onChange={() => {}} disabled />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
