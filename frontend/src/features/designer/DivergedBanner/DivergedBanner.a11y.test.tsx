import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { DivergedBanner } from './DivergedBanner';

const modes = ['light', 'dark'] as const;

describe('DivergedBanner a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <DivergedBanner
        onRestage={() => undefined}
        onRestart={() => undefined}
        onRollback={() => undefined}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
