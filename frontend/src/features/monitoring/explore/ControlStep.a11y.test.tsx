import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { ControlStep } from './ControlStep';

const modes = ['light', 'dark'] as const;

describe('ControlStep a11y', () => {
  it.each(modes)('has no violations unlocked, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <ControlStep step={1} title="Metric" subtitle="single-select">
        <div>metric body</div>
      </ControlStep>,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations locked, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <ControlStep step={2} title="Instances" locked lockedReason="Pick a metric first.">
        <div>instances body</div>
      </ControlStep>,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
