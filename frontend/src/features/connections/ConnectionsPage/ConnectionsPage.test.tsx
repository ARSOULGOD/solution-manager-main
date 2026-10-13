import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import remoteServerDescriptor from '@contracts/connection-types/remote-server.json';
import solaceBrokerDescriptor from '@contracts/connection-types/solace-broker.json';

import { renderWithProviders as render, screen, within } from '../../../test/renderWithProviders';
import type { ConnectionDescriptor, ConnectionRow, ConnectionsRepository } from '../connectionsRepository';

import { ConnectionsPage } from './ConnectionsPage';

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
  {
    connectionId: 'conn-8',
    name: 'stage-broker-01',
    connectionType: 'solace-broker',
    ownershipScope: { environmentId: 'env-2' },
    attributes: { host: 'tcps://broker-st.acme.internal', port: 55443, vpn: 'payments-stage', password: { set: true } },
    active: true,
    usedByCount: 1,
  },
  {
    connectionId: 'conn-9',
    name: 'retired-broker',
    connectionType: 'solace-broker',
    ownershipScope: { environmentId: 'env-3' },
    attributes: { host: 'tcps://old.acme.internal', port: 55555, vpn: 'default' },
    active: false,
    usedByCount: 0,
  },
  {
    connectionId: 'conn-12',
    name: 'sftp-inbound-prod',
    connectionType: 'remote-server',
    ownershipScope: { environmentId: 'env-1' },
    attributes: { host: 'sftp.acme.internal', port: 22, username: 'ingest', authMethod: 'private-key', privateKey: { set: true } },
    active: true,
    usedByCount: 2,
  },
  {
    connectionId: 'conn-13',
    name: 'ftp-archive',
    connectionType: 'remote-server',
    ownershipScope: { environmentId: 'env-2' },
    attributes: { host: 'archive.acme.internal', port: 22, username: 'archive', authMethod: 'basic', password: { set: true } },
    active: true,
    usedByCount: 0,
  },
];

function repo(rows = ROWS): ConnectionsRepository {
  return {
    list: vi.fn().mockResolvedValue([...rows]),
    create: vi.fn(), update: vi.fn(),
    deactivate: vi.fn(), remove: vi.fn(),
    getConnectionTypes: vi.fn().mockResolvedValue(DESCRIPTORS),
  };
}

// 3 solace-broker rows (2 active: prod-broker-01, stage-broker-01; 1
// inactive: retired-broker) and 2 remote-server rows (both active). Tab
// counts are ACTIVE-only — what the tab shows by default — so "Solace
// brokers (2)", not "(3)": counting all statuses would disagree with the
// closed tab.
describe('ConnectionsPage', () => {
  it('shows one tab per connection type, counted by active rows', async () => {
    render(<ConnectionsPage repository={repo()} />);
    expect(await screen.findByRole('tab', { name: /Solace brokers \(2\)/ })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Remote servers \(2\)/ })).toBeInTheDocument();
  });

  it('renders the columns the active tab\'s descriptor declares', async () => {
    render(<ConnectionsPage repository={repo()} />);
    expect(await screen.findByRole('columnheader', { name: 'Message VPN' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: /Remote servers/ }));
    expect(await screen.findByRole('columnheader', { name: 'Authentication' })).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Message VPN' })).not.toBeInTheDocument();
  });

  it('shows the reference count, which is what gates deletion', async () => {
    render(<ConnectionsPage repository={repo()} />);
    const row = await screen.findByRole('row', { name: /prod-broker-01/ });
    expect(within(row).getByText('3')).toBeInTheDocument();
  });

  it('renders an empty state rather than a bare table when a type has no connections', async () => {
    render(<ConnectionsPage repository={repo([])} />);
    expect(await screen.findByText(/No connections yet/)).toBeInTheDocument();
  });

  it('never renders a secret value', async () => {
    const { container } = render(<ConnectionsPage repository={repo()} />);
    await screen.findByRole('row', { name: /prod-broker-01/ });
    expect(container.textContent).not.toMatch(/password|privateKey/i);
  });

  it('hides inactive rows by default and reveals them via the toggle', async () => {
    render(<ConnectionsPage repository={repo()} />);
    await screen.findByRole('row', { name: /prod-broker-01/ });
    expect(screen.queryByText('retired-broker')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('switch', { name: /Show inactive/ }));
    expect(await screen.findByText('retired-broker')).toBeInTheDocument();
  });

  it('calls onOpen with the row on row click', async () => {
    const onOpen = vi.fn();
    render(<ConnectionsPage repository={repo()} onOpen={onOpen} />);
    const row = await screen.findByRole('row', { name: /prod-broker-01/ });
    await userEvent.click(row);
    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ connectionId: 'conn-7' }));
  });

  it('calls onCreate with the active type from the New connection button', async () => {
    const onCreate = vi.fn();
    render(<ConnectionsPage repository={repo()} onCreate={onCreate} />);
    await screen.findByRole('row', { name: /prod-broker-01/ });
    await userEvent.click(screen.getByRole('button', { name: /New connection/ }));
    expect(onCreate).toHaveBeenCalledWith('solace-broker');
  });
});
