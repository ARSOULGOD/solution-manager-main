import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { EnvChip } from './EnvChip';

const modes = ['light', 'dark'] as const;

describe('EnvChip a11y', () => {
  it.each(modes)('has no violations in %s mode across every env', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <EnvChip env="DEV" />
        <EnvChip env="TEST" />
        <EnvChip env="PROD" />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
