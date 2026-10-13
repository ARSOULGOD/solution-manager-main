import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import type { HealthSnapshot } from '../governanceRepository';

import { ReplicaCards } from './ReplicaCards';

const CHECKED_AT = '2026-10-08T00:00:00Z';

const PLACEMENTS: HealthSnapshot['placements'] = [
  { placementId: 'ledger-0', state: 'GREEN', checkedAt: CHECKED_AT, stateVersion: 1 },
  { placementId: 'ledger-1', state: 'GREEN', checkedAt: CHECKED_AT, stateVersion: 1 },
  { placementId: 'ledger-2', state: 'INACTIVE_REPLICA', checkedAt: CHECKED_AT, stateVersion: 1 },
];

describe('ReplicaCards', () => {
  it('renders one card per placement', () => {
    renderWithProviders(<ReplicaCards placements={PLACEMENTS} />);
    expect(screen.getByText('ledger-0')).not.toBeNull();
    expect(screen.getByText('ledger-1')).not.toBeNull();
    expect(screen.getByText('ledger-2')).not.toBeNull();
  });

  it('marks a standby card distinctly and states it is excluded from availability math', () => {
    renderWithProviders(<ReplicaCards placements={PLACEMENTS} />);
    const standbyCard = screen.getByText('ledger-2').closest('[data-standby]');
    expect(standbyCard).not.toBeNull();
    expect(screen.getByText(/excluded from availability math; not a healthy zero/i)).not.toBeNull();
  });

  it('carries no standby marker for a healthy placement', () => {
    renderWithProviders(<ReplicaCards placements={PLACEMENTS} />);
    expect(screen.getByText('ledger-0').closest('[data-standby]')).toBeNull();
  });

  it('gives unreachable a distinct note from a confirmed failure', () => {
    renderWithProviders(
      <ReplicaCards
        placements={[
          { placementId: 'p1', state: 'unreachable', checkedAt: CHECKED_AT, stateVersion: 1 },
        ]}
      />,
    );
    expect(screen.getByText('Unreachable')).not.toBeNull();
    expect(screen.getByText(/Host alert/i)).not.toBeNull();
  });

  it('renders no section at all when there are no placements', () => {
    const { container } = renderWithProviders(<ReplicaCards placements={[]} />);
    expect(container.firstChild).toBeNull();
  });
});
