import { describe, expect, it, vi } from 'vitest';

import { healthVocab } from '@shared/vocab';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import type { HealthRollupState, HealthSnapshot } from '../governanceRepository';
import { ROLLUP_TO_HEALTH_KEY } from '../healthRollup';

import { HealthHero } from './HealthHero';

function snapshotFor(rollup: HealthRollupState): HealthSnapshot {
  return {
    rollup,
    availability: rollup === 'NOT_APPLICABLE' ? 'not-declared' : 'declared',
    replicas: [],
  };
}

const ALL_ROLLUPS: readonly HealthRollupState[] = [
  'GREEN',
  'RED',
  'RESTART_EXPECTED',
  'INACTIVE_REPLICA',
  'NOT_APPLICABLE',
];

describe('HealthHero', () => {
  it.each(ALL_ROLLUPS)('renders the healthVocab label for rollup %s', (rollup) => {
    renderWithProviders(<HealthHero snapshot={snapshotFor(rollup)} onOpenMonitoring={vi.fn()} />);
    const entry = healthVocab[ROLLUP_TO_HEALTH_KEY[rollup]];
    expect(screen.getByText(entry.label)).not.toBeNull();
    expect(screen.getByText('rollup')).not.toBeNull();
  });

  it('never gives NOT_APPLICABLE the error tone', () => {
    renderWithProviders(
      <HealthHero snapshot={snapshotFor('NOT_APPLICABLE')} onOpenMonitoring={vi.fn()} />,
    );
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('makes the CTA primary (contained) only when the rollup is RED', () => {
    renderWithProviders(<HealthHero snapshot={snapshotFor('RED')} onOpenMonitoring={vi.fn()} />);
    const cta = screen.getByRole('button', { name: 'Open in Monitoring' });
    expect(cta.className).toContain('MuiButton-contained');
  });

  it('leaves the CTA outlined for a GREEN rollup', () => {
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

  it('renders no score, gauge or progress element for any rollup', () => {
    for (const rollup of ALL_ROLLUPS) {
      const { unmount } = renderWithProviders(
        <HealthHero snapshot={snapshotFor(rollup)} onOpenMonitoring={vi.fn()} />,
      );
      expect(document.querySelector('[role="meter"], [role="progressbar"]')).toBeNull();
      unmount();
    }
  });
});
