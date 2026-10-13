import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ViewField, ViewSection } from '@contracts/view-model/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { SectionNav } from './SectionNav';

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

describe('SectionNav a11y', () => {
  it.each(modes)('has no violations in %s mode: field count, warning dot, and blocking dot entries', async (mode) => {
    const { container } = renderWithProviders(
      <SectionNav
        sections={[
          section({ title: 'General' }),
          section({
            title: 'Sizing',
            fields: [field({ validations: [{ tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'x' }] })],
          }),
          section({
            title: 'Broker',
            fields: [field({ validations: [{ tier: 'bind', code: 'REQUIRED', message: 'y' }] })],
          }),
        ]}
        onJump={() => {}}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
