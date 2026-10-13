import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { SectionHead } from './SectionHead';

const modes = ['light', 'dark'] as const;

describe('SectionHead a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <SectionHead title="Replicas" caption="3 of 3 reporting" />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
