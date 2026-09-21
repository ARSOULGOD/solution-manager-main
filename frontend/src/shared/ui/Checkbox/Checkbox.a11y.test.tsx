import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { Checkbox } from './Checkbox';

const modes = ['light', 'dark'] as const;

describe('Checkbox a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <Checkbox label="Select all replicas" checked onChange={() => {}} />
        <Checkbox label="Select all replicas" checked="mixed" onChange={() => {}} />
        <Checkbox
          label="Select all replicas"
          checked={false}
          onChange={() => {}}
          helperText="Applies the action to every replica in this instance."
        />
        <Checkbox label="Disabled" checked={false} onChange={() => {}} disabled />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
