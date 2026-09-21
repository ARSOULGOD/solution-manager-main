import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import type { HealthSnapshot } from '../governanceRepository';

import { ReplicaCards } from './ReplicaCards';

const REPLICAS: HealthSnapshot['replicas'] = [
  { id: 'ledger-0', health: 'healthy' },
  { id: 'ledger-1', health: 'healthy' },
  { id: 'ledger-2', health: 'inactive_replica' },
];

describe('ReplicaCards', () => {
  it('renders one card per replica', () => {
    renderWithProviders(<ReplicaCards replicas={REPLICAS} />);
    expect(screen.getByText('ledger-0')).not.toBeNull();
    expect(screen.getByText('ledger-1')).not.toBeNull();
    expect(screen.getByText('ledger-2')).not.toBeNull();
  });

  it('marks a standby card distinctly and states it is excluded from availability math', () => {
    renderWithProviders(<ReplicaCards replicas={REPLICAS} />);
    const standbyCard = screen.getByText('ledger-2').closest('[data-standby]');
    expect(standbyCard).not.toBeNull();
    expect(screen.getByText(/excluded from availability math; not a healthy zero/i)).not.toBeNull();
  });

  it('carries no standby marker for a healthy replica', () => {
    renderWithProviders(<ReplicaCards replicas={REPLICAS} />);
    expect(screen.getByText('ledger-0').closest('[data-standby]')).toBeNull();
  });

  it('renders no section at all when there are no replicas', () => {
    const { container } = renderWithProviders(<ReplicaCards replicas={[]} />);
    expect(container.firstChild).toBeNull();
  });
});
