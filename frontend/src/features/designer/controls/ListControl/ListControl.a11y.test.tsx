import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../../test/renderWithProviders';

import { ListControl } from './ListControl';

const modes = ['light', 'dark'] as const;

describe('ListControl a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <ListControl label="Allowed hosts" values={['a.example', 'b.example']} onChange={() => {}} />
        <ListControl label="Empty list" values={[]} onChange={() => {}} />
        <ListControl label="Disabled list" values={['a.example']} onChange={() => {}} disabled />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
