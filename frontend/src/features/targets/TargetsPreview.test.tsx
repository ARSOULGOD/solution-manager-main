import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { TargetRow } from '@shared/testdoubles/data/targets';

import { renderWithProviders, screen, within } from '../../test/renderWithProviders';

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
  {
    id: 'tgt-eu-observe',
    location: 'k8s://logistics-eu-west/obs',
    driver: 'k8s',
    locus: 'observe_only',
    ownershipScope: ['Acme Financial', 'Logistics'],
  },
];

function stubRepository(overrides: Partial<TargetsRepository> = {}): TargetsRepository {
  return {
    list: () => Promise.resolve(TARGETS),
    ...overrides,
  };
}

describe('TargetsPreview', () => {
  it('lists each target with its location, driver, locus and ownership scope', async () => {
    renderWithProviders(<TargetsPreview repository={stubRepository()} />);

    const row = within(await screen.findByRole('row', { name: /tgt-ledger-k8s/ }));
    expect(row.getByText('k8s://payments-prod/ledger')).not.toBeNull();
    expect(row.getByText('Kubernetes')).not.toBeNull();
    expect(row.getByText('dp-remote')).not.toBeNull();
    expect(row.getByText('Acme Financial / Payments / PROD')).not.toBeNull();

    expect(screen.getByText('host-local')).not.toBeNull();
    expect(screen.getByText('observe-only')).not.toBeNull();
  });

  it('states the control_locus security meaning', async () => {
    renderWithProviders(<TargetsPreview repository={stubRepository()} />);

    await screen.findByRole('row', { name: /tgt-ledger-k8s/ });
    expect(
      screen.getByText('control_locus is a security boundary worth surfacing'),
    ).not.toBeNull();
    expect(
      screen.getByText(/stores host credentials centrally.*it stores none.*never writes here/s),
    ).not.toBeNull();
  });

  it('is read-only — no credential entry and nothing that writes to a target', async () => {
    renderWithProviders(<TargetsPreview repository={stubRepository()} />);

    await screen.findByRole('row', { name: /tgt-ledger-k8s/ });
    // No form controls of any kind.
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.queryByRole('combobox')).toBeNull();
    // The only `role="button"` elements are the locus info tooltips (their
    // accessible name is the tip text) — never an action control.
    for (const control of screen.queryAllByRole('button')) {
      expect(control).toHaveAccessibleName(/Solution Manager/);
    }
    expect(screen.getByText('Not designed here — blocked')).not.toBeNull();
    expect(screen.getByText(/Nothing on this screen writes to a target/)).not.toBeNull();
  });

  it('renders the Target and Binding model field lists', async () => {
    renderWithProviders(<TargetsPreview repository={stubRepository()} />);

    await screen.findByRole('row', { name: /tgt-ledger-k8s/ });
    expect(screen.getByText(/control_locus · ownership_scope/)).not.toBeNull();
    expect(screen.getByText(/render_outputs\[\] · runtime_attachments\[\]/)).not.toBeNull();
  });

  it('shows an error panel with retry when targets fail to load', async () => {
    const list = vi
      .fn<TargetsRepository['list']>()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValue(TARGETS);
    renderWithProviders(<TargetsPreview repository={stubRepository({ list })} />);

    expect(await screen.findByText('Targets could not be loaded')).not.toBeNull();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('row', { name: /tgt-ledger-k8s/ })).not.toBeNull();
  });
});
