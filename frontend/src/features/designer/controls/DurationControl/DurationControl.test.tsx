import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../../test/renderWithProviders';

import { DurationControl } from './DurationControl';

describe('DurationControl', () => {
  it('renders the canonical-unit example text as its helper text', () => {
    renderWithProviders(<DurationControl label="Poll interval" value="120m" onChange={() => {}} />);

    const input = screen.getByLabelText<HTMLInputElement>('Poll interval');
    expect(input.value).toBe('120m');
    const describedBy = input.getAttribute('aria-describedby');
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe(
      'Spring Duration · 120m · PT2H',
    );
  });

  it('appends the canonical example after a package-supplied helper text', () => {
    renderWithProviders(
      <DurationControl
        label="Poll interval"
        value=""
        onChange={() => {}}
        helperText="How often the cache is polled."
      />,
    );

    const input = screen.getByLabelText('Poll interval');
    const describedBy = input.getAttribute('aria-describedby');
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe(
      'How often the cache is polled. — Spring Duration · 120m · PT2H',
    );
  });

  it('fires onChange with the raw text value', () => {
    const onChange = vi.fn();
    renderWithProviders(<DurationControl label="Poll interval" value="" onChange={onChange} />);

    fireEvent.change(screen.getByLabelText('Poll interval'), { target: { value: 'PT2H' } });

    expect(onChange).toHaveBeenCalledWith('PT2H');
  });

  it('swaps the helper text for an error message and marks the field invalid', () => {
    renderWithProviders(
      <DurationControl label="Poll interval" value="bogus" onChange={() => {}} error="Not a valid duration." />,
    );

    const input = screen.getByLabelText('Poll interval');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    const describedBy = input.getAttribute('aria-describedby');
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe('Not a valid duration.');
  });
});
