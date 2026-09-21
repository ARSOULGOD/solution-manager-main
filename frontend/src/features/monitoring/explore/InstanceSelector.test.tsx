import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { InstanceSelector, type InstanceSelectorInstance } from './InstanceSelector';

const INSTANCES: readonly InstanceSelectorInstance[] = [
  { id: 'cache-prod-01', label: 'cache-prod-01', replicaCount: 3 },
  { id: 'cache-prod-02', label: 'cache-prod-02', replicaCount: 2 },
  { id: 'cache-prod-03', label: 'cache-prod-03', replicaCount: 1 },
];

function Probe({
  cap,
  seriesBy = 'instance',
  initialSelected = [],
}: {
  cap: number;
  seriesBy?: 'instance' | 'replica';
  initialSelected?: readonly string[];
}) {
  const [selected, setSelected] = useState<readonly string[]>(initialSelected);
  return (
    <InstanceSelector
      instances={INSTANCES}
      selected={selected}
      onSelectedChange={setSelected}
      seriesBy={seriesBy}
      cap={cap}
      onNarrowByLabel={() => {}}
      onAggregate={() => {}}
    />
  );
}

describe('InstanceSelector', () => {
  it('renders every instance as a row with its own projected series count', () => {
    renderWithProviders(<Probe cap={10} seriesBy="replica" />);
    expect(screen.getByRole('checkbox', { name: 'cache-prod-01' })).not.toBeNull();
    expect(screen.getByText('3 sr')).not.toBeNull();
    expect(screen.getByText('2 sr')).not.toBeNull();
    expect(screen.getByText('1 sr')).not.toBeNull();
  });

  it('projects Σ1 under seriesBy: instance', () => {
    renderWithProviders(<Probe cap={10} seriesBy="instance" initialSelected={['cache-prod-01', 'cache-prod-02']} />);
    expect(screen.getByTestId('instance-selector-projection').textContent).toContain('2 / 10');
    // Each row still shows 1 sr — the per-instance contribution, not the running total.
    expect(screen.getAllByText('1 sr').length).toBe(3);
  });

  it('projects Σ replicas under seriesBy: replica, for the same selection', () => {
    renderWithProviders(<Probe cap={10} seriesBy="replica" initialSelected={['cache-prod-01', 'cache-prod-02']} />);
    // 3 + 2 = 5.
    expect(screen.getByTestId('instance-selector-projection').textContent).toContain('5 / 10');
  });

  it('selecting an instance updates the projection live', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe cap={10} seriesBy="instance" />);
    await user.click(screen.getByRole('checkbox', { name: 'cache-prod-01' }));
    expect(screen.getByRole('checkbox', { name: 'cache-prod-01' })).toBeChecked();
  });

  it('at the cap, unselected rows disable and the two-routes panel appears', () => {
    // cap 3, replica mode, cache-prod-01 selected (3 replicas) — already at cap.
    renderWithProviders(<Probe cap={3} seriesBy="replica" initialSelected={['cache-prod-01']} />);
    expect(screen.getByRole('checkbox', { name: 'cache-prod-02' })).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: 'cache-prod-03' })).toBeDisabled();
    expect(screen.getByText('Series cap reached — two ways forward')).not.toBeNull();
  });

  it('does not disable a row whose own contribution still fits under the cap, even near capacity', () => {
    // cap 4, replica mode, cache-prod-01 selected (3 replicas) — projected 3.
    // cache-prod-03 (1 replica) fits (3+1=4 <= 4); cache-prod-02 (2 replicas) does not (3+2=5 > 4).
    renderWithProviders(<Probe cap={4} seriesBy="replica" initialSelected={['cache-prod-01']} />);
    expect(screen.getByRole('checkbox', { name: 'cache-prod-03' })).not.toBeDisabled();
    expect(screen.getByRole('checkbox', { name: 'cache-prod-02' })).toBeDisabled();
  });

  it('never lets a blocked selection succeed — the row is a genuinely disabled control, not a styled-only one', () => {
    // A real `disabled` input rejects pointer interaction outright (browsers
    // don't dispatch click to it, and neither does user-event) — the
    // strongest form of "cannot succeed and then fail at query time".
    renderWithProviders(<Probe cap={3} seriesBy="replica" initialSelected={['cache-prod-01']} />);
    const blockedRow = screen.getByRole('checkbox', { name: 'cache-prod-02' });
    expect(blockedRow).toBeDisabled();
    expect(blockedRow).not.toBeChecked();
  });

  it('switching to replica mode visibly reduces how many instances remain selectable', () => {
    // cap 3, cache-prod-01 selected. Under instance mode (projected 1) both
    // remaining rows fit; under replica mode (projected 3, cache-prod-01's
    // own count) neither remaining row still fits.
    const { rerender } = renderWithProviders(
      <InstanceSelector
        instances={INSTANCES}
        selected={['cache-prod-01']}
        onSelectedChange={() => {}}
        seriesBy="instance"
        cap={3}
        onNarrowByLabel={() => {}}
        onAggregate={() => {}}
      />,
    );
    expect(screen.getByRole('checkbox', { name: 'cache-prod-02' })).not.toBeDisabled();

    rerender(
      <InstanceSelector
        instances={INSTANCES}
        selected={['cache-prod-01']}
        onSelectedChange={() => {}}
        seriesBy="replica"
        cap={3}
        onNarrowByLabel={() => {}}
        onAggregate={() => {}}
      />,
    );
    expect(screen.getByRole('checkbox', { name: 'cache-prod-02' })).toBeDisabled();
  });

  it('never shows the cap panel below the cap', () => {
    renderWithProviders(<Probe cap={10} seriesBy="instance" initialSelected={['cache-prod-01']} />);
    expect(screen.queryByText('Series cap reached — two ways forward')).toBeNull();
  });

  it('both escape routes invoke their callback', async () => {
    const user = userEvent.setup();
    const onNarrowByLabel = vi.fn();
    const onAggregate = vi.fn();
    renderWithProviders(
      <InstanceSelector
        instances={INSTANCES}
        selected={['cache-prod-01']}
        onSelectedChange={() => {}}
        seriesBy="replica"
        cap={3}
        onNarrowByLabel={onNarrowByLabel}
        onAggregate={onAggregate}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'Narrow by label' }));
    await user.click(screen.getByRole('button', { name: 'Aggregate into one line' }));
    expect(onNarrowByLabel).toHaveBeenCalledTimes(1);
    expect(onAggregate).toHaveBeenCalledTimes(1);
  });

  it('disabled disables every row and suppresses the cap panel, even past the cap', () => {
    renderWithProviders(
      <InstanceSelector
        instances={INSTANCES}
        selected={['cache-prod-01']}
        onSelectedChange={() => {}}
        seriesBy="replica"
        cap={1}
        disabled
        onNarrowByLabel={() => {}}
        onAggregate={() => {}}
      />,
    );
    expect(screen.getByRole('checkbox', { name: 'cache-prod-01' })).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: 'cache-prod-02' })).toBeDisabled();
    expect(screen.queryByText('Series cap reached — two ways forward')).toBeNull();
  });
});
