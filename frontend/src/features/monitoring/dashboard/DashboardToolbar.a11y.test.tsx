import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { CapabilityTier } from '../metricsRepository';

import { DashboardToolbar, type DashboardToolbarProps } from './DashboardToolbar';

const TIERS: readonly CapabilityTier[] = [
  { name: 'raw', retention: 'P6M', minBucketSeconds: 15 },
  { name: 'hourly', retention: 'P2Y', minBucketSeconds: 3600 },
  { name: 'daily', retention: null, minBucketSeconds: 86400 },
];

function baseProps(overrides: Partial<DashboardToolbarProps> = {}): DashboardToolbarProps {
  return {
    timeRange: { kind: 'preset', preset: '24h' },
    onTimeRangeChange: () => {},
    tiers: TIERS,
    refreshSeconds: 0,
    onRefreshChange: () => {},
    effectiveRefreshSeconds: 0,
    refreshDisabled: false,
    refreshPaused: false,
    resolution: 'auto',
    onResolutionChange: () => {},
    forceRawAdvertised: false,
    layer: 'all',
    onLayerChange: () => {},
    ...overrides,
  };
}

const modes = ['light', 'dark'] as const;

describe('DashboardToolbar a11y', () => {
  it.each(modes)('default state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<DashboardToolbar {...baseProps()} />, { mode });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('with Raw advertised and a floored refresh note has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <DashboardToolbar
        {...baseProps({ forceRawAdvertised: true, refreshSeconds: 10, effectiveRefreshSeconds: 3600 })}
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it('disabled refresh (custom range) has no violations', async () => {
    const { container } = renderWithProviders(
      <DashboardToolbar
        {...baseProps({
          timeRange: { kind: 'custom', from: '2026-01-01T00:00:00Z', to: '2026-01-02T00:00:00Z' },
          refreshDisabled: true,
        })}
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
