import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import type { HealthSnapshot, HealthState } from '../governanceRepository';
import { HEALTH_STATE_VOCAB } from '../healthRollup';

import { HealthHero } from './HealthHero';

function snapshotFor(state: HealthState): HealthSnapshot {
  return { placements: [{ placementId: 'p1', state, checkedAt: '2026-10-08T00:00:00Z', stateVersion: 1 }] };
}

const ALL_STATES: readonly HealthState[] = [
  'GREEN',
  'RED',
  'RESTART_EXPECTED',
  'INACTIVE_REPLICA',
  'NOT_APPLICABLE',
  'unreachable',
  'not_exposed',
  'missing',
  'bootstrap',
];

describe('HealthHero', () => {
  it.each(ALL_STATES)('renders the vocab label for state %s', (state) => {
    renderWithProviders(<HealthHero snapshot={snapshotFor(state)} onOpenMonitoring={vi.fn()} />);
    const entry = HEALTH_STATE_VOCAB[state];
    expect(screen.getByText(entry.label)).not.toBeNull();
    expect(screen.getByText('rollup')).not.toBeNull();
  });

  it('never gives NOT_APPLICABLE the error tone', () => {
    renderWithProviders(
      <HealthHero snapshot={snapshotFor('NOT_APPLICABLE')} onOpenMonitoring={vi.fn()} />,
    );
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('never gives unreachable the same label as RED', () => {
    renderWithProviders(
      <HealthHero snapshot={snapshotFor('unreachable')} onOpenMonitoring={vi.fn()} />,
    );
    expect(screen.getByText('Unreachable')).not.toBeNull();
    expect(screen.queryByText('Not responding')).toBeNull();
  });

  it('makes the CTA primary (contained) only for an alert-grade state (RED/unreachable/missing)', () => {
    renderWithProviders(<HealthHero snapshot={snapshotFor('RED')} onOpenMonitoring={vi.fn()} />);
    const cta = screen.getByRole('button', { name: 'Open in Monitoring' });
    expect(cta.className).toContain('MuiButton-contained');
  });

  it('leaves the CTA outlined for a GREEN state', () => {
    renderWithProviders(<HealthHero snapshot={snapshotFor('GREEN')} onOpenMonitoring={vi.fn()} />);
    const cta = screen.getByRole('button', { name: 'Open in Monitoring' });
    expect(cta.className).toContain('MuiButton-outlined');
  });

  it('fires onOpenMonitoring when the CTA is clicked', () => {
    const onOpenMonitoring = vi.fn();
    renderWithProviders(
      <HealthHero snapshot={snapshotFor('RED')} onOpenMonitoring={onOpenMonitoring} />,
    );
    screen.getByRole('button', { name: 'Open in Monitoring' }).click();
    expect(onOpenMonitoring).toHaveBeenCalledOnce();
  });

  it('derives the worst state across placements when more than one is live', () => {
    const snapshot: HealthSnapshot = {
      placements: [
        { placementId: 'p1', state: 'GREEN', checkedAt: '2026-10-08T00:00:00Z', stateVersion: 1 },
        { placementId: 'p2', state: 'unreachable', checkedAt: '2026-10-08T00:00:00Z', stateVersion: 1 },
      ],
    };
    renderWithProviders(<HealthHero snapshot={snapshot} onOpenMonitoring={vi.fn()} />);
    expect(screen.getByText('Unreachable')).not.toBeNull();
  });

  it('renders no score, gauge or progress element for any state', () => {
    for (const state of ALL_STATES) {
      const { unmount } = renderWithProviders(
        <HealthHero snapshot={snapshotFor(state)} onOpenMonitoring={vi.fn()} />,
      );
      expect(document.querySelector('[role="meter"], [role="progressbar"]')).toBeNull();
      unmount();
    }
  });
});
