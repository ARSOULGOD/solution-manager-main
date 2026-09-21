import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';

import { createConsoleClient } from '@shared/api';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { WidgetConfig, WidgetPlacement } from '../dashboardRepository';
import { MetricsProvider } from '../MetricsProvider';

import { DashboardGrid } from './DashboardGrid';
import type { WidgetFrameProps } from './WidgetFrame';

vi.mock('./WidgetFrame', () => ({
  WidgetFrame: (props: WidgetFrameProps) => (
    <div>
      {props.widget.title}
      {props.editing && (
        <>
          <button
            onClick={props.onMoveEarlier}
            disabled={!props.canMoveEarlier}
            aria-label={`Move ${props.widget.title} earlier`}
          >
            ↑
          </button>
          <button
            onClick={props.onMoveLater}
            disabled={!props.canMoveLater}
            aria-label={`Move ${props.widget.title} later`}
          >
            ↓
          </button>
        </>
      )}
      <button
        onClick={props.onHide}
        disabled={props.hidePending}
        aria-label={`Hide ${props.widget.title}`}
      >
        ⋮
      </button>
    </div>
  ),
}));

const consoleClient = createConsoleClient('/api');

function widget(id: string, title: string, overrides: Partial<WidgetConfig> = {}): WidgetConfig {
  return {
    schemaVersion: 1,
    id,
    title,
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

const WIDGETS: readonly WidgetConfig[] = [widget('w1', 'First'), widget('w2', 'Second')];
const LAYOUT: readonly WidgetPlacement[] = [
  { widgetId: 'w1', x: 0, y: 0, w: 6, h: 1, order: 0 },
  { widgetId: 'w2', x: 6, y: 0, w: 6, h: 1, order: 1 },
];
const ABSOLUTE_RANGE = { from: '2026-08-31T00:00:00.000Z', to: '2026-09-01T00:00:00.000Z' };

function renderGrid(
  mode: 'light' | 'dark',
  overrides: Partial<{ widgets: readonly WidgetConfig[]; editing: boolean }> = {},
) {
  return renderWithProviders(
    <MetricsProvider consoleClient={consoleClient}>
      <DashboardGrid
        dashboardId="db_1"
        widgets={overrides.widgets ?? WIDGETS}
        layout={LAYOUT}
        absoluteRange={ABSOLUTE_RANGE}
        resolution="auto"
        refreshSeconds={0}
        editing={overrides.editing ?? false}
      />
    </MetricsProvider>,
    { mode },
  );
}

const modes = ['light', 'dark'] as const;

describe('DashboardGrid a11y', () => {
  it.each(modes)('default (non-editing) state has no violations in %s mode', async (mode) => {
    const { container } = renderGrid(mode);
    expect(await axe(container)).toHaveNoViolations();
  });

  // Task 131 — `editing` is a prop now; the toggle that sets it lives on
  // `DashboardToolbar`, so it is driven directly rather than clicked here.
  it('editing state (move controls) has no violations', async () => {
    const { container } = renderGrid('light', { editing: true });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('a hidden default placeholder has no violations in %s mode', async (mode) => {
    const { container } = renderGrid(mode, {
      widgets: [widget('w1', 'First'), widget('w2', 'Second', { hidden: true })],
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});
