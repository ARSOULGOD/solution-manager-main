import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { HighConsequenceDialog } from './index';

const modes = ['light', 'dark'] as const;

describe('Dialog a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <HighConsequenceDialog
        open
        title="Restart all"
        consequence="outage"
        confirmLabel="Restart all"
        onConfirm={() => undefined}
        onCancel={() => undefined}
      >
        Every replica will restart.
      </HighConsequenceDialog>,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
