import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { NumberField } from './NumberField';

const modes = ['light', 'dark'] as const;

describe('NumberField a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <NumberField label="Replica count" value={2} onChange={() => {}} />
        <NumberField label="Required" value="" onChange={() => {}} required />
        <NumberField
          label="Replica count"
          value={0}
          onChange={() => {}}
          error="Must be at least 1."
        />
        <NumberField label="Disabled" value={1} onChange={() => {}} disabled />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
