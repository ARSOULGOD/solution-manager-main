import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../test/renderWithProviders';

import { MonitoringTabs } from './MonitoringTabs';

const modes = ['light', 'dark'] as const;
const BASE = '/solutions/cache/instances/cache-prod-03/monitoring';

describe('MonitoringTabs a11y', () => {
  it.each(modes)('has no violations on the dashboard route in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <MonitoringTabs solutionId="cache" instanceId="cache-prod-03">
        <p>Panel body</p>
      </MonitoringTabs>,
      { mode, route: BASE },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations on the explore route in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <MonitoringTabs solutionId="cache" instanceId="cache-prod-03">
        <p>Panel body</p>
      </MonitoringTabs>,
      { mode, route: `${BASE}/explore` },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
