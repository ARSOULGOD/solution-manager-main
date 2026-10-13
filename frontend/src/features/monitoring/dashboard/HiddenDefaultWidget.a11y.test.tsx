import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { HiddenDefaultWidget } from './HiddenDefaultWidget';

const modes = ['light', 'dark'] as const;

describe('HiddenDefaultWidget a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <HiddenDefaultWidget title="Hit rate" onRestore={() => {}} restorePending={false} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
