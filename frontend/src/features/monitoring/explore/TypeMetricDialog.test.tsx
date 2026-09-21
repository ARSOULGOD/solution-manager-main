import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import type { CatalogEntry, MetricType } from '@shared/testdoubles/data/metrics';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { MetricsRepository } from '../metricsRepository';

import { TypeMetricDialog } from './TypeMetricDialog';

function stubRepository(
  declareMetricType: MetricsRepository['declareMetricType'] = () =>
    Promise.reject(new Error('not used in this test')),
): Pick<MetricsRepository, 'declareMetricType'> {
  return { declareMetricType };
}

function Probe({
  metricsRepository = stubRepository(),
  onTyped = () => {},
  onClose = () => {},
}: {
  metricsRepository?: Pick<MetricsRepository, 'declareMetricType'>;
  onTyped?: (entry: CatalogEntry) => void;
  onClose?: () => void;
}) {
  const [metricType, setMetricType] = useState<MetricType | ''>('');
  const [unit, setUnit] = useState('');
  return (
    <TypeMetricDialog
      open
      metricName="cache.custom_lag"
      metricsRepository={metricsRepository}
      metricType={metricType}
      onMetricTypeChange={setMetricType}
      unit={unit}
      onUnitChange={setUnit}
      onClose={onClose}
      onTyped={onTyped}
    />
  );
}

describe('TypeMetricDialog', () => {
  it('names the metric being typed in the title', () => {
    renderWithProviders(<Probe />);
    expect(screen.getByText('Type cache.custom_lag')).not.toBeNull();
  });

  it('states that inferring a type is forbidden', () => {
    renderWithProviders(<Probe />);
    expect(screen.getByText(/Inferring a type is forbidden/)).not.toBeNull();
  });

  it('states the no-suggested-type limitation', () => {
    renderWithProviders(<Probe />);
    expect(screen.getByText(/neither a first-seen timestamp nor a suggested-type field/)).not.toBeNull();
  });

  it('offers exactly the three contract metric types', () => {
    renderWithProviders(<Probe />);
    expect(screen.getByRole('radio', { name: 'counter' })).not.toBeNull();
    expect(screen.getByRole('radio', { name: 'gauge' })).not.toBeNull();
    expect(screen.getByRole('radio', { name: 'histogram' })).not.toBeNull();
  });

  it('Save is disabled until a type is chosen', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe />);
    expect(screen.getByRole('button', { name: 'Save type' })).toBeDisabled();
    await user.click(screen.getByRole('radio', { name: 'histogram' }));
    expect(screen.getByRole('button', { name: 'Save type' })).not.toBeDisabled();
  });

  it('unit is free text with no vocabulary offered', () => {
    renderWithProviders(<Probe />);
    const unitField = screen.getByLabelText('Unit');
    expect(unitField.tagName).toBe('INPUT');
    expect(screen.getByText(/there is no unit vocabulary anywhere in the contract/)).not.toBeNull();
  });

  it('Save calls declareMetricType with the chosen type and unit, then onTyped with the result', async () => {
    const user = userEvent.setup();
    const typed: CatalogEntry = {
      name: 'cache.custom_lag',
      metricType: 'counter',
      unit: 'events',
      layer: 'user-extension',
      state: 'plottable',
      lastSeen: 0,
      labelKeys: [],
    };
    const declareMetricType = vi.fn().mockResolvedValue(typed);
    const onTyped = vi.fn();
    renderWithProviders(<Probe metricsRepository={stubRepository(declareMetricType)} onTyped={onTyped} />);

    await user.click(screen.getByRole('radio', { name: 'counter' }));
    await user.type(screen.getByLabelText('Unit'), 'events');
    await user.click(screen.getByRole('button', { name: 'Save type' }));

    await waitFor(() =>
      expect(declareMetricType).toHaveBeenCalledWith('cache.custom_lag', 'counter', 'events'),
    );
    await waitFor(() => expect(onTyped).toHaveBeenCalledWith(typed));
  });

  it('shows an error and does not call onTyped when the save fails', async () => {
    const user = userEvent.setup();
    const declareMetricType = vi.fn().mockRejectedValue(new Error('boom'));
    const onTyped = vi.fn();
    renderWithProviders(<Probe metricsRepository={stubRepository(declareMetricType)} onTyped={onTyped} />);

    await user.click(screen.getByRole('radio', { name: 'gauge' }));
    await user.click(screen.getByRole('button', { name: 'Save type' }));

    await waitFor(() => expect(screen.getByText('The type could not be saved. Try again.')).not.toBeNull());
    expect(onTyped).not.toHaveBeenCalled();
  });

  it('Cancel calls onClose without calling declareMetricType', async () => {
    const user = userEvent.setup();
    const declareMetricType = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(
      <Probe metricsRepository={stubRepository(declareMetricType)} onClose={onClose} />,
    );
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(declareMetricType).not.toHaveBeenCalled();
  });
});
