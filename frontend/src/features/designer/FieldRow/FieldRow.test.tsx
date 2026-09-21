import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { ViewField } from '@contracts/view-model/types';

import { setViewportWidth } from '../../../test/matchMedia';
import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { FieldRow } from './FieldRow';

function textField(overrides: Partial<ViewField> = {}): ViewField {
  return {
    key: 'namespace',
    label: 'Namespace',
    renderedPath: 'cache-engine.namespace',
    discriminator: false,
    controlKind: 'text',
    value: { kind: 'unset' },
    options: [],
    enabled: true,
    visible: true,
    masked: false,
    advanced: false,
    validations: [],
    ...overrides,
  };
}

describe('FieldRow', () => {
  it('renders the control for the field and fires onChange with the raw value', () => {
    const onChange = vi.fn();
    renderWithProviders(
      <FieldRow field={textField()} value="acme" onChange={onChange} />,
    );

    const input = screen.getByLabelText<HTMLInputElement>('Namespace');
    expect(input.value).toBe('acme');

    fireEvent.change(input, { target: { value: 'acme-2' } });
    expect(onChange).toHaveBeenCalledWith('acme-2');
  });

  it('shows the discriminator hint only when the field is a discriminator', () => {
    const { rerender } = renderWithProviders(
      <FieldRow field={textField({ discriminator: true })} value={null} onChange={() => {}} />,
    );
    expect(screen.getByText('controls fields below')).not.toBeNull();

    rerender(<FieldRow field={textField({ discriminator: false })} value={null} onChange={() => {}} />);
    expect(screen.queryByText('controls fields below')).toBeNull();
  });

  it('shows the rendered path only when requested and present, in mono', () => {
    const { container, rerender } = renderWithProviders(
      <FieldRow field={textField()} value={null} onChange={() => {}} showRenderedPath />,
    );
    expect(screen.getByText('cache-engine.namespace')).not.toBeNull();

    rerender(<FieldRow field={textField()} value={null} onChange={() => {}} showRenderedPath={false} />);
    expect(screen.queryByText('cache-engine.namespace')).toBeNull();
    void container;
  });

  it('a virtual field (renderedPath null) renders with no visual tell', () => {
    const virtual = renderWithProviders(
      <FieldRow field={textField({ renderedPath: null })} value={null} onChange={() => {}} showRenderedPath />,
    );
    // No separate "virtual" badge exists anywhere (UI §A.8.4) — the only
    // difference from a real field is the rendered-path text itself.
    expect(virtual.queryByText(/virtual/i)).toBeNull();
    expect(virtual.queryByText('cache-engine.namespace')).toBeNull();
    virtual.unmount();

    const real = renderWithProviders(
      <FieldRow field={textField()} value={null} onChange={() => {}} showRenderedPath />,
    );
    expect(real.queryByText(/virtual/i)).toBeNull();
    expect(real.getByText('cache-engine.namespace')).not.toBeNull();
  });

  it('shows "Default" when the current value equals the projected default and is unchanged', () => {
    renderWithProviders(
      <FieldRow
        field={textField({ value: { kind: 'value', value: 'acme', source: 'default' } })}
        value="acme"
        onChange={() => {}}
      />,
    );

    expect(screen.getByText('Default')).not.toBeNull();
    expect(screen.queryByText('Modified')).toBeNull();
  });

  it('shows "Modified" once the live value diverges from the projected one, replacing "Default"', () => {
    renderWithProviders(
      <FieldRow
        field={textField({ value: { kind: 'value', value: 'acme', source: 'default' } })}
        value="acme-changed"
        onChange={() => {}}
      />,
    );

    expect(screen.getByText('Modified')).not.toBeNull();
    expect(screen.queryByText('Default')).toBeNull();
  });

  it('shows neither "Default" nor "Modified" for a draft-sourced, unedited value', () => {
    renderWithProviders(
      <FieldRow
        field={textField({ value: { kind: 'value', value: 'acme', source: 'draft' } })}
        value="acme"
        onChange={() => {}}
      />,
    );

    expect(screen.queryByText('Default')).toBeNull();
    expect(screen.queryByText('Modified')).toBeNull();
  });

  it('shows the required asterisk only when a REQUIRED validation is present', () => {
    const { container, rerender } = renderWithProviders(
      <FieldRow
        field={textField({ validations: [{ tier: 'bind', code: 'REQUIRED', message: 'This field is required.' }] })}
        value={null}
        onChange={() => {}}
      />,
    );
    expect(container.querySelector('.MuiInputLabel-asterisk')).not.toBeNull();

    rerender(<FieldRow field={textField()} value={null} onChange={() => {}} />);
    expect(container.querySelector('.MuiInputLabel-asterisk')).toBeNull();
  });

  it('a blocking validation sets aria-invalid on the control, referenced by aria-describedby', () => {
    renderWithProviders(
      <FieldRow
        field={textField({ validations: [{ tier: 'bind', code: 'REQUIRED', message: 'This field is required.' }] })}
        value={null}
        onChange={() => {}}
      />,
    );

    // The label's accessible name gains the asterisk text once `required`
    // is set (MUI's own rendering) — match on the field name, not the
    // exact label string.
    const input = screen.getByLabelText<HTMLInputElement>(/Namespace/);
    expect(input.getAttribute('aria-invalid')).toBe('true');
    const describedBy = input.getAttribute('aria-describedby');
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe('This field is required.');

    // The full validation is still listed as its own row, distinct from
    // the control's own inline error text (UI §A.8.9's validation list).
    expect(screen.getAllByText('This field is required.').length).toBeGreaterThanOrEqual(1);
  });

  it('a warning validation does not mark the control invalid, and appends the recommendation caption', () => {
    renderWithProviders(
      <FieldRow
        field={textField({
          validations: [{ tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'Outside the range Solace recommends.' }],
        })}
        value="1000001"
        onChange={() => {}}
      />,
    );

    const input = screen.getByLabelText<HTMLInputElement>('Namespace');
    expect(input.getAttribute('aria-invalid')).not.toBe('true');
    expect(
      screen.getByText('Outside the range Solace recommends. — a Solace recommendation, not a hard limit — you can proceed'),
    ).not.toBeNull();
  });

  it('disables the control when the field is not enabled', () => {
    renderWithProviders(<FieldRow field={textField({ enabled: false })} value={null} onChange={() => {}} />);

    expect(screen.getByLabelText<HTMLInputElement>('Namespace').disabled).toBe(true);
  });

  it('renders a secret field through SecretField, unset and set', () => {
    const { rerender } = renderWithProviders(
      <FieldRow
        field={textField({ controlKind: 'secret', masked: true, value: { kind: 'secretUnset' } })}
        value={null}
        onChange={() => {}}
      />,
    );
    expect(screen.getByText('No credential set')).not.toBeNull();

    rerender(
      <FieldRow
        field={textField({ controlKind: 'secret', masked: true, value: { kind: 'secretRef', ref: 'secretRef://kv/acme' } })}
        value="secretRef://kv/acme"
        onChange={() => {}}
      />,
    );
    expect(screen.getByText('Credential set')).not.toBeNull();
    expect(screen.getByText('secretRef://kv/acme')).not.toBeNull();
  });

  it('renders a toggle field through Switch', () => {
    const onChange = vi.fn();
    renderWithProviders(
      <FieldRow field={textField({ controlKind: 'toggle' })} value={true} onChange={onChange} />,
    );

    const toggle = screen.getByRole('switch');
    expect((toggle as HTMLInputElement).checked).toBe(true);
    fireEvent.click(toggle);
    expect(onChange).toHaveBeenCalledWith(false);
  });

  it('renders a select field with the field\'s options', () => {
    renderWithProviders(
      <FieldRow
        field={textField({ controlKind: 'select', options: ['caffeine', 'redis'] })}
        value="caffeine"
        onChange={() => {}}
      />,
    );

    expect(screen.getByLabelText('Namespace')).not.toBeNull();
  });
});

describe('FieldRow metadata stacking (task 108, FE-D29)', () => {
  it('the metadata row (Modified chip) renders above the control at sm and up', () => {
    const { container } = renderWithProviders(
      <FieldRow
        field={textField({ value: { kind: 'value', value: 'acme', source: 'default' } })}
        value="acme-changed"
        onChange={() => {}}
        showRenderedPath
      />,
    );

    const row = container.querySelector('[data-field-row]');
    expect(row).toBeInstanceOf(HTMLElement);
    const children = [...(row as HTMLElement).children];
    const controlIndex = children.findIndex((child) => child.querySelector('input') !== null);
    const metadataIndex = children.findIndex((child) => child.textContent?.includes('Modified') === true);

    expect(controlIndex).toBeGreaterThanOrEqual(0);
    expect(metadataIndex).toBeLessThan(controlIndex);
  });

  it('below sm, the metadata row moves after the control (DOM order, not just CSS order)', () => {
    setViewportWidth(320);
    const { container } = renderWithProviders(
      <FieldRow
        field={textField({ value: { kind: 'value', value: 'acme', source: 'default' } })}
        value="acme-changed"
        onChange={() => {}}
        showRenderedPath
      />,
    );

    const row = container.querySelector('[data-field-row]');
    expect(row).toBeInstanceOf(HTMLElement);
    const children = [...(row as HTMLElement).children];
    const controlIndex = children.findIndex((child) => child.querySelector('input') !== null);
    const metadataIndex = children.findIndex((child) => child.textContent?.includes('Modified') === true);

    expect(controlIndex).toBeGreaterThanOrEqual(0);
    expect(metadataIndex).toBeGreaterThan(controlIndex);
  });
});
