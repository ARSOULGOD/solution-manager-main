import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { ResolutionControl } from './ResolutionControl';

const modes = ['light', 'dark'] as const;

describe('ResolutionControl a11y', () => {
  it.each(modes)('has no violations with Raw unavailable, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <ResolutionControl value="auto" onChange={() => {}} forceRawAdvertised={false} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations with Raw available, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <ResolutionControl value="auto" onChange={() => {}} forceRawAdvertised />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it('has no violations while disabled', async () => {
    const { container } = renderWithProviders(
      <ResolutionControl value="auto" onChange={() => {}} forceRawAdvertised disabled />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
