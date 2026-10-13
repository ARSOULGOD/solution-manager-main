import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { CapabilityTier } from '../metricsRepository';

import { TimeRangePicker } from './TimeRangePicker';

const FULL_TIERS: readonly CapabilityTier[] = [
  { name: 'raw', retention: 'P6M', minBucketSeconds: 15 },
  { name: 'hourly', retention: 'P2Y', minBucketSeconds: 3600 },
  { name: 'daily', retention: null, minBucketSeconds: 86400 },
];
const modes = ['light', 'dark'] as const;

describe('TimeRangePicker a11y', () => {
  it.each(modes)('has no violations with a preset selected, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <TimeRangePicker value={{ kind: 'preset', preset: '24h' }} onChange={() => {}} tiers={FULL_TIERS} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations with custom bounds shown, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <TimeRangePicker
        value={{ kind: 'custom', from: '2026-08-01T00:00:00.000Z', to: '2026-08-07T00:00:00.000Z' }}
        onChange={() => {}}
        tiers={FULL_TIERS}
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it('has no violations while disabled', async () => {
    const { container } = renderWithProviders(
      <TimeRangePicker
        value={{ kind: 'preset', preset: '24h' }}
        onChange={() => {}}
        tiers={FULL_TIERS}
        disabled
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
