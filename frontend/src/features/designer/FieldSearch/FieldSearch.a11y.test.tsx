import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ViewField } from '@contracts/view-model/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { FieldSearch } from './FieldSearch';
import type { FieldSearchHit } from './useFieldSearch';

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

const modes = ['light', 'dark'] as const;

describe('FieldSearch a11y', () => {
  it.each(modes)('has no violations in %s mode: idle box', async (mode) => {
    const { container } = renderWithProviders(
      <FieldSearch query="" onQueryChange={() => {}} totalFieldCount={1} hits={[]} values={{}} onFieldChange={() => {}} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations in %s mode: hit list', async (mode) => {
    const hits: readonly FieldSearchHit[] = [{ field: field({ key: 'host', label: 'Host' }), sectionTitle: 'Broker' }];
    const { container } = renderWithProviders(
      <FieldSearch query="host" onQueryChange={() => {}} totalFieldCount={1} hits={hits} values={{}} onFieldChange={() => {}} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations in %s mode: empty state', async (mode) => {
    const { container } = renderWithProviders(
      <FieldSearch query="zzz" onQueryChange={() => {}} totalFieldCount={1} hits={[]} values={{}} onFieldChange={() => {}} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
