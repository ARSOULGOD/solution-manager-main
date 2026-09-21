import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';

import { timeZoneLabel } from '@shared/format/time';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import type { CapabilityTier } from '../metricsRepository';

import type { TimeRangeValue } from './timeRangeOptions';
import { TimeRangePicker } from './TimeRangePicker';

const FULL_TIERS: readonly CapabilityTier[] = [
  { name: 'raw', retention: 'P6M', minBucketSeconds: 15 },
  { name: 'hourly', retention: 'P2Y', minBucketSeconds: 3600 },
  { name: 'daily', retention: null, minBucketSeconds: 86400 },
];
const DAY_ONLY_TIER: readonly CapabilityTier[] = [
  { name: 'raw', retention: 'P1D', minBucketSeconds: 300 },
];

function Probe({
  tiers = FULL_TIERS,
  initial = { kind: 'preset', preset: '24h' } as const,
}: {
  tiers?: readonly CapabilityTier[];
  initial?: TimeRangeValue;
}) {
  const [value, setValue] = useState<TimeRangeValue>(initial);
  return <TimeRangePicker value={value} onChange={setValue} tiers={tiers} />;
}

async function openAndListOptions(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByLabelText('Time range'));
  const options = await screen.findAllByRole('option');
  return options.map((o) => o.textContent);
}

describe('TimeRangePicker', () => {
  it('offers every candidate preset plus custom when tiers advertise the full retention', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe />);
    expect(await openAndListOptions(user)).toEqual([
      'Last 15m',
      'Last 1h',
      'Last 6h',
      'Last 24h',
      'Last 7d',
      'Last 30d',
      'Last 90d',
      'Last 6mo',
      'Custom range',
    ]);
  });

  it('caps the preset list at 24h for a single-day tier, but always keeps custom', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe tiers={DAY_ONLY_TIER} />);
    expect(await openAndListOptions(user)).toEqual([
      'Last 15m',
      'Last 1h',
      'Last 6h',
      'Last 24h',
      'Custom range',
    ]);
  });

  it('corrects a stale preset no longer offered to the longest still-permitted one', () => {
    renderWithProviders(<Probe tiers={DAY_ONLY_TIER} initial={{ kind: 'preset', preset: '90d' }} />);
    expect(screen.getByLabelText('Time range')).toHaveTextContent('Last 24h');
  });

  it('selecting custom reveals from/to inputs seeded from the currently resolved window', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe />);
    await user.click(screen.getByLabelText('Time range'));
    await user.click(await screen.findByRole('option', { name: 'Custom range' }));
    expect(screen.getByLabelText('From')).not.toBeNull();
    expect(screen.getByLabelText('To')).not.toBeNull();
  });

  it('renders the resolved window with a labelled zone', () => {
    renderWithProviders(
      <Probe
        initial={{ kind: 'custom', from: '2026-08-01T00:00:00.000Z', to: '2026-08-07T00:00:00.000Z' }}
      />,
    );
    // The zone label (e.g. "UTC", "GMT", "GMT+5:30") always trails the formatted times — never a
    // bare local time with no indication of which zone it's in (`B/D15`).
    const caption = screen.getByText(/–/);
    const expectedZone = timeZoneLabel();
    expect(caption.textContent?.endsWith(expectedZone)).toBe(true);
  });

  it('disabled disables the select', () => {
    renderWithProviders(
      <TimeRangePicker
        value={{ kind: 'preset', preset: '24h' }}
        onChange={() => {}}
        tiers={FULL_TIERS}
        disabled
      />,
    );
    expect(screen.getByLabelText('Time range')).toHaveAttribute('aria-disabled', 'true');
  });
});
