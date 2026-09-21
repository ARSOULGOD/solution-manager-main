import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { GroupByControl } from './GroupByControl';

const modes = ['light', 'dark'] as const;

describe('GroupByControl a11y', () => {
  it.each(modes)('has no violations with no label keys (caption shown), in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <GroupByControl value={[]} onChange={() => {}} labelKeys={[]} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations with label keys populated, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <GroupByControl value={['region']} onChange={() => {}} labelKeys={['region', 'org']} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it('has no violations while disabled (gate-locked)', async () => {
    const { container } = renderWithProviders(
      <GroupByControl value={[]} onChange={() => {}} labelKeys={['region', 'org']} disabled />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
