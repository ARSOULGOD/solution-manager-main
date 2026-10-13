import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { MetricType } from '@shared/testdoubles/data/metrics';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { MetricsRepository } from '../metricsRepository';

import { TypeMetricDialog } from './TypeMetricDialog';

const metricsRepository: Pick<MetricsRepository, 'declareMetricType'> = {
  declareMetricType: () => Promise.reject(new Error('not exercised in a11y assertions')),
};

const modes = ['light', 'dark'] as const;

function dialog(metricType: MetricType | '') {
  return (
    <TypeMetricDialog
      open
      metricName="cache.custom_lag"
      metricsRepository={metricsRepository}
      metricType={metricType}
      onMetricTypeChange={() => {}}
      unit=""
      onUnitChange={() => {}}
      onClose={() => {}}
      onTyped={() => {}}
    />
  );
}

describe('TypeMetricDialog a11y', () => {
  it.each(modes)('has no violations with nothing chosen yet, in %s mode', async (mode) => {
    const { container } = renderWithProviders(dialog(''), { mode });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations with a type chosen, in %s mode', async (mode) => {
    const { container } = renderWithProviders(dialog('gauge'), { mode });
    expect(await axe(container)).toHaveNoViolations();
  });
});
