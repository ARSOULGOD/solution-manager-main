import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../test/renderWithProviders';

import { MonitoringTabs, monitoringTabs } from './MonitoringTabs';

const SOLUTION = 'cache';
const INSTANCE = 'cache-prod-03';
const BASE = `/solutions/${SOLUTION}/instances/${INSTANCE}/monitoring`;

describe('monitoringTabs', () => {
  it('builds both routes from the solution and instance', () => {
    expect(monitoringTabs(SOLUTION, INSTANCE).map((tab) => tab.to)).toEqual([
      BASE,
      `${BASE}/explore`,
    ]);
  });

  /**
   * Load-bearing, not decoration: `/monitoring` is a path PREFIX of
   * `/monitoring/explore`, and `RouteTabs` returns the first prefix match,
   * so without `end` both tabs read as active on Explore and the wrong one
   * wins.
   */
  it('marks only the Dashboards tab `end`, because its path is a prefix of Explore', () => {
    const [dashboards, explore] = monitoringTabs(SOLUTION, INSTANCE);

    expect(dashboards?.end).toBe(true);
    expect(explore?.end).toBeUndefined();
  });
});

describe('MonitoringTabs', () => {
  it('renders both tabs as links, with the page body in the panel', () => {
    renderWithProviders(
      <MonitoringTabs solutionId={SOLUTION} instanceId={INSTANCE}>
        <p>Panel body</p>
      </MonitoringTabs>,
      { route: BASE },
    );

    expect(screen.getByRole('tab', { name: 'Dashboards' }).getAttribute('href')).toBe(BASE);
    expect(screen.getByRole('tab', { name: 'Explore' }).getAttribute('href')).toBe(
      `${BASE}/explore`,
    );
    expect(screen.getByText('Panel body')).not.toBeNull();
  });

  it('selects Dashboards on the dashboard route', () => {
    renderWithProviders(
      <MonitoringTabs solutionId={SOLUTION} instanceId={INSTANCE}>
        <p>Panel body</p>
      </MonitoringTabs>,
      { route: BASE },
    );

    expect(screen.getByRole('tab', { name: 'Dashboards' }).getAttribute('aria-selected')).toBe(
      'true',
    );
    expect(screen.getByRole('tab', { name: 'Explore' }).getAttribute('aria-selected')).toBe(
      'false',
    );
  });

  /** The regression plan `126` names: on Explore, BOTH tabs used to read active. */
  it('selects Explore — and only Explore — on the explore route', () => {
    renderWithProviders(
      <MonitoringTabs solutionId={SOLUTION} instanceId={INSTANCE}>
        <p>Panel body</p>
      </MonitoringTabs>,
      { route: `${BASE}/explore` },
    );

    expect(screen.getByRole('tab', { name: 'Explore' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: 'Dashboards' }).getAttribute('aria-selected')).toBe(
      'false',
    );
  });

  it('gives Explore a permanent route from a populated dashboard', () => {
    // Plan `126`'s second defect: Explore was reachable only from two
    // EMPTY states, so a board with one widget on it made the screen that
    // builds widgets unreachable. The bar is on screen regardless of what
    // the panel contains.
    renderWithProviders(
      <MonitoringTabs solutionId={SOLUTION} instanceId={INSTANCE}>
        <p>A populated dashboard, no empty state anywhere</p>
      </MonitoringTabs>,
      { route: BASE },
    );

    expect(screen.getByRole('tab', { name: 'Explore' }).getAttribute('href')).toBe(
      `${BASE}/explore`,
    );
  });
});
