import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import remoteServerDescriptor from '@contracts/connection-types/remote-server.json';
import solaceBrokerDescriptor from '@contracts/connection-types/solace-broker.json';
import { ApiError } from '@shared/api';

import type { ConnectionDescriptor, ConnectionRow } from '../connectionsRepository';

import { ConnectionDrawer } from './ConnectionDrawer';

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

const UNUSED_BROKER: ConnectionRow = {
  connectionId: 'conn-9',
  name: 'retired-broker',
  connectionType: 'solace-broker',
  ownershipScope: { environmentId: 'env-1' },
  attributes: { host: 'tcps://old.acme.internal', port: 55555, vpn: 'default' },
  active: false,
  usedByCount: 0,
};

describe('ConnectionDrawer', () => {
  it('renders fields from the descriptor, in declared order', async () => {
    render(
      <ConnectionDrawer
        connection={BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    expect(await screen.findByLabelText('Host')).toHaveValue('tcps://broker.acme.internal');
    expect(screen.getByLabelText('Message VPN')).toHaveValue('payments-prod');
  });

  it('shows a set secret as an empty password field, never as a value', () => {
    render(
      <ConnectionDrawer
        connection={BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    const field = screen.getByLabelText('Password');
    expect(field).toHaveValue('');
    expect(field).toHaveAttribute('type', 'password');
    expect(screen.getByText(/Currently set/)).toBeInTheDocument();
  });

  it('swaps the credential block when the auth discriminator changes', async () => {
    render(
      <ConnectionDrawer
        connection={SFTP}
        descriptor={REMOTE_DESCRIPTOR}
        onSave={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    expect(await screen.findByLabelText('Private key')).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Authentication'), 'basic');
    expect(screen.queryByLabelText('Private key')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
  });

  it('renders private keys as a multiline field and preserves pasted line breaks', async () => {
    const privateKey = '-----BEGIN OPENSSH PRIVATE KEY-----\\nline-one\\nline-two\\n-----END OPENSSH PRIVATE KEY-----';
    render(
      <ConnectionDrawer
        connection={{ ...SFTP, attributes: { ...SFTP.attributes, authMethod: 'private-key', privateKey: undefined } }}
        descriptor={REMOTE_DESCRIPTOR}
        onSave={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    const field = screen.getByLabelText('Private key');
    expect(field).toHaveAttribute('rows', '8');
    await userEvent.type(field, privateKey);
    expect(field).toHaveValue(privateKey);
  });

  it('does not render deployment envelope fields on a remote-server Connection', () => {
    render(
      <ConnectionDrawer
        connection={SFTP}
        descriptor={REMOTE_DESCRIPTOR}
        onSave={vi.fn()}
        onClose={vi.fn()}
      />,
    );

    expect(screen.queryByLabelText('Allowed config root')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Allowed container prefix')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Host key fingerprint')).not.toBeInTheDocument();
  });

  it('warns how many instances a save will affect, before calling onSave', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <ConnectionDrawer
        connection={BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={onSave}
        onClose={vi.fn()}
      />,
    );
    await userEvent.clear(screen.getByLabelText('Host'));
    await userEvent.type(screen.getByLabelText('Host'), 'tcps://new.acme.internal');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText(/3 instances reference this connection/)).toBeInTheDocument();
    expect(onSave).not.toHaveBeenCalled(); // confirmation first
  });

  it('sends testBeforeSave: false from "Save changes" and true from "Test & save"', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <ConnectionDrawer
        connection={UNUSED_BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={onSave}
        onClose={vi.fn()}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ testBeforeSave: false }));

    await userEvent.click(screen.getByRole('button', { name: 'Test & save' }));
    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ testBeforeSave: true }));
  });

  it('keeps the drawer open and shows the rejection reason inline on 422 CONNECTION_TEST_FAILED, preserving entered values', async () => {
    const onSave = vi.fn().mockRejectedValue(
      new ApiError({ status: 422, code: 'CONNECTION_TEST_FAILED', message: 'Connection refused by peer.' }),
    );
    const onClose = vi.fn();
    render(
      <ConnectionDrawer
        connection={UNUSED_BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={onSave}
        onClose={onClose}
      />,
    );
    await userEvent.clear(screen.getByLabelText('Host'));
    await userEvent.type(screen.getByLabelText('Host'), 'tcps://unreachable.example');
    await userEvent.click(screen.getByRole('button', { name: 'Test & save' }));

    expect(await screen.findByText('Connection refused by peer.')).toBeInTheDocument();
    expect(screen.getByLabelText('Host')).toHaveValue('tcps://unreachable.example');
    expect(onClose).not.toHaveBeenCalled();
  });

  it('keeps the drawer open and shows a rate-limit message inline on 429 RATE_LIMITED', async () => {
    const onSave = vi.fn().mockRejectedValue(
      new ApiError({ status: 429, code: 'RATE_LIMITED', message: 'Too many attempts.', retryAfterSeconds: 30 }),
    );
    const onClose = vi.fn();
    render(
      <ConnectionDrawer
        connection={UNUSED_BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={onSave}
        onClose={onClose}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Test & save' }));

    expect(await screen.findByText(/Too many attempts\./)).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('keeps the drawer open and shows a service-unavailable message inline on 503 SERVICE_UNAVAILABLE', async () => {
    const onSave = vi.fn().mockRejectedValue(
      new ApiError({ status: 503, code: 'SERVICE_UNAVAILABLE', message: 'Could not reach the peer.' }),
    );
    const onClose = vi.fn();
    render(
      <ConnectionDrawer
        connection={UNUSED_BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={onSave}
        onClose={onClose}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Test & save' }));

    expect(await screen.findByText(/Could not reach the peer\./)).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('omits an untouched secret attribute on save, never resending its prior value (I6 rotation)', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <ConnectionDrawer
        connection={BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={onSave}
        onClose={vi.fn()}
        onDeactivate={vi.fn()}
      />,
    );
    // Rename only — the password field is left blank (not retyped).
    await userEvent.clear(screen.getByLabelText('Host'));
    await userEvent.type(screen.getByLabelText('Host'), 'tcps://renamed.acme.internal');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText(/3 instances reference this connection/)).toBeInTheDocument();
    await userEvent.click(screen.getAllByRole('button', { name: 'Save changes' }).at(-1)!);

    expect(onSave).toHaveBeenCalledTimes(1);
    const sentAttributes = onSave.mock.calls[0]![0].attributes as Record<string, unknown>;
    expect(sentAttributes).not.toHaveProperty('password');
    expect(sentAttributes.host).toBe('tcps://renamed.acme.internal');
  });

  it('rotates a secret by sending the newly typed value when the password field is filled in', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <ConnectionDrawer
        connection={UNUSED_BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={onSave}
        onClose={vi.fn()}
      />,
    );
    await userEvent.type(screen.getByLabelText('Password'), 'new-secret-value');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(onSave).toHaveBeenCalledTimes(1);
    const sentAttributes = onSave.mock.calls[0]![0].attributes as Record<string, unknown>;
    expect(sentAttributes.password).toBe('new-secret-value');
  });

  it('does not render the redundant standalone Test connection action', () => {
    render(
      <ConnectionDrawer
        connection={BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    expect(screen.queryByRole('button', { name: /^Test connection$/i })).not.toBeInTheDocument();
  });

  it('omits the status/delete row when no callback is given (create mode)', () => {
    render(
      <ConnectionDrawer
        connection={BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Deactivate' })).not.toBeInTheDocument();
  });

  it('deactivates via the one-directional action', async () => {
    const onDeactivate = vi.fn();
    render(
      <ConnectionDrawer
        connection={BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={vi.fn()}
        onClose={vi.fn()}
        onDeactivate={onDeactivate}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }));
    expect(onDeactivate).toHaveBeenCalledWith();
  });

  it('disables Deactivate once already inactive', () => {
    render(
      <ConnectionDrawer
        connection={UNUSED_BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={vi.fn()}
        onClose={vi.fn()}
        onDeactivate={vi.fn()}
      />,
    );
    expect(screen.getByRole('button', { name: 'Deactivate' })).toBeDisabled();
  });

  it('disables Delete and explains why when the connection is in use', () => {
    render(
      <ConnectionDrawer
        connection={BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={vi.fn()}
        onClose={vi.fn()}
        onDelete={vi.fn()}
      />,
    );
    const button = screen.getByRole('button', { name: 'Delete' });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription(/Deactivate it instead/);
    expect(screen.getByText(/Used by 3 instances/)).toBeInTheDocument();
  });

  it('confirms before deleting an unused connection', async () => {
    const onDelete = vi.fn();
    render(
      <ConnectionDrawer
        connection={UNUSED_BROKER}
        descriptor={BROKER_DESCRIPTOR}
        onSave={vi.fn()}
        onClose={vi.fn()}
        onDelete={onDelete}
      />,
    );
    const openConfirm = screen.getByRole('button', { name: 'Delete' });
    expect(openConfirm).toBeEnabled();
    await userEvent.click(openConfirm);
    expect(onDelete).not.toHaveBeenCalled(); // confirmation first
    expect(await screen.findByText('Delete this connection?')).toBeInTheDocument();
    const confirm = screen.getAllByRole('button', { name: 'Delete' });
    await userEvent.click(confirm[confirm.length - 1]!); // the dialog's own confirm button
    expect(onDelete).toHaveBeenCalledTimes(1);
  });
});
