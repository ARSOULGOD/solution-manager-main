import { describe, expect, it, vi } from 'vitest';

import { fireEvent, renderWithProviders, screen } from '../../../test/renderWithProviders';

import { SecretField } from './SecretField';

describe('SecretField', () => {
  it('never renders a resolved value and has no reveal control', () => {
    renderWithProviders(
      <SecretField
        state={{ kind: 'set', ref: 'secretRef://kv/acme/broker' }}
        onSet={() => undefined}
        onClear={() => undefined}
      />,
    );
    expect(screen.getByText('Credential set')).not.toBeNull();
    expect(screen.getByText('secretRef://kv/acme/broker')).not.toBeNull();
    expect(screen.queryByLabelText(/show password/i)).toBeNull();
    expect(screen.queryByLabelText(/hide password/i)).toBeNull();
  });

  it('emits a set intent, not a displayed value, and clear is explicit', () => {
    const onSet = vi.fn();
    const onClear = vi.fn();
    const { rerender } = renderWithProviders(
      <SecretField state={{ kind: 'unset' }} onSet={onSet} onClear={onClear} />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Set' }));
    const input = screen.getByLabelText('Credential');
    expect(input.getAttribute('type')).toBe('password');
    fireEvent.change(input, { target: { value: 'super-secret' } });
    screen.getByRole('button', { name: 'Save' }).click();
    expect(onSet).toHaveBeenCalledWith('super-secret');

    rerender(
      <SecretField
        state={{ kind: 'set', ref: 'secretRef://kv/acme/broker' }}
        onSet={onSet}
        onClear={onClear}
      />,
    );
    screen.getByRole('button', { name: 'Clear' }).click();
    expect(onClear).toHaveBeenCalledTimes(1);
  });
});
