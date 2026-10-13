import { describe, expect, it } from 'vitest';

import { fireEvent, renderWithProviders, screen } from '../../../test/renderWithProviders';

import { SecretField } from './SecretField';

describe('SecretField', () => {
  it('renders a labelled, masked input with a show/hide toggle — no separate set/replace/clear step', () => {
    renderWithProviders(<SecretField label="Solace password" value="" onChange={() => undefined} />);

    const input = screen.getByLabelText<HTMLInputElement>('Solace password');
    expect(input.getAttribute('type')).toBe('password');
    expect(screen.getByLabelText(/show password/i)).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Set' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull();
  });

  it('emits the typed value directly on change', () => {
    const values: string[] = [];
    renderWithProviders(
      <SecretField
        label="Solace password"
        value=""
        onChange={(event) => values.push(event.target.value)}
      />,
    );

    fireEvent.change(screen.getByLabelText('Solace password'), { target: { value: 'super-secret' } });

    expect(values).toEqual(['super-secret']);
  });

  it('reveals the typed value on toggle click, same as any other password field', () => {
    renderWithProviders(<SecretField label="Solace password" value="super-secret" onChange={() => undefined} />);

    const input = screen.getByLabelText<HTMLInputElement>('Solace password');
    expect(input.getAttribute('type')).toBe('password');

    fireEvent.click(screen.getByLabelText(/show password/i));
    expect(input.getAttribute('type')).toBe('text');
  });
});
