import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { TargetRow } from '@shared/testdoubles/data/targets';

import { renderWithProviders, screen, waitFor } from '../../test/renderWithProviders';

import { TargetsPreview } from './TargetsPreview';
import type { TargetsRepository } from './targetsRepository';

const TARGETS: TargetRow[] = [
  {
    id: 'tgt-ledger-k8s',
    location: 'k8s://payments-prod/ledger',
    driver: 'k8s',
    locus: 'dp_remote',
    ownershipScope: ['Acme Financial', 'Payments', 'PROD'],
  },
  {
    id: 'tgt-quotes-vm',
    location: 'vm://trading-quotes-01.internal',
    driver: 'systemd',
    locus: 'host_local',
    ownershipScope: ['Acme Financial', 'Trading', 'PROD'],
  },
];

function stubRepository(): TargetsRepository {
  return { list: () => Promise.resolve(TARGETS) };
}

const modes = ['light', 'dark'] as const;

describe('TargetsPreview a11y', () => {
  it.each(modes)('the loaded preview has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<TargetsPreview repository={stubRepository()} />, {
      mode,
    });
    await waitFor(() => {
      expect(screen.getByRole('row', { name: /tgt-ledger-k8s/ })).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});
