import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import type { AcrossInstances } from '../metricsRepository';

import { AggregationControl } from './AggregationControl';

const ALL_AGGREGATIONS: readonly ('sum' | 'avg' | 'min' | 'max')[] = ['sum', 'avg', 'min', 'max'];

function Probe({
  metricType = 'gauge' as const,
  capabilityAggregations = ALL_AGGREGATIONS,
  mark = 'line' as const,
  initialValue = 'none',
}: {
  metricType?: 'counter' | 'gauge' | 'histogram' | null;
  capabilityAggregations?: readonly ('sum' | 'avg' | 'min' | 'max')[];
  mark?: 'line' | 'gauge' | 'single-stat';
  initialValue?: AcrossInstances;
}) {
  const [value, setValue] = useState<AcrossInstances>(initialValue);
  return (
    <AggregationControl
      value={value}
      onChange={setValue}
      metricType={metricType}
      capabilityAggregations={capabilityAggregations}
      mark={mark}
    />
  );
}

async function openAndListOptions(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByLabelText('Across instances'));
  const options = await screen.findAllByRole('option');
  return options.map((o) => o.textContent);
}

describe('AggregationControl', () => {
  it('offers none plus every type-permitted, capability-advertised function, in canonical order', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe metricType="gauge" />);
    expect(await openAndListOptions(user)).toEqual([
      'Across instances: none (one line each)',
      'Across instances: sum',
      'Across instances: average',
      'Across instances: minimum',
      'Across instances: maximum',
    ]);
  });

  it('offers only none for a histogram metric — unsupported in v1 — plus the caption', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe metricType="histogram" />);
    expect(await openAndListOptions(user)).toEqual(['Across instances: none (one line each)']);
    expect(screen.getByText('Histogram aggregation is not supported yet.')).not.toBeNull();
  });

  it('intersects with a restricted capability set', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe metricType="gauge" capabilityAggregations={['sum', 'avg']} />);
    expect(await openAndListOptions(user)).toEqual([
      'Across instances: none (one line each)',
      'Across instances: sum',
      'Across instances: average',
    ]);
  });

  it('selecting an option calls onChange and updates the displayed value', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe metricType="gauge" />);
    await user.click(screen.getByLabelText('Across instances'));
    await user.click(await screen.findByRole('option', { name: 'Across instances: sum' }));
    expect(screen.getByLabelText('Across instances')).toHaveTextContent('Across instances: sum');
  });

  it('a gauge/single-stat mark forces a non-none value and shows the forced caption', () => {
    renderWithProviders(<Probe metricType="gauge" mark="gauge" initialValue="none" />);
    expect(screen.getByLabelText('Across instances')).toHaveTextContent('Across instances: sum');
    expect(
      screen.getByText('A gauge shows one number — aggregation is forced to a single value.'),
    ).not.toBeNull();
  });

  it('a line mark never forces', () => {
    renderWithProviders(<Probe metricType="gauge" mark="line" initialValue="none" />);
    expect(screen.getByLabelText('Across instances')).toHaveTextContent('Across instances: none (one line each)');
    expect(
      screen.queryByText('A line shows one number — aggregation is forced to a single value.'),
    ).toBeNull();
  });

  it('does not disturb an already-non-none value while forced', () => {
    renderWithProviders(<Probe metricType="gauge" mark="single-stat" initialValue="max" />);
    expect(screen.getByLabelText('Across instances')).toHaveTextContent('Across instances: maximum');
  });

  it('disabled disables the select', () => {
    renderWithProviders(
      <AggregationControl
        value="none"
        onChange={() => {}}
        metricType="gauge"
        capabilityAggregations={ALL_AGGREGATIONS}
        mark="line"
        disabled
      />,
    );
    expect(screen.getByLabelText('Across instances')).toHaveAttribute('aria-disabled', 'true');
  });
});
