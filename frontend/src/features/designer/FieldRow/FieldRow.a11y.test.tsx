import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ViewField } from '@contracts/view-model/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

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

const modes = ['light', 'dark'] as const;

describe('FieldRow a11y', () => {
  it.each(modes)('has no violations in %s mode across every controlKind, plus discriminator/required/validation states', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <FieldRow field={textField()} value="acme" onChange={() => {}} showRenderedPath />
        <FieldRow field={textField({ discriminator: true })} value="acme" onChange={() => {}} />
        <FieldRow
          field={textField({ validations: [{ tier: 'bind', code: 'REQUIRED', message: 'This field is required.' }] })}
          value={null}
          onChange={() => {}}
        />
        <FieldRow
          field={textField({
            validations: [{ tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'Outside the recommended range.' }],
          })}
          value="1000001"
          onChange={() => {}}
        />
        <FieldRow field={textField({ enabled: false })} value={null} onChange={() => {}} />
        <FieldRow
          field={textField({ key: 'max-size', label: 'Max entries', controlKind: 'number' })}
          value={5000}
          onChange={() => {}}
        />
        <FieldRow
          field={textField({ key: 'enabled', label: 'Enabled', controlKind: 'toggle' })}
          value={true}
          onChange={() => {}}
        />
        <FieldRow
          field={textField({
            key: 'provider',
            label: 'Provider',
            controlKind: 'select',
            options: ['caffeine', 'redis'],
          })}
          value="caffeine"
          onChange={() => {}}
        />
        <FieldRow
          field={textField({ key: 'ttl', label: 'TTL', controlKind: 'duration' })}
          value="120m"
          onChange={() => {}}
        />
        <FieldRow
          field={textField({
            key: 'password',
            label: 'Password',
            controlKind: 'secret',
            masked: true,
            value: { kind: 'secretUnset' },
          })}
          value={null}
          onChange={() => {}}
        />
        <FieldRow
          field={textField({ key: 'topics', label: 'Topics', controlKind: 'list' })}
          value={['a', 'b']}
          onChange={() => {}}
        />
        <FieldRow
          field={textField({ key: 'mappings', label: 'Mappings', controlKind: 'map' })}
          value={{ a: 'b' }}
          onChange={() => {}}
        />
        <FieldRow
          field={textField({ key: 'rows', label: 'Rows', controlKind: 'table' })}
          value={[]}
          onChange={() => {}}
        />
        <FieldRow
          field={textField({ key: 'raw', label: 'Raw config', controlKind: 'yaml' })}
          value={null}
          onChange={() => {}}
        />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
