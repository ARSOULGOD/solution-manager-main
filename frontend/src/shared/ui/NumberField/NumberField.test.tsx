import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { NumberField } from './NumberField';

describe('NumberField', () => {
  it('renders a numeric input with inputMode="numeric"', () => {
    renderWithProviders(<NumberField label="Replica count" value={2} onChange={() => {}} />);

    const input = screen.getByLabelText<HTMLInputElement>('Replica count');
    expect(input.type).toBe('number');
    expect(input.getAttribute('inputmode')).toBe('numeric');
    expect(input.value).toBe('2');
  });

  it('reports a number when the value changes', () => {
    const onChange = vi.fn();
    renderWithProviders(<NumberField label="Replica count" value={2} onChange={onChange} />);

    fireEvent.change(screen.getByLabelText('Replica count'), { target: { value: '4' } });

    expect(onChange).toHaveBeenCalledWith(4);
  });

  it('reports an empty string rather than coercing a cleared field to zero', () => {
    const onChange = vi.fn();
    renderWithProviders(<NumberField label="Replica count" value={2} onChange={onChange} />);

    fireEvent.change(screen.getByLabelText('Replica count'), { target: { value: '' } });

    expect(onChange).toHaveBeenCalledWith('');
  });

  it('swaps helper text for the error message', () => {
    renderWithProviders(
      <NumberField
        label="Replica count"
        value={0}
        onChange={() => {}}
        helperText="Between 1 and 8."
        error="Must be at least 1."
      />,
    );

    const input = screen.getByLabelText<HTMLInputElement>('Replica count');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    const describedBy = input.getAttribute('aria-describedby');
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe('Must be at least 1.');
  });
});
