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

  it('a blocking validation sets aria-invalid on the control, referenced by aria-describedby, without duplicating the message', () => {
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

    // A control kind that renders `error` itself (every kind but `secret`
    // and `yaml`) must show the message exactly once — the control's own
    // inline error text, never repeated in the validation-list row below.
    expect(screen.getAllByText('This field is required.').length).toBe(1);
  });

  it('a blocking validation on a `secret` field shows the message exactly once, the same as `text` (`secret` now renders through the same `TextField`)', () => {
    renderWithProviders(
      <FieldRow
        field={textField({
          key: 'password',
          label: 'Password',
          controlKind: 'secret',
          masked: true,
          value: { kind: 'secretUnset' },
          validations: [{ tier: 'bind', code: 'REQUIRED', message: 'This field is required.' }],
        })}
        value={null}
        onChange={() => {}}
      />,
    );

    const input = screen.getByLabelText<HTMLInputElement>(/Password/);
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(screen.getAllByText('This field is required.').length).toBe(1);
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

  it('renders a secret field through SecretField as a plain masked input carrying the current draft value', () => {
    const onChange = vi.fn();
    renderWithProviders(
      <FieldRow
        field={textField({ controlKind: 'secret', masked: true, value: { kind: 'secretUnset' } })}
        value={null}
        onChange={onChange}
      />,
    );

    const input = screen.getByLabelText<HTMLInputElement>('Namespace');
    expect(input.getAttribute('type')).toBe('password');
    expect(input.value).toBe('');

    fireEvent.change(input, { target: { value: 'super-secret' } });
    expect(onChange).toHaveBeenCalledWith('super-secret');
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

describe('FieldRow select option labels (connection selectors)', () => {
  // The engine's `ViewField.options` are catalog IDS (what the draft stores and enum validation
  // compares against), so a connection selector would show "conn-7" unless the caller hands the
  // row an id -> name lookup. The lookup changes only what is DISPLAYED, never what is stored.
  const selector = () =>
    textField({
      key: 'solaceBroker',
      label: 'Broker',
      controlKind: 'select',
      options: ['conn-7', 'conn-8'],
    });
  const LABELS = { 'conn-7': 'prod-broker-01', 'conn-8': 'stage-broker-01' };

  it('shows each option by its label, not its id', () => {
    renderWithProviders(
      <FieldRow field={selector()} value="conn-7" onChange={() => {}} optionLabels={LABELS} />,
    );

    const select = screen.getByLabelText('Broker');
    expect(select).toHaveTextContent('prod-broker-01');
    expect(select).not.toHaveTextContent('conn-7');
    fireEvent.mouseDown(select);
    expect(screen.getByRole('option', { name: 'stage-broker-01' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'conn-8' })).not.toBeInTheDocument();
  });

  it('still reports the ID when an option is chosen', () => {
    const onChange = vi.fn();
    renderWithProviders(
      <FieldRow field={selector()} value="conn-7" onChange={onChange} optionLabels={LABELS} />,
    );

    fireEvent.mouseDown(screen.getByLabelText('Broker'));
    fireEvent.click(screen.getByRole('option', { name: 'stage-broker-01' }));
    expect(onChange).toHaveBeenCalledWith('conn-8');
  });

  it('falls back to the id for an option the lookup does not know', () => {
    renderWithProviders(
      <FieldRow
        field={selector()}
        value="conn-8"
        onChange={() => {}}
        optionLabels={{ 'conn-7': 'prod-broker-01' }}
      />,
    );
    expect(screen.getByLabelText('Broker')).toHaveTextContent('conn-8');
  });
});

describe('FieldRow connection provenance and override (UI-D28)', () => {
  function boundField(key: string, source: 'connection' | 'override'): ViewField {
    return textField({
      key,
      label: key === 'solace.host' ? 'Host' : 'Port',
      renderedPath: `cache-engine.${key}`,
      value: {
        kind: 'value',
        value: key === 'solace.host' ? 'tcps://broker.acme.internal' : 55556,
        source,
      },
    });
  }

  function boundSecret(key: string): ViewField {
    return textField({
      key,
      label: 'Password',
      controlKind: 'secret',
      masked: true,
      value: { kind: 'secretUnset' },
    });
  }

  function plainField(key: string): ViewField {
    return textField({ key, label: 'Name', value: { kind: 'value', value: 'acme', source: 'draft' } });
  }

  it('shows the source connection and an Override action on a bound field', () => {
    const onOverride = vi.fn();
    renderWithProviders(
      <FieldRow
        field={boundField('solace.host', 'connection')}
        value="tcps://broker.acme.internal"
        onChange={() => {}}
        connectionLabel="prod-broker-01"
        onOverride={onOverride}
      />,
    );

    expect(screen.getByText('from prod-broker-01')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Host' })).toHaveAttribute('readonly');
    expect(screen.getByRole('button', { name: 'Override' })).toBeInTheDocument();
  });

  it('shows the overridden chip and a revert action once detached', () => {
    const onRevert = vi.fn();
    renderWithProviders(
      <FieldRow
        field={boundField('solace.port', 'override')}
        value={55556}
        onChange={() => {}}
        connectionLabel="prod-broker-01"
        onRevert={onRevert}
      />,
    );

    expect(screen.getByText('overridden · was prod-broker-01')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Revert to connection' })).toBeInTheDocument();
  });

  it('offers Replace rather than Override on a bound secret (I6)', () => {
    const onOverride = vi.fn();
    renderWithProviders(
      <FieldRow
        field={boundSecret('solace.password')}
        value={null}
        onChange={() => {}}
        connectionLabel="prod-broker-01"
        onOverride={onOverride}
      />,
    );

    expect(screen.getByRole('button', { name: 'Replace' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Override' })).not.toBeInTheDocument();
  });

  it('adds no chip to an ordinary unbound field', () => {
    renderWithProviders(
      <FieldRow field={plainField('cache.name')} value="acme" onChange={() => {}} />,
    );

    expect(screen.queryByText(/from |overridden/)).not.toBeInTheDocument();
  });

  it('clicking Override calls onOverride, clicking Revert calls onRevert', () => {
    const onOverride = vi.fn();
    renderWithProviders(
      <FieldRow
        field={boundField('solace.host', 'connection')}
        value="tcps://broker.acme.internal"
        onChange={() => {}}
        connectionLabel="prod-broker-01"
        onOverride={onOverride}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Override' }));
    expect(onOverride).toHaveBeenCalledTimes(1);

    const onRevert = vi.fn();
    renderWithProviders(
      <FieldRow
        field={boundField('solace.port', 'override')}
        value={55556}
        onChange={() => {}}
        connectionLabel="prod-broker-01"
        onRevert={onRevert}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Revert to connection' }));
    expect(onRevert).toHaveBeenCalledTimes(1);
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
