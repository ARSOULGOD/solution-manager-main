import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { Autocomplete } from './Autocomplete';

interface Org {
  id: string;
  name: string;
}

const orgs: Org[] = [
  { id: 'acme', name: 'Acme' },
  { id: 'globex', name: 'Globex' },
];

describe('Autocomplete', () => {
  it('announces its label and current value', () => {
    renderWithProviders(
      <Autocomplete<Org>
        label="Organisation"
        value={orgs[0] ?? null}
        onChange={() => {}}
        options={orgs}
        getOptionLabel={(org) => org.name}
      />,
    );

    expect(screen.getByLabelText('Organisation')).toHaveValue('Acme');
  });

  it('reports the selected option when one is chosen', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(
      <Autocomplete<Org>
        label="Organisation"
        value={null}
        onChange={onChange}
        options={orgs}
        getOptionLabel={(org) => org.name}
      />,
    );

    await user.click(screen.getByLabelText('Organisation'));
    await user.click(await screen.findByRole('option', { name: 'Globex' }));

    expect(onChange).toHaveBeenCalledWith(orgs[1]);
  });

  it('swaps helper text for the error message', () => {
    renderWithProviders(
      <Autocomplete<Org>
        label="Organisation"
        value={null}
        onChange={() => {}}
        options={orgs}
        getOptionLabel={(org) => org.name}
        helperText="Pick the owning organisation."
        error="Organisation is required."
      />,
    );

    const input = screen.getByLabelText('Organisation');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    const describedBy = input.getAttribute('aria-describedby');
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe(
      'Organisation is required.',
    );
  });
});
