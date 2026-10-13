import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { SidebarNav } from './SidebarNav';

const modes = ['light', 'dark'] as const;
const SOLUTIONS = [{ id: 'cache', name: 'Cache', instanceCount: 2, icon: 'bolt' }];

describe('SidebarNav a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<SidebarNav solutions={SOLUTIONS} />, {
      mode,
      route: '/',
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  // The rail is where accessible naming is easiest to lose: the visible
  // label leaves the layout, so every link is named only by its own
  // `aria-label`.
  it.each(modes)('has no violations collapsed to a rail in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <SidebarNav solutions={SOLUTIONS} collapsed onToggleCollapsed={() => {}} />,
      { mode, route: '/solutions/cache/instances' },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
