import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { Checkbox } from './Checkbox';

describe('Checkbox', () => {
  it('announces its label and checked state', () => {
    renderWithProviders(<Checkbox label="Select all replicas" checked onChange={() => {}} />);

    expect(screen.getByRole('checkbox', { name: 'Select all replicas' })).toBeChecked();
  });

  it('exposes the mixed state as aria-checked="mixed"', () => {
    renderWithProviders(
      <Checkbox label="Select all replicas" checked="mixed" onChange={() => {}} />,
    );

    const box = screen.getByRole('checkbox', { name: 'Select all replicas' });
    expect(box.getAttribute('aria-checked')).toBe('mixed');
    expect(box).not.toBeChecked();
  });

  it('reports true when toggled from mixed', () => {
    const onChange = vi.fn();
    renderWithProviders(
      <Checkbox label="Select all replicas" checked="mixed" onChange={onChange} />,
    );

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select all replicas' }));

    expect(onChange).toHaveBeenCalledWith(true);
  });

  it('wires helper text to the control via aria-describedby', () => {
    renderWithProviders(
      <Checkbox
        label="Select all replicas"
        checked={false}
        onChange={() => {}}
        helperText="Applies the action to every replica in this instance."
      />,
    );

    const box = screen.getByRole('checkbox', { name: 'Select all replicas' });
    const describedBy = box.getAttribute('aria-describedby');
    expect(describedBy).not.toBeNull();
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe(
      'Applies the action to every replica in this instance.',
    );
  });
});
