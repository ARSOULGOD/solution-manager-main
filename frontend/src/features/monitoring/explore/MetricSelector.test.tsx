import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import type { CatalogEntry } from '@shared/testdoubles/data/metrics';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { MetricsRepository } from '../metricsRepository';

import { MetricSelector } from './MetricSelector';

const CATALOG: readonly CatalogEntry[] = [
  {
    name: 'cache.hit_rate',
    metricType: 'gauge',
    unit: 'ratio',
    layer: 'package-baseline',
    state: 'plottable',
    lastSeen: 0,
    labelKeys: [],
  },
  {
    name: 'cache.custom_lag',
    metricType: null,
    unit: null,
    layer: 'user-extension',
    state: 'needs-typing',
    lastSeen: 0,
    labelKeys: [],
  },
  {
    name: 'cache.queue_depth',
    metricType: 'gauge',
    unit: 'items',
    layer: 'user-extension',
    state: 'type-suspect',
    lastSeen: 0,
    labelKeys: [],
    suspectReason: 'Observed values climb then drop to zero like a counter reset.',
  },
];

function stubRepository(
  declareMetricType: MetricsRepository['declareMetricType'] = () =>
    Promise.reject(new Error('not used in this test')),
): Pick<MetricsRepository, 'declareMetricType'> {
  return { declareMetricType };
}

function Probe({
  catalog = CATALOG,
  initialSelected = null,
  metricsRepository = stubRepository(),
}: {
  catalog?: readonly CatalogEntry[];
  initialSelected?: string | null;
  metricsRepository?: Pick<MetricsRepository, 'declareMetricType'>;
}) {
  const [selected, setSelected] = useState<string | null>(initialSelected);
  return (
    <MetricSelector
      catalog={catalog}
      selected={selected}
      onSelectedChange={setSelected}
      metricsRepository={metricsRepository}
    />
  );
}

describe('MetricSelector', () => {
  it('renders every catalog entry, each with its layer and state chip', () => {
    renderWithProviders(<Probe />);
    expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull();
    expect(screen.getByRole('radio', { name: 'cache.custom_lag' })).not.toBeNull();
    expect(screen.getByRole('radio', { name: 'cache.queue_depth' })).not.toBeNull();
    expect(screen.getAllByText('pkg')).toHaveLength(1);
    expect(screen.getAllByText('ext')).toHaveLength(2);
    expect(screen.getByText('plottable')).not.toBeNull();
    expect(screen.getByText('needs typing')).not.toBeNull();
    expect(screen.getByText('type-suspect')).not.toBeNull();
  });

  it('selecting a plottable metric fires onSelectedChange with its name', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe />);
    await user.click(screen.getByRole('radio', { name: 'cache.hit_rate' }));
    expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).toBeChecked();
  });

  it('single-select: choosing a different metric replaces the selection, never adds a second', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe initialSelected="cache.hit_rate" />);
    await user.click(screen.getByRole('radio', { name: 'cache.queue_depth' }));
    expect(screen.getByRole('radio', { name: 'cache.queue_depth' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeChecked();
  });

  it('never lets a needs-typing row succeed — a real disabled control rejects the click outright', () => {
    // A real `disabled` input has `pointer-events: none`, so `user-event`
    // refuses to click it at all (the same "genuinely disabled control"
    // reasoning `InstanceSelector`'s own cap-blocked-row test gives).
    renderWithProviders(<Probe />);
    const disabledRadio = screen.getByRole('radio', { name: 'cache.custom_lag' });
    expect(disabledRadio).toBeDisabled();
    expect(disabledRadio).not.toBeChecked();
  });

  it('shows a persistent warning for a selected type-suspect metric', () => {
    renderWithProviders(<Probe initialSelected="cache.queue_depth" />);
    expect(screen.getByText('Type-suspect — plotted, never auto-corrected')).not.toBeNull();
    expect(
      screen.getByText('Observed values climb then drop to zero like a counter reset.'),
    ).not.toBeNull();
  });

  it('shows no type-suspect warning for a plottable selection', () => {
    renderWithProviders(<Probe initialSelected="cache.hit_rate" />);
    expect(screen.queryByText('Type-suspect — plotted, never auto-corrected')).toBeNull();
  });

  it('shows the declared type and unit for a selected, typed metric', () => {
    renderWithProviders(<Probe initialSelected="cache.hit_rate" />);
    expect(screen.getByText('type gauge · unit ratio')).not.toBeNull();
  });

  it('a needs-typing row offers a Type… affordance; a plottable row does not', () => {
    renderWithProviders(<Probe />);
    expect(screen.getByRole('button', { name: 'Type…' })).not.toBeNull();
    expect(screen.getAllByRole('button', { name: 'Type…' })).toHaveLength(1);
  });

  it('opening the type dialog states that inferring a type is forbidden, and offers no suggestion', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe />);
    await user.click(screen.getByRole('button', { name: 'Type…' }));
    expect(screen.getByText(/Inferring a type is forbidden/)).not.toBeNull();
    expect(screen.getByText(/suggested-type field/)).not.toBeNull();
  });

  it('typing a metric calls declareMetricType, then auto-selects the newly-typed metric', async () => {
    const user = userEvent.setup();
    const onSelectedChange = vi.fn();
    const declareMetricType = vi.fn().mockResolvedValue({
      name: 'cache.custom_lag',
      metricType: 'gauge',
      unit: 'ms',
      layer: 'user-extension',
      state: 'plottable',
      lastSeen: 0,
      labelKeys: [],
    } satisfies CatalogEntry);

    renderWithProviders(
      <MetricSelector
        catalog={CATALOG}
        selected={null}
        onSelectedChange={onSelectedChange}
        metricsRepository={stubRepository(declareMetricType)}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'Type…' }));
    await user.click(screen.getByRole('radio', { name: 'gauge' }));
    await user.type(screen.getByLabelText('Unit'), 'ms');
    await user.click(screen.getByRole('button', { name: 'Save type' }));

    await waitFor(() => expect(declareMetricType).toHaveBeenCalledWith('cache.custom_lag', 'gauge', 'ms'));
    await waitFor(() => expect(onSelectedChange).toHaveBeenCalledWith('cache.custom_lag'));
  });

  it('the type dialog Save action stays disabled until a type is chosen', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe />);
    await user.click(screen.getByRole('button', { name: 'Type…' }));
    expect(screen.getByRole('button', { name: 'Save type' })).toBeDisabled();
    await user.click(screen.getByRole('radio', { name: 'counter' }));
    expect(screen.getByRole('button', { name: 'Save type' })).not.toBeDisabled();
  });

  it('cancelling the type dialog leaves the catalog untouched', async () => {
    const user = userEvent.setup();
    const declareMetricType = vi.fn();
    renderWithProviders(<Probe metricsRepository={stubRepository(declareMetricType)} />);
    await user.click(screen.getByRole('button', { name: 'Type…' }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(declareMetricType).not.toHaveBeenCalled();
  });

  it('states the single-select-replaces rule as copy', () => {
    renderWithProviders(<Probe />);
    expect(
      screen.getByText(/Selecting a different metric replaces this one/),
    ).not.toBeNull();
  });
});
