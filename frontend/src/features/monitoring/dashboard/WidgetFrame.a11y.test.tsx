import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';

import { createConsoleClient } from '@shared/api';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { WidgetConfig, WidgetPlacement } from '../dashboardRepository';
import { MetricsProvider } from '../MetricsProvider';

import { WidgetFrame } from './WidgetFrame';

// Same rationale as `WidgetFrame.test.tsx`'s own header — a fully mocked
// `ChartRenderer` keeps this suite out of jsdom's canvas gap entirely.
vi.mock('../chart/ChartRenderer', () => ({ ChartRenderer: () => <div data-testid="real-chart" /> }));

const consoleClient = createConsoleClient('/api');

function makeWidget(overrides: Partial<WidgetConfig> = {}): WidgetConfig {
  return {
    schemaVersion: 1,
    id: 'w1',
    title: 'Hit rate',
    mark: 'line',
    options: {},
    query: {
      metric: 'cache.hit_rate',
      selector: { entityType: 'cache-engine', instanceIds: [] },
      seriesBy: 'instance',
      aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
    },
    range: { kind: 'preset', preset: '24h' },
    resolution: 'auto',
    axes: { y: { label: '', unitFormat: 'auto', min: null, max: null } },
    legend: { visible: true, position: 'bottom', maxHeightRows: 4 },
    series: { colorStrategy: 'palette', colors: {} },
    thresholds: [],
    refresh: { intervalSeconds: 60 },
    layer: 'product-default',
    ...overrides,
  };
}

const PLACEMENT: WidgetPlacement = { widgetId: 'w1', x: 0, y: 0, w: 6, h: 2, order: 0 };
const ABSOLUTE_RANGE = { from: new Date(Date.now() - 86_400_000).toISOString(), to: new Date().toISOString() };

function renderWidget(overrides: Partial<Parameters<typeof WidgetFrame>[0]> = {}, mode: 'light' | 'dark' = 'light') {
  return renderWithProviders(
    <MetricsProvider consoleClient={consoleClient}>
      <WidgetFrame
        widget={makeWidget()}
        placement={PLACEMENT}
        absoluteRange={ABSOLUTE_RANGE}
        resolution="auto"
        refreshSeconds={0}
        editing={false}
        onMoveEarlier={() => {}}
        onMoveLater={() => {}}
        canMoveEarlier={false}
        canMoveLater={false}
        onHide={() => {}}
        hidePending={false}
        {...overrides}
      />
    </MetricsProvider>,
    { mode },
  );
}

const modes = ['light', 'dark'] as const;

describe('WidgetFrame a11y', () => {
  it.each(modes)('loaded (line) state has no violations in %s mode', async (mode) => {
    const { container } = renderWidget({}, mode);
    await waitFor(() => {
      expect(screen.getByTestId('real-chart')).toBeInTheDocument();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('edit mode (order badge + move buttons) has no violations in %s mode', async (mode) => {
    const { container } = renderWidget({ editing: true, canMoveEarlier: true, canMoveLater: true }, mode);
    await waitFor(() => {
      expect(screen.getByTestId('real-chart')).toBeInTheDocument();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it('too-new (read-only) state has no violations', async () => {
    const { container } = renderWidget({ widget: makeWidget({ schemaVersion: 2 as 1 }) });
    expect(screen.getByText('Read-only')).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('open menu (with the Hide and Export CSV actions) has no violations', async () => {
    const user = userEvent.setup();
    const { container } = renderWidget();
    await waitFor(() => {
      expect(screen.getByTestId('real-chart')).toBeInTheDocument();
    });
    await user.click(screen.getByRole('button', { name: 'Widget menu' }));
    await screen.findByRole('menuitem', { name: 'Hide (keep default)' });
    await screen.findByRole('menuitem', { name: 'Export CSV' });
    expect(await axe(container)).toHaveNoViolations();
  });

  it('the Export CSV high-consequence confirm dialog has no violations', async () => {
    const user = userEvent.setup();
    const { container } = renderWidget();
    await waitFor(() => {
      expect(screen.getByTestId('real-chart')).toBeInTheDocument();
    });
    await user.click(screen.getByRole('button', { name: 'Widget menu' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Export CSV' }));
    await screen.findByText('Export widget as CSV?');
    expect(await axe(container)).toHaveNoViolations();
  });
});
