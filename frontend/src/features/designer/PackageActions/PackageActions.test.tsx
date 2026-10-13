import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { ViewAction, ViewSection } from '@contracts/view-model/types';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { ActionResult, ActionsRepository } from '../actionsRepository';

import { PackageActions } from './PackageActions';

function stubRepository(run: ActionsRepository['run']): ActionsRepository {
  return { run };
}

function sectionsWith(...actions: readonly ViewAction[]): readonly ViewSection[] {
  return [{ title: 'General', order: 0, fields: [], actions }];
}

const TEST_CONNECTION: ViewAction = {
  key: 'TEST_BROKER_CONNECTION',
  kind: 'test-connection',
  enabled: true,
  inputs: ['solace.host', 'solace.vpn', 'solace.username', 'solace.password'],
};

describe('PackageActions', () => {
  it('renders nothing when no section declares an action', () => {
    const { container } = renderWithProviders(
      <PackageActions
        instanceId="cache-prod-01"
        sections={sectionsWith()}
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
      />,
    );

    expect(container.firstChild).toBeNull();
  });

  it('flattens actions across every section, not just the first', () => {
    const manageAction: ViewAction = {
      key: 'MANAGE_CONNECTION',
      kind: 'manage-catalog-entry',
      enabled: true,
      inputs: ['ftp.host'],
    };
    const sections: readonly ViewSection[] = [
      { title: 'Broker', order: 0, fields: [], actions: [TEST_CONNECTION] },
      { title: 'Remote', order: 1, fields: [], actions: [manageAction] },
    ];

    renderWithProviders(
      <PackageActions
        instanceId="cache-prod-01"
        sections={sections}
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
      />,
    );

    expect(screen.getByRole('button', { name: 'Test connection' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Manage catalog entry' })).not.toBeNull();
  });

  it('names the action label and its inputs on screen', () => {
    renderWithProviders(
      <PackageActions
        instanceId="cache-prod-01"
        sections={sectionsWith(TEST_CONNECTION)}
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
      />,
    );

    expect(screen.getByRole('button', { name: 'Test connection' })).not.toBeNull();
    expect(
      screen.getByText(/solace\.host, solace\.vpn, solace\.username, solace\.password/),
    ).not.toBeNull();
  });

  it('runs the action by instanceId and key only, never a field value', async () => {
    const run = vi.fn<ActionsRepository['run']>(() =>
      Promise.resolve<ActionResult>({ ok: true, message: 'Connection succeeded.' }),
    );

    renderWithProviders(
      <PackageActions
        instanceId="cache-prod-01"
        sections={sectionsWith(TEST_CONNECTION)}
        repository={stubRepository(run)}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Test connection' }));

    await waitFor(() => {
      expect(run).toHaveBeenCalledWith('cache-prod-01', 'TEST_BROKER_CONNECTION');
    });
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('renders the server verdict message on success', async () => {
    renderWithProviders(
      <PackageActions
        instanceId="cache-prod-01"
        sections={sectionsWith(TEST_CONNECTION)}
        repository={stubRepository(() =>
          Promise.resolve<ActionResult>({ ok: true, message: 'Connection succeeded.' }),
        )}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Test connection' }));

    expect(await screen.findByText('Connection succeeded.')).not.toBeNull();
  });

  it('renders a negative verdict distinctly from a transport failure', async () => {
    renderWithProviders(
      <PackageActions
        instanceId="cache-prod-01"
        sections={sectionsWith(TEST_CONNECTION)}
        repository={stubRepository(() =>
          Promise.resolve<ActionResult>({ ok: false, message: 'Connection refused.' }),
        )}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Test connection' }));

    const alert = await screen.findByText('Connection refused.');
    expect(alert.closest('[role="alert"], [role="status"]')).not.toBeNull();
  });

  it('renders a generic error when the request itself fails', async () => {
    renderWithProviders(
      <PackageActions
        instanceId="cache-prod-01"
        sections={sectionsWith(TEST_CONNECTION)}
        repository={stubRepository(() => Promise.reject(new Error('network down')))}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Test connection' }));

    expect(
      await screen.findByText('The platform could not run this action. Try again.'),
    ).not.toBeNull();
  });

  it('disables the button while an action is unavailable (its own when gate)', () => {
    renderWithProviders(
      <PackageActions
        instanceId="cache-prod-01"
        sections={sectionsWith({ ...TEST_CONNECTION, enabled: false })}
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
      />,
    );

    expect(screen.getByRole('button', { name: 'Test connection' }).hasAttribute('disabled')).toBe(
      true,
    );
  });

  it('disables every action when editing is blocked', () => {
    renderWithProviders(
      <PackageActions
        instanceId="cache-prod-01"
        sections={sectionsWith(TEST_CONNECTION)}
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        disabled
      />,
    );

    expect(screen.getByRole('button', { name: 'Test connection' }).hasAttribute('disabled')).toBe(
      true,
    );
  });
});
