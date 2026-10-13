import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { SidebarNav } from './SidebarNav';

const SOLUTIONS = [
  { id: 'cache', name: 'Cache', instanceCount: 12, icon: 'bolt' },
  { id: 'file-mesh', name: 'File Mesh', instanceCount: 3, icon: 'files' },
];

function dotOf(name: string): Element {
  const dot = screen.getByRole('link', { name }).querySelector('[data-nav-status-dot]');
  expect(dot).not.toBeNull();
  return dot as Element;
}

describe('SidebarNav', () => {
  it('contains only Home and Solutions in v1, with a non-colour active marker', () => {
    const { container } = renderWithProviders(
      <SidebarNav solutions={[{ id: 'cache', name: 'Cache', instanceCount: 12 }]} />,
      { route: '/' },
    );

    expect(screen.getByRole('link', { name: 'Home' })).not.toBeNull();
    expect(screen.getByRole('link', { name: /Solutions/ })).not.toBeNull();
    expect(screen.queryByRole('link', { name: 'Targets' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Admin' })).toBeNull();

    const home = screen.getByRole('link', { name: 'Home' });
    expect(home.getAttribute('aria-current')).toBe('page');
    expect(home.classList.contains('active')).toBe(true);
    expect(container.querySelector('[data-sidebar-nav]')).not.toBeNull();
  });

  it('gives every entry an icon, including a solution whose package names no glyph', () => {
    renderWithProviders(
      <SidebarNav
        solutions={[...SOLUTIONS, { id: 'unknown', name: 'Unknown', instanceCount: 0 }]}
        solutionCount={3}
      />,
      { route: '/' },
    );

    for (const name of [
      'Home',
      'Solutions (3)',
      'Cache (12 instances)',
      'File Mesh (3 instances)',
      // No `icon` on the package, and the glyph set is deliberately open —
      // this entry must still get the generic package mark, never a hole.
      'Unknown (0 instances)',
    ]) {
      expect(screen.getByRole('link', { name }).querySelector('svg')).not.toBeNull();
    }
  });

  it('marks the selected solution with a green status dot that moves with the selection', () => {
    const { unmount } = renderWithProviders(
      <SidebarNav solutions={SOLUTIONS} solutionCount={2} />,
      { route: '/solutions/file-mesh/instances' },
    );

    expect(
      screen.getByRole('link', { name: 'File Mesh (3 instances)' }).getAttribute('aria-current'),
    ).toBe('page');
    expect(dotOf('File Mesh (3 instances)').getAttribute('data-active')).toBe('true');
    // Nothing about the treatment is File-Mesh-specific: every solution
    // carries the same dot, lit only while it is the selected one.
    expect(dotOf('Cache (12 instances)').getAttribute('data-active')).toBeNull();

    unmount();

    renderWithProviders(<SidebarNav solutions={SOLUTIONS} solutionCount={2} />, {
      route: '/solutions/cache/instances',
    });

    expect(dotOf('Cache (12 instances)').getAttribute('data-active')).toBe('true');
    expect(dotOf('File Mesh (3 instances)').getAttribute('data-active')).toBeNull();
  });

  it('stays selected across a solution sub-route, not just its instances landing page', () => {
    renderWithProviders(<SidebarNav solutions={SOLUTIONS} solutionCount={2} />, {
      route: '/solutions/cache/instances/i-1/designer',
    });

    expect(
      screen.getByRole('link', { name: 'Cache (12 instances)' }).getAttribute('aria-current'),
    ).toBe('page');
    expect(dotOf('Cache (12 instances)').getAttribute('data-active')).toBe('true');
  });

  it('collapsed: hides the labels but keeps every entry named, and offers the expand control', () => {
    const { container } = renderWithProviders(
      <SidebarNav solutions={SOLUTIONS} solutionCount={2} collapsed onToggleCollapsed={() => {}} />,
      { route: '/solutions/cache/instances' },
    );

    expect(container.querySelector('[data-sidebar-nav]')?.getAttribute('data-collapsed')).toBe(
      'true',
    );
    // The label text leaves the layout; the accessible name does not.
    expect(screen.queryByText('Cache (12 instances)')).toBeNull();
    expect(screen.getByRole('link', { name: 'Cache (12 instances)' })).not.toBeNull();
    expect(screen.getByRole('link', { name: 'Home' })).not.toBeNull();
    // The selection treatment survives the collapse.
    expect(dotOf('Cache (12 instances)').getAttribute('data-active')).toBe('true');
    expect(screen.getByRole('button', { name: 'Expand navigation' })).not.toBeNull();
  });

  it('offers no collapse control where the caller owns no rail state', () => {
    renderWithProviders(<SidebarNav solutions={SOLUTIONS} solutionCount={2} />, { route: '/' });

    expect(screen.queryByRole('button', { name: /navigation/i })).toBeNull();
  });
});
