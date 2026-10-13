import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { Autocomplete } from './Autocomplete';

interface Org {
  id: string;
  name: string;
}

const orgs: Org[] = [
  { id: 'acme', name: 'Acme' },
  { id: 'globex', name: 'Globex' },
];

const modes = ['light', 'dark'] as const;

describe('Autocomplete a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <Autocomplete<Org>
          label="Organisation"
          value={orgs[0] ?? null}
          onChange={() => {}}
          options={orgs}
          getOptionLabel={(org) => org.name}
        />
        <Autocomplete<Org>
          label="Organisation"
          value={null}
          onChange={() => {}}
          options={orgs}
          getOptionLabel={(org) => org.name}
          error="Organisation is required."
        />
        <Autocomplete<Org>
          label="Disabled"
          value={null}
          onChange={() => {}}
          options={orgs}
          getOptionLabel={(org) => org.name}
          disabled
        />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
