import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { RouteTabs } from './RouteTabs';

describe('RouteTabs', () => {
  it('renders tabs as links and moves focus into the panel after a change', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RouteTabs
        tabs={[
          { to: '/designer', label: 'Designer' },
          { to: '/governance', label: 'Governance' },
        ]}
      >
        Panel body
      </RouteTabs>,
      { route: '/designer' },
    );

    expect(screen.getByRole('tab', { name: 'Designer' }).getAttribute('href')).toBe('/designer');
    expect(screen.getByRole('tab', { name: 'Governance' }).getAttribute('href')).toBe(
      '/governance',
    );

    await user.click(screen.getByRole('tab', { name: 'Governance' }));
    expect(document.querySelector('[data-route-tab-panel]')).toHaveFocus();
  });

  /**
   * Task 130. A parent tab whose path is a prefix of a sibling's — the
   * exact shape Monitoring has, `/monitoring` and `/monitoring/explore` —
   * would otherwise match BOTH, and `find` hands back the parent, so the
   * child route reads as the parent tab being active. `end` mirrors
   * react-router's own `NavLink` `end` prop: match this path exactly, and
   * never as a prefix.
   */
  it('marks only the exact match active when a tab sets `end`', () => {
    renderWithProviders(
      <RouteTabs
        tabs={[
          { to: '/monitoring', label: 'Dashboards', end: true },
          { to: '/monitoring/explore', label: 'Explore' },
        ]}
      >
        Panel body
      </RouteTabs>,
      { route: '/monitoring/explore' },
    );

    expect(screen.getByRole('tab', { name: 'Explore' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: 'Dashboards' }).getAttribute('aria-selected')).toBe(
      'false',
    );
  });

  it('still matches its own path when a tab sets `end`', () => {
    renderWithProviders(
      <RouteTabs
        tabs={[
          { to: '/monitoring', label: 'Dashboards', end: true },
          { to: '/monitoring/explore', label: 'Explore' },
        ]}
      >
        Panel body
      </RouteTabs>,
      { route: '/monitoring' },
    );

    expect(screen.getByRole('tab', { name: 'Dashboards' }).getAttribute('aria-selected')).toBe(
      'true',
    );
    expect(screen.getByRole('tab', { name: 'Explore' }).getAttribute('aria-selected')).toBe(
      'false',
    );
  });

  it('leaves prefix matching in place for a tab that does not set `end`', () => {
    // The existing behaviour every other tab bar relies on — a nested
    // route keeps its parent tab active.
    renderWithProviders(
      <RouteTabs tabs={[{ to: '/designer', label: 'Designer' }]}>Panel body</RouteTabs>,
      { route: '/designer/section/cache' },
    );

    expect(screen.getByRole('tab', { name: 'Designer' }).getAttribute('aria-selected')).toBe(
      'true',
    );
  });
});
