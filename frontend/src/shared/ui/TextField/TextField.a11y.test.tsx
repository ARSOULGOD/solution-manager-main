import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { TextField } from './TextField';

const modes = ['light', 'dark'] as const;

describe('TextField a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <TextField label="Instance name" value="acme-01" onChange={() => {}} />
        <TextField
          label="Description"
          value=""
          onChange={() => {}}
          helperText="Optional."
          required
        />
        <TextField
          label="Instance name"
          value="acme"
          onChange={() => {}}
          error="Name is already in use."
        />
        <TextField label="Password" value="" onChange={() => {}} reveal />
        <TextField label="Disabled" value="" onChange={() => {}} disabled />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
