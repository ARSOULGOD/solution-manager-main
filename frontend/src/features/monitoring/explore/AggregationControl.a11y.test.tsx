import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { AggregationControl } from './AggregationControl';

const ALL_AGGREGATIONS: readonly ('sum' | 'avg' | 'min' | 'max')[] = ['sum', 'avg', 'min', 'max'];
const modes = ['light', 'dark'] as const;

describe('AggregationControl a11y', () => {
  it.each(modes)('has no violations unforced, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <AggregationControl
        value="none"
        onChange={() => {}}
        metricType="gauge"
        capabilityAggregations={ALL_AGGREGATIONS}
        mark="line"
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations while forced, with the caption shown, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <AggregationControl
        value="sum"
        onChange={() => {}}
        metricType="gauge"
        capabilityAggregations={ALL_AGGREGATIONS}
        mark="gauge"
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it('has no violations while disabled (gate-locked)', async () => {
    const { container } = renderWithProviders(
      <AggregationControl
        value="none"
        onChange={() => {}}
        metricType="gauge"
        capabilityAggregations={ALL_AGGREGATIONS}
        mark="line"
        disabled
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it('has no violations for a histogram metric (unsupported caption shown)', async () => {
    const { container } = renderWithProviders(
      <AggregationControl
        value="none"
        onChange={() => {}}
        metricType="histogram"
        capabilityAggregations={ALL_AGGREGATIONS}
        mark="line"
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
