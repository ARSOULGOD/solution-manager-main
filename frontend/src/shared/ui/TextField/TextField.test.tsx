import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { TextField } from './TextField';

function Controlled(props: Partial<Parameters<typeof TextField>[0]> = {}) {
  return <TextField label="Instance name" value="acme-01" onChange={() => {}} {...props} />;
}

describe('TextField', () => {
  it('announces its label and current value to a screen reader', () => {
    renderWithProviders(<Controlled />);

    const input = screen.getByLabelText<HTMLInputElement>('Instance name');
    expect(input.value).toBe('acme-01');
  });

  it('fires onChange when the value changes', () => {
    const onChange = vi.fn();
    renderWithProviders(<Controlled value="" onChange={onChange} />);

    fireEvent.change(screen.getByLabelText('Instance name'), { target: { value: 'x' } });

    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it('shows helper text and wires it via aria-describedby', () => {
    renderWithProviders(<Controlled helperText="Lowercase, hyphen-separated." />);

    const input = screen.getByLabelText('Instance name');
    const describedBy = input.getAttribute('aria-describedby');
    expect(describedBy).not.toBeNull();
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe(
      'Lowercase, hyphen-separated.',
    );
  });

  it('swaps helper text for the error message and marks the field invalid', () => {
    renderWithProviders(
      <Controlled helperText="Lowercase, hyphen-separated." error="Name is already in use." />,
    );

    const input = screen.getByLabelText('Instance name');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    const describedBy = input.getAttribute('aria-describedby');
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe('Name is already in use.');
  });

  it('marks a required field for assistive technology', () => {
    renderWithProviders(<Controlled required />);

    // `getByLabelText` would include the (aria-hidden) "*" marker's text in
    // its exact-match lookup; `getByRole`'s accessible-name computation
    // correctly excludes it, matching what a screen reader announces.
    const input = screen.getByRole('textbox', { name: 'Instance name' });
    expect(input.getAttribute('required')).not.toBeNull();
  });

  it('masks the value until the reveal toggle is used', () => {
    renderWithProviders(<Controlled label="Password" value="s3cr3t" reveal />);

    const input = screen.getByLabelText<HTMLInputElement>('Password');
    expect(input.type).toBe('password');

    fireEvent.click(screen.getByRole('button', { name: 'Show password' }));
    expect(input.type).toBe('text');

    fireEvent.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(input.type).toBe('password');
  });

  it('renders no reveal toggle when reveal is not requested', () => {
    renderWithProviders(<Controlled />);

    expect(screen.queryByRole('button', { name: 'Show password' })).toBeNull();
  });
});
