/**
 * Mirrors `DeclareInstanceDialog.a11y.test.tsx`'s shape (both themes, plus
 * a pass with validation errors showing) — `renderWithProviders` gives the
 * theme/query-client wrapper the drawer's own `Dialog` needs.
 */
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import remoteServerDescriptor from '@contracts/connection-types/remote-server.json';
import solaceBrokerDescriptor from '@contracts/connection-types/solace-broker.json';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import type { ConnectionDescriptor, ConnectionRow } from '../connectionsRepository';

import { ConnectionDrawer } from './ConnectionDrawer';

const modes = ['light', 'dark'] as const;

const BROKER_DESCRIPTOR = solaceBrokerDescriptor as ConnectionDescriptor;
const REMOTE_DESCRIPTOR = remoteServerDescriptor as ConnectionDescriptor;

const BROKER: ConnectionRow = {
  connectionId: 'conn-7',
  name: 'prod-broker-01',
  connectionType: 'solace-broker',
  ownershipScope: { environmentId: 'env-1' },
  attributes: {
    host: 'tcps://broker.acme.internal',
    port: 55443,
    vpn: 'payments-prod',
    username: 'svc-cache',
    password: { set: true },
  },
  active: true,
  usedByCount: 3,
};

const SFTP: ConnectionRow = {
  connectionId: 'conn-12',
  name: 'sftp-inbound-prod',
  connectionType: 'remote-server',
  ownershipScope: { environmentId: 'env-1' },
  attributes: {
    host: 'sftp.acme.internal',
    port: 22,
    username: 'ingest',
    authMethod: 'private-key',
    privateKey: { set: true },
  },
  active: true,
  usedByCount: 2,
};

function renderDrawer(mode: (typeof modes)[number], connection: ConnectionRow, descriptor: ConnectionDescriptor) {
  return renderWithProviders(
    <ConnectionDrawer
      connection={connection}
      descriptor={descriptor}
      onSave={() => Promise.resolve()}
      onClose={() => undefined}
    />,
    { mode },
  );
}

describe('ConnectionDrawer a11y', () => {
  it.each(modes)('a set/unset secret connection has no violations in %s mode', async (mode) => {
    const { container } = renderDrawer(mode, BROKER, BROKER_DESCRIPTOR);
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('a private-key connection has no violations in %s mode', async (mode) => {
    const { container } = renderDrawer(mode, SFTP, REMOTE_DESCRIPTOR);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('has no violations while typing a replacement secret', async () => {
    const { container } = renderDrawer('light', BROKER, BROKER_DESCRIPTOR);
    await userEvent.type(screen.getByLabelText('Password'), 'new-password');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('has no violations while showing validation errors', async () => {
    const { container } = renderDrawer('light', BROKER, BROKER_DESCRIPTOR);
    await userEvent.clear(screen.getByLabelText('Host'));
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await axe(container)).toHaveNoViolations();
  });
});
