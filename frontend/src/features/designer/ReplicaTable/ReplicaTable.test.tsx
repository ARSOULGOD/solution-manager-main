import { describe, expect, it } from 'vitest';

import { DIVERGED_REPLICAS, ROLLOUT_REPLICAS } from '@shared/testdoubles/data/designer';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { ReplicaTable } from './ReplicaTable';

describe('ReplicaTable', () => {
  it('renders one row per replica, never a single spinner', () => {
    renderWithProviders(<ReplicaTable replicas={ROLLOUT_REPLICAS} />);
    for (const replica of ROLLOUT_REPLICAS) {
      expect(screen.getByText(replica.id)).not.toBeNull();
    }
  });

  it('flags both invented column names in place, on the header', () => {
    renderWithProviders(<ReplicaTable replicas={ROLLOUT_REPLICAS} />);
    expect(screen.getByText('Rendered config version')).not.toBeNull();
    expect(screen.getByText('Restart observed')).not.toBeNull();
    expect(
      screen.getByRole('button', { name: /Invented column name — the contract names this/ }),
    ).not.toBeNull();
    expect(
      screen.getByRole('button', { name: /Invented column name — same caveat/ }),
    ).not.toBeNull();
  });

  it('renders each replica health as its shared vocabulary entry, not a local copy', () => {
    renderWithProviders(<ReplicaTable replicas={ROLLOUT_REPLICAS} />);
    expect(screen.getAllByText('Healthy').length).toBeGreaterThan(0);
    expect(screen.getByText('Restart expected')).not.toBeNull();
    expect(screen.getByText('Inactive replica')).not.toBeNull();
  });

  it('marks restart-observed pending distinctly from observed', () => {
    renderWithProviders(<ReplicaTable replicas={ROLLOUT_REPLICAS} />);
    expect(screen.getAllByText('Observed').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Pending').length).toBeGreaterThan(0);
  });

  it('highlights only the minority-generation replicas, in a colour distinct from the majority', () => {
    renderWithProviders(<ReplicaTable replicas={DIVERGED_REPLICAS} />);
    // DIVERGED_REPLICAS: ledger-0/1 on gen-41 (majority, first-seen),
    // ledger-2/3 on gen-40 (minority) — only the latter pair is flagged.
    const majorityCell = screen.getAllByText('gen-41')[0];
    const minorityCell = screen.getAllByText('gen-40')[0];
    expect(majorityCell).toBeDefined();
    expect(minorityCell).toBeDefined();
    const majorityColor = majorityCell !== undefined ? getComputedStyle(majorityCell).color : '';
    const minorityColor = minorityCell !== undefined ? getComputedStyle(minorityCell).color : '';
    expect(minorityColor).not.toBe(majorityColor);
  });

  it('does not flag any replica as odd when every generation agrees', () => {
    renderWithProviders(<ReplicaTable replicas={[ROLLOUT_REPLICAS[0]!, ROLLOUT_REPLICAS[1]!]} />);
    const cell = screen.getAllByText('gen-41')[0];
    expect(cell).toBeDefined();
    expect(getComputedStyle(cell!).fontWeight).not.toBe('600');
  });
});
