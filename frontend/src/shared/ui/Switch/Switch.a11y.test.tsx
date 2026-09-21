import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { Switch } from './Switch';

const modes = ['light', 'dark'] as const;

describe('Switch a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <Switch label="Auto-restart" checked onChange={() => {}} />
        <Switch
          label="Auto-restart"
          checked={false}
          onChange={() => {}}
          helperText="Restarts a not-responding replica automatically."
        />
        <Switch
          label="Auto-restart"
          checked
          onChange={() => {}}
          error="Cannot be enabled without a health check configured."
        />
        <Switch label="Disabled" checked={false} onChange={() => {}} disabled />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
