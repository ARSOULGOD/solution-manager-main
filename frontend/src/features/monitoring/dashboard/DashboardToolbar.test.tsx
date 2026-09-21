import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
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
    onTimeRangeChange: vi.fn(),
    tiers: TIERS,
    refreshSeconds: 0,
    onRefreshChange: vi.fn(),
    effectiveRefreshSeconds: 0,
    refreshDisabled: false,
    refreshPaused: false,
    resolution: 'auto',
    onResolutionChange: vi.fn(),
    forceRawAdvertised: false,
    layer: 'all',
    onLayerChange: vi.fn(),
    ...overrides,
  };
}

describe('DashboardToolbar', () => {
  it('renders the time range, refresh, resolution and layer controls', () => {
    renderWithProviders(<DashboardToolbar {...baseProps()} />);
    expect(screen.getByLabelText('Time range')).toBeInTheDocument();
    expect(screen.getByLabelText('Refresh')).toBeInTheDocument();
    expect(screen.getByLabelText('Layer')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Auto' })).toBeInTheDocument();
  });

  it('omits Raw from resolution when forceRaw is not advertised', () => {
    renderWithProviders(<DashboardToolbar {...baseProps({ forceRawAdvertised: false })} />);
    expect(screen.queryByRole('button', { name: 'Raw' })).toBeNull();
  });

  it('offers Raw when forceRaw is advertised', () => {
    renderWithProviders(<DashboardToolbar {...baseProps({ forceRawAdvertised: true })} />);
    expect(screen.getByRole('button', { name: 'Raw' })).toBeInTheDocument();
  });

  it('calls onRefreshChange with a number when a refresh option is picked', async () => {
    const user = userEvent.setup();
    const onRefreshChange = vi.fn();
    renderWithProviders(<DashboardToolbar {...baseProps({ onRefreshChange })} />);
    await user.click(screen.getByLabelText('Refresh'));
    await user.click(await screen.findByRole('option', { name: '1m' }));
    expect(onRefreshChange).toHaveBeenCalledWith(60);
  });

  it('disables the refresh select and states why for a custom range', () => {
    renderWithProviders(
      <DashboardToolbar
        {...baseProps({
          timeRange: { kind: 'custom', from: '2026-01-01T00:00:00Z', to: '2026-01-02T00:00:00Z' },
          refreshDisabled: true,
        })}
      />,
    );
    expect(screen.getByLabelText('Refresh')).toHaveAttribute('aria-disabled', 'true');
    expect(
      screen.getByText('Refresh is disabled for a fixed range — there is nothing new to fetch.'),
    ).toBeInTheDocument();
  });

  it('states the floored interval when flooring changed what was requested', () => {
    renderWithProviders(
      <DashboardToolbar {...baseProps({ refreshSeconds: 10, effectiveRefreshSeconds: 3600 })} />,
    );
    expect(
      screen.getByText(
        'Flooring to 3600s — polling faster than the tier this range would serve just re-fetches the same bucket.',
      ),
    ).toBeInTheDocument();
  });

  it('states a paused reason when the tab is hidden, taking priority over the floor note', () => {
    renderWithProviders(
      <DashboardToolbar
        {...baseProps({ refreshSeconds: 10, effectiveRefreshSeconds: 3600, refreshPaused: true })}
      />,
    );
    expect(screen.getByText('Refresh paused — this tab is hidden.')).toBeInTheDocument();
    expect(screen.queryByText(/Flooring to/)).toBeNull();
  });

  it('shows no refresh note for "off"', () => {
    renderWithProviders(<DashboardToolbar {...baseProps({ refreshSeconds: 0, effectiveRefreshSeconds: 0 })} />);
    expect(screen.queryByText(/Flooring to/)).toBeNull();
    expect(screen.queryByText(/Refresh is disabled/)).toBeNull();
    expect(screen.queryByText(/Refresh paused/)).toBeNull();
  });

  it('calls onLayerChange with the selected layer', async () => {
    const user = userEvent.setup();
    const onLayerChange = vi.fn();
    renderWithProviders(<DashboardToolbar {...baseProps({ onLayerChange })} />);
    await user.click(screen.getByLabelText('Layer'));
    await user.click(await screen.findByRole('option', { name: 'Shared' }));
    expect(onLayerChange).toHaveBeenCalledWith('shared');
  });

  // The sticky shell and the scrolling row are two elements now: the row
  // holds the controls at one height, and the shell carries the notes line
  // under it. SC 1.4.10's exception is claimed by whichever element
  // actually scrolls, which is what `reflowCheck.ts` resolves with
  // `closest()` — so the assertion follows the attribute rather than
  // assuming the root is the scroller.
  it('is a persistent, single-axis-scrolling toolbar at every width (task 109, SC 1.4.10)', () => {
    const { container } = renderWithProviders(<DashboardToolbar {...baseProps()} />);
    const root = container.firstElementChild;
    expect(root).toBeInstanceOf(HTMLElement);
    if (!(root instanceof HTMLElement)) {
      return;
    }
    expect(getComputedStyle(root).position).toBe('sticky');

    const scroller = root.querySelector('[data-reflow-exception="persistent-toolbar"]');
    expect(scroller).toBeInstanceOf(HTMLElement);
    if (!(scroller instanceof HTMLElement)) {
      return;
    }
    const style = getComputedStyle(scroller);
    expect(style.flexWrap).toBe('nowrap');
    expect(style.overflowX).toBe('auto');
  });

  it('states the resolved window with its zone, and DM-D21, under the controls', () => {
    renderWithProviders(<DashboardToolbar {...baseProps()} />);
    expect(screen.getByText(/GMT|UTC/)).toBeInTheDocument();
    expect(screen.getByText(/authoritative for every widget/)).toBeInTheDocument();
  });
});
