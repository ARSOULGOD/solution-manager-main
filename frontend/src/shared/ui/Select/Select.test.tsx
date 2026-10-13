import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { Select } from './Select';

const options = [
  { value: 'docker', label: 'Docker' },
  { value: 'k8s', label: 'Kubernetes' },
  { value: 'systemd', label: 'systemd · VM' },
];

describe('Select', () => {
  it('announces its label and current value', () => {
    renderWithProviders(
      <Select label="Driver" value="docker" onChange={() => {}} options={options} />,
    );

    expect(screen.getByLabelText('Driver')).toHaveTextContent('Docker');
  });

  it('reports the new value when an option is chosen', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(
      <Select label="Driver" value="docker" onChange={onChange} options={options} />,
    );

    await user.click(screen.getByLabelText('Driver'));
    await user.click(await screen.findByRole('option', { name: 'Kubernetes' }));

    expect(onChange).toHaveBeenCalledWith('k8s');
  });

  it('swaps helper text for the error message', () => {
    renderWithProviders(
      <Select
        label="Driver"
        value="docker"
        onChange={() => {}}
        options={options}
        helperText="How this instance is run."
        error="Unsupported driver."
      />,
    );

    const combobox = screen.getByLabelText('Driver');
    expect(combobox.getAttribute('aria-invalid')).toBe('true');
    const describedBy = combobox.getAttribute('aria-describedby');
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe('Unsupported driver.');
  });
});
