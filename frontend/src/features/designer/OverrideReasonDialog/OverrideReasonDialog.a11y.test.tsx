import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ValidationEntry } from '../ValidationSummary/collectValidationEntries';

import { OverrideReasonDialog } from './OverrideReasonDialog';

const WARNINGS: readonly ValidationEntry[] = [
  {
    sectionIndex: 0,
    fieldKey: 'cacheSizeMb',
    label: 'Cache size (MB)',
    message: 'Above the range Solace recommends (1–1000000).',
  },
  {
    sectionIndex: 0,
    fieldKey: 'evictionPattern',
    label: 'Eviction key pattern',
    message: "Doesn't match the pattern Solace recommends.",
  },
];

const modes = ['light', 'dark'] as const;

describe('OverrideReasonDialog a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <OverrideReasonDialog
        open
        warnings={WARNINGS}
        onCancel={() => undefined}
        onConfirm={() => undefined}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
