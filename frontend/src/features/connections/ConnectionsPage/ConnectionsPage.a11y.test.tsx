/**
 * Follows `InstancesPage.a11y.test.tsx`'s shape (real repository, populated
 * vs. empty, both themes). Uses a stub repository (the same way
 * `ConnectionsPage.test.tsx` and `connectionsRepository.test.ts` do) rather
 * than `createConnectionsRepository(createCpClient(...))`, which would hit
 * the network and fail rather than exercising a11y.
 */
import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';

import remoteServerDescriptor from '@contracts/connection-types/remote-server.json';
import solaceBrokerDescriptor from '@contracts/connection-types/solace-broker.json';

import { renderWithProviders, waitFor } from '../../../test/renderWithProviders';
import type { ConnectionDescriptor, ConnectionRow, ConnectionsRepository } from '../connectionsRepository';

import { ConnectionsPage } from './ConnectionsPage';

const modes = ['light', 'dark'] as const;

const DESCRIPTORS: ConnectionDescriptor[] = [
  solaceBrokerDescriptor as ConnectionDescriptor,
  remoteServerDescriptor as ConnectionDescriptor,
];

const ROWS: ConnectionRow[] = [
  {
    connectionId: 'conn-7',
    name: 'prod-broker-01',
    connectionType: 'solace-broker',
    ownershipScope: { environmentId: 'env-1' },
    attributes: { host: 'tcps://broker.acme.internal', port: 55443, vpn: 'payments-prod', password: { set: true } },
    active: true,
    usedByCount: 3,
  },
];

function stubRepository(rows: ConnectionRow[] = ROWS): ConnectionsRepository {
  return {
    list: () => Promise.resolve([...rows]),
    create: () => Promise.reject(new Error('not used')),
    update: () => Promise.reject(new Error('not used')),
    deactivate: () => Promise.reject(new Error('not used')),
    remove: () => Promise.reject(new Error('not used')),
    getConnectionTypes: vi.fn().mockResolvedValue(DESCRIPTORS),
  };
}

describe('ConnectionsPage a11y', () => {
  it.each(modes)('populated catalog has no violations in %s mode', async (mode) => {
    const { container, getByText } = renderWithProviders(
      <ConnectionsPage repository={stubRepository()} />,
      { mode },
    );

    await waitFor(() => {
      expect(getByText('prod-broker-01')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('empty state has no violations in %s mode', async (mode) => {
    const { container, getByText } = renderWithProviders(
      <ConnectionsPage repository={stubRepository([])} />,
      { mode },
    );

    await waitFor(() => {
      expect(getByText('No connections yet')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });
});
