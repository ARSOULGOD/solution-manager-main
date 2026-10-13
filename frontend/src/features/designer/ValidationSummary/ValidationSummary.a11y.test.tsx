import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ViewField, ViewSection } from '@contracts/view-model/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { ValidationSummary } from './ValidationSummary';

function field(overrides: Partial<ViewField> = {}): ViewField {
  return {
    key: overrides.key ?? 'a',
    label: overrides.label ?? 'A',
    renderedPath: 'component.a',
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

function section(overrides: Partial<ViewSection> = {}): ViewSection {
  return { title: 'General', order: 0, fields: [field()], actions: [], ...overrides };
}

const modes = ['light', 'dark'] as const;

describe('ValidationSummary a11y', () => {
  it.each(modes)('has no violations in %s mode: blocking, warning-only, and mixed summaries', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <ValidationSummary
          sections={[
            section({
              title: 'Broker',
              fields: [field({ key: 'host', label: 'Host', validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }] })],
            }),
          ]}
          onJump={() => {}}
        />
        <ValidationSummary
          sections={[
            section({
              title: 'Sizing',
              fields: [
                field({
                  key: 'max-size',
                  label: 'Max entries',
                  validations: [{ tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'y' }],
                }),
              ],
            }),
          ]}
          onJump={() => {}}
        />
        <ValidationSummary
          sections={[
            section({
              title: 'Broker',
              fields: [
                field({ key: 'host', label: 'Host', validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }] }),
                field({
                  key: 'ttl',
                  label: 'TTL',
                  validations: [{ tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'y' }],
                }),
              ],
            }),
          ]}
          onJump={() => {}}
        />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
