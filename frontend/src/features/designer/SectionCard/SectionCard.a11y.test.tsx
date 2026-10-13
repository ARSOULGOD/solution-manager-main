import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ViewField, ViewSection } from '@contracts/view-model/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { SectionCard } from './SectionCard';

function field(overrides: Partial<ViewField> = {}): ViewField {
  return {
    key: overrides.key ?? 'namespace',
    label: overrides.label ?? 'Namespace',
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

function section(overrides: Partial<ViewSection> = {}): ViewSection {
  return {
    title: 'General',
    order: 0,
    fields: [field()],
    actions: [],
    ...overrides,
  };
}

const modes = ['light', 'dark'] as const;

describe('SectionCard a11y', () => {
  it.each(modes)(
    'has no violations in %s mode: collapsed, expanded, empty, all-advanced and blocked states',
    async (mode) => {
      const { container } = renderWithProviders(
        <div>
          <SectionCard section={section()} values={{}} onFieldChange={() => {}} expanded={false} onToggleExpanded={() => {}} />
          <SectionCard
            section={section({
              fields: [field({ key: 'a' }), field({ key: 'b', label: 'B', advanced: true })],
            })}
            values={{}}
            onFieldChange={() => {}}
            expanded={true}
            onToggleExpanded={() => {}}
          />
          <SectionCard
            section={section({ fields: [field({ visible: false })] })}
            values={{}}
            onFieldChange={() => {}}
            expanded={true}
            onToggleExpanded={() => {}}
          />
          <SectionCard
            section={section({ fields: [field({ advanced: true })] })}
            values={{}}
            onFieldChange={() => {}}
            expanded={true}
            onToggleExpanded={() => {}}
          />
          <SectionCard
            section={section({
              fields: [field({ validations: [{ tier: 'bind', code: 'REQUIRED', message: 'Required.' }] })],
            })}
            values={{}}
            onFieldChange={() => {}}
            expanded={true}
            onToggleExpanded={() => {}}
          />
        </div>,
        { mode },
      );

      expect(await axe(container)).toHaveNoViolations();
    },
  );
});
