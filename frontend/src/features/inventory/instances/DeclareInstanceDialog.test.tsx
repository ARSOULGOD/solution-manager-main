/**
 * The dialog's behaviour: what it renders, when errors appear, and the
 * exact payload it hands `onSubmit`.
 *
 * Rewritten for integration plan 007 (2026-10-09) against the real
 * `declareInstance` contract — see `DeclareInstanceDialog.tsx`'s header
 * for what the pre-007 version asked for (role, connections, driver,
 * locus, a replica array) and why none of it survived.
 *
 * Integration plan 014 (2026-10-10): the Deployment mode dropdown and the
 * remote-server Connection picker replace plan 007's target picker.
 */
import { describe, expect, it, vi } from 'vitest';

import type { Instance } from '@shared/api/contracts/instances';
import type { HierarchyRepository } from '@shared/hierarchy';
import type { Env } from '@shared/testdoubles/data/instances';

import {
  fireEvent,
  renderWithProviders,
  screen,
  waitFor,
  within,
} from '../../../test/renderWithProviders';

import type { RemoteServerConnectionRow } from './connectionPickerRepository';
import { DeclareInstanceDialog } from './DeclareInstanceDialog';
import type { TargetRow } from './targetPickerRepository';

/** What the Cache package declares (FE-118) — two versions, so nothing is preselected. */
const VERSIONS: readonly (string | number)[] = [6, 7];

const CONNECTION: RemoteServerConnectionRow = {
  connectionId: 'conn-1',
  name: 'build-host',
  connectionType: 'remote-server',
  attributes: { allowedConfigRoot: '/etc', allowedContainerPrefix: 'cache-' },
  active: true,
};

function stubConnections(rows: readonly RemoteServerConnectionRow[] = [CONNECTION]) {
  return { listRemoteServers: () => Promise.resolve([...rows]) };
}

const TARGETS: readonly TargetRow[] = [
  {
    targetId: 'target-a',
    name: 'Docker A',
    kind: 'docker',
    transport: 'ssh-docker',
    runtime: 'docker',
    ownershipScope: {},
    allowedConfigRoot: '/opt/a',
    allowedContainerPrefix: 'a-',
  },
  {
    targetId: 'target-b',
    name: 'Docker B',
    kind: 'docker',
    transport: 'ssh-docker',
    runtime: 'docker',
    ownershipScope: {},
    allowedConfigRoot: '/opt/b',
    allowedContainerPrefix: 'b-',
  },
];

function stubTargets(rows: readonly TargetRow[] = TARGETS) {
  return { list: () => Promise.resolve([...rows]) };
}

/**
 * Two organizations with DIFFERENT sub-orgs and different environments —
 * that asymmetry is what makes the reset assertions meaningful. A stub
 * where every organization had the same children could not tell a cascade
 * that resets from one that does not.
 */
function stubHierarchy(overrides: Partial<HierarchyRepository> = {}): HierarchyRepository {
  return {
    organizations: () => Promise.resolve(['Acme Financial', 'Globex']),
    subOrgs: (org: string) => Promise.resolve(org === 'Acme Financial' ? ['Payments'] : ['Platform']),
    environments: (org: string) =>
      Promise.resolve((org === 'Acme Financial' ? ['PROD', 'TEST'] : ['DEV']) as Env[]),
    environmentId: () => Promise.resolve('env-fixture-1'),
    environmentPath: () =>
      Promise.resolve({ org: 'Acme Financial', subOrg: 'Payments', env: 'PROD' }),
    ...overrides,
  };
}

function existingInstance(name: string): Instance {
  return {
    stateVersion: 1,
    instanceId: name,
    name,
    solutionId: 'cache',
    environmentId: 'env-1',
    declaredPackageVersion: 7,
    acquisition: { mode: 'direct-http' },
    placements: [],
  };
}

function renderDialog(props: Partial<Parameters<typeof DeclareInstanceDialog>[0]> = {}) {
  const onSubmit = vi.fn();
  const onClose = vi.fn();
  renderWithProviders(
    <DeclareInstanceDialog
      open
      solutionId="cache"
      existing={[]}
      packageVersions={VERSIONS}
      hierarchyRepository={stubHierarchy()}
      connectionsRepository={stubConnections()}
      onClose={onClose}
      onSubmit={onSubmit}
      {...props}
    />,
  );
  return { onSubmit, onClose };
}

/** Pick one option from a MUI select, once its options have loaded. */
async function choose(label: RegExp, option: string): Promise<void> {
  fireEvent.mouseDown(screen.getByLabelText(label));
  const item = await screen.findByRole('option', { name: option });
  fireEvent.click(item);
}

/**
 * Walk the whole cascade plus the connection picker, in the order the screen
 * requires. Every test that needs a submittable form goes through here
 * rather than setting fields independently.
 */
async function walkCascade(
  org = 'Acme Financial',
  subOrg = 'Payments',
  env = 'PROD',
): Promise<void> {
  await choose(/^Organization/, org);
  await choose(/^Sub-organization/, subOrg);
  await choose(/^Environment/, env);
  await choose(/^Remote server connection/, 'build-host');
}

function chooseVersion(label: string): void {
  fireEvent.mouseDown(screen.getByLabelText(/^Package version/));
  fireEvent.click(screen.getByRole('option', { name: label }));
}

function typeInto(label: string | RegExp, value: string): void {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

async function fillPlacement(): Promise<void> {
  typeInto(/^Container name/, 'cache-prod-01');
  typeInto(/^File name/, 'application.yml');
  typeInto(/^Config directory/, '/etc/cache');
  typeInto(/^Container config path/, '/config');
  typeInto(/^Health port/, '8080');
  await choose(/^Acquisition mode/, 'Direct HTTP (the target’s own address)');
}

function declare(): void {
  fireEvent.click(screen.getByRole('button', { name: 'Declare' }));
}

describe('DeclareInstanceDialog', () => {
  it('asks for identity, binding and placement fields, and for no observed fact', () => {
    renderDialog();
    const dialog = screen.getByRole('dialog');

    for (const label of [
      /^Name/,
      /^Package version/,
      /^Organization/,
      /^Sub-organization/,
      /^Environment/,
      /^Deployment mode/,
      /^Remote server connection/,
      /^Container name/,
      /^File name/,
      /^Config directory/,
      /^Container config path/,
      /^Health port/,
      /^Acquisition mode/,
    ]) {
      expect(within(dialog).getByLabelText(label)).not.toBeNull();
    }

    // Health STATE, config state and last-seen are reported by an
    // instance, never asserted by whoever declares it — distinct from the
    // legitimate "Health port" placement field asked for above. Role/
    // driver/locus/replicas don't exist on the real contract at all (see
    // the file header).
    for (const forbidden of [
      /^Health$/i,
      /last seen/i,
      /config state/i,
      /^Role/,
      /^Where it runs/,
      /^How it.s reached/,
      /^Replica/,
    ]) {
      expect(within(dialog).queryByLabelText(forbidden)).toBeNull();
    }
  });

  it('does NOT render the package’s config fields — those belong to the Designer', () => {
    renderDialog();
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).queryByLabelText(/jdbc/i)).toBeNull();
    expect(within(dialog).queryByLabelText(/pool/i)).toBeNull();
  });

  it('stays quiet until the first Declare press, then reports what is missing', () => {
    const { onSubmit } = renderDialog();

    expect(screen.queryByText('Required.')).toBeNull();

    declare();

    // Name, package version (two served, none preselected), Organization,
    // and the 6 placement/acquisition fields (not cascade-gated — see
    // `validateDeclareForm`) are all unblocked required fields. Everything
    // BELOW an unanswered cascade level (sub-org, env, target) is NOT
    // flagged — naming the wrong field.
    expect(screen.getAllByText('Required.').length).toBeGreaterThan(0);
    expect(screen.queryByText('Choose an organization first.')).not.toBeNull();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('builds the version options from the prop, never from a list of its own', () => {
    renderDialog({ packageVersions: ['dev-2026.08.17'] });
    fireEvent.mouseDown(screen.getByLabelText(/^Package version/));
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual([
      'dev-2026.08.17',
    ]);
  });

  it('preselects the only version a single-version package serves', async () => {
    const { onSubmit } = renderDialog({ packageVersions: [1] });

    typeInto(/^Name/, 'db-01');
    await walkCascade();
    await fillPlacement();
    declare();

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith(
        'env-fixture-1',
        expect.objectContaining({ declaredPackageVersion: 1 }),
      );
    });
  });

  it('clears the required error as soon as a version is chosen', () => {
    renderDialog();

    declare();
    const before = screen.getAllByText('Required.').length;

    chooseVersion('6');
    expect(screen.getAllByText('Required.').length).toBe(before - 1);
  });

  it('distinguishes the package version from the MI version in its helper copy', () => {
    renderDialog();
    expect(screen.getByText(/decides which fields the Designer shows/i)).not.toBeNull();
  });

  it('submits the real contract shape once the form is valid', async () => {
    const { onSubmit } = renderDialog();

    typeInto(/^Name/, 'ledger-eu-01');
    chooseVersion('6');
    await walkCascade('Acme Financial', 'Payments', 'TEST');
    await fillPlacement();
    declare();

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith('env-fixture-1', {
        solutionId: 'cache',
        name: 'ledger-eu-01',
        deploymentMode: 'dp-remote',
        connectionId: 'conn-1',
        declaredPackageVersion: 6,
        placement: {
          containerName: 'cache-prod-01',
          configDirectory: '/etc/cache',
          containerConfigPath: '/config',
          fileName: 'application.yml',
          healthPort: 8080,
        },
        acquisition: { mode: 'direct-http' },
      });
    });
  });

  it('refuses a name an existing instance already has', async () => {
    const existing = [existingInstance('cache-prod-01')];
    const { onSubmit } = renderDialog({ existing });

    typeInto(/^Name/, 'cache-prod-01');
    chooseVersion('7');
    await walkCascade();
    await fillPlacement();
    declare();

    expect(screen.getByText('An instance with this name is already declared.')).not.toBeNull();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('renders a failed declare in place, so the form is not lost', () => {
    renderDialog({ errorMessage: 'the server said no' });
    expect(screen.getByText('the server said no')).not.toBeNull();
    expect(screen.getByText('Couldn’t declare the instance')).not.toBeNull();
  });

  it('disables both actions while the declare is in flight', () => {
    renderDialog({ submitting: true });
    expect(screen.getByRole('button', { name: 'Declaring…' })).toHaveProperty('disabled', true);
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveProperty('disabled', true);
  });

  it('requires a health port between 1 and 65535', async () => {
    renderDialog();
    typeInto(/^Name/, 'cache-01');
    chooseVersion('6');
    await walkCascade();
    typeInto(/^Container name/, 'cache-01');
    typeInto(/^File name/, 'application.yml');
    typeInto(/^Config directory/, '/etc/cache');
    typeInto(/^Container config path/, '/config');
    typeInto(/^Health port/, '99999');
    await choose(/^Acquisition mode/, 'Direct HTTP (the target’s own address)');
    declare();

    expect(await screen.findByText('Enter a port between 1 and 65535.')).not.toBeNull();
  });
});

describe('the Organization → Sub-organization → Environment → Connection cascade (FE-D46)', () => {
  it('gates each level on the one above it', async () => {
    renderDialog();

    expect(screen.getByLabelText(/^Sub-organization/)).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByText('Choose an organization first.')).not.toBeNull();
    expect(screen.getByLabelText(/^Environment/)).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByLabelText(/^Remote server connection/)).toHaveAttribute('aria-disabled', 'true');

    await choose(/^Organization/, 'Acme Financial');

    await waitFor(() => {
      expect(screen.getByLabelText(/^Sub-organization/)).not.toHaveAttribute(
        'aria-disabled',
        'true',
      );
    });
    expect(screen.getByText('Choose a sub-organization first.')).not.toBeNull();

    await choose(/^Sub-organization/, 'Payments');

    await waitFor(() => {
      expect(screen.getByLabelText(/^Environment/)).not.toHaveAttribute('aria-disabled', 'true');
    });
  });

  it('loads each level from the repository rather than a list of its own', async () => {
    const subOrgs = vi.fn((org: string) =>
      Promise.resolve(org === 'Acme Financial' ? ['Payments'] : ['Platform']),
    );
    renderDialog({ hierarchyRepository: stubHierarchy({ subOrgs }) });

    expect(subOrgs).not.toHaveBeenCalled();

    await choose(/^Organization/, 'Globex');

    await waitFor(() => {
      expect(subOrgs).toHaveBeenCalledWith('Globex', expect.anything());
    });
    fireEvent.mouseDown(screen.getByLabelText(/^Sub-organization/));
    expect(await screen.findByRole('option', { name: 'Platform' })).not.toBeNull();
  });

  it('clears the levels below (including the connection) when the organization changes', async () => {
    const { onSubmit } = renderDialog();

    typeInto(/^Name/, 'cache-01');
    chooseVersion('6');
    await walkCascade('Acme Financial', 'Payments', 'PROD');

    await choose(/^Organization/, 'Globex');

    await waitFor(() => {
      expect(screen.getByText('Choose a sub-organization first.')).not.toBeNull();
    });
    declare();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('requires an environment — nothing is defaulted', async () => {
    const { onSubmit } = renderDialog();

    typeInto(/^Name/, 'cache-01');
    chooseVersion('6');
    await choose(/^Organization/, 'Acme Financial');
    await choose(/^Sub-organization/, 'Payments');
    declare();

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getAllByText('Required.').length).toBeGreaterThan(0);
  });

  // A failed load and an empty one are different facts. Showing "this org
  // has no sub-orgs" for a network error sends the operator after the
  // wrong problem.
  it('says a level failed to load rather than showing it as empty', async () => {
    renderDialog({
      hierarchyRepository: stubHierarchy({
        subOrgs: () => Promise.reject(new Error('network down')),
      }),
    });

    await choose(/^Organization/, 'Acme Financial');

    expect(
      await screen.findByText('Could not load these. Retry, or check your connection.'),
    ).not.toBeNull();
  });

  it('requires a connection from the known list, not any string', async () => {
    const { onSubmit } = renderDialog({ connectionsRepository: stubConnections([]) });

    typeInto(/^Name/, 'cache-01');
    chooseVersion('6');
    await choose(/^Organization/, 'Acme Financial');
    await choose(/^Sub-organization/, 'Payments');
    await choose(/^Environment/, 'PROD');
    declare();

    expect(onSubmit).not.toHaveBeenCalled();
  });
});

describe('deployment target placement defaults', () => {
  it('fills target-owned values and replaces them when the target changes', async () => {
    renderDialog({ targetsRepository: stubTargets() });

    await choose(/^Organization/, 'Acme Financial');
    await choose(/^Sub-organization/, 'Payments');
    await choose(/^Environment/, 'PROD');
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Deployment target' }));
    fireEvent.click(await screen.findByRole('option', { name: 'Docker A (docker)' }));

    expect(screen.getByLabelText(/^Config directory/)).toHaveValue('/opt/a');
    expect(screen.getByLabelText(/^Container name/)).toHaveValue('a-');

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Deployment target' }));
    fireEvent.click(await screen.findByRole('option', { name: 'Docker B (docker)' }));

    expect(screen.getByLabelText(/^Config directory/)).toHaveValue('/opt/b');
    expect(screen.getByLabelText(/^Container name/)).toHaveValue('b-');
  });
});

describe('deployment mode and the Connection picker (integration plan 014)', () => {
  it('lists all three deployment modes, with only dp-remote selectable', () => {
    renderDialog();

    fireEvent.mouseDown(screen.getByLabelText(/^Deployment mode/));
    const options = screen.getAllByRole('option');
    expect(options.map((option) => option.textContent)).toEqual([
      'Remote (dp-remote)',
      'Host-local (coming soon)',
      'Federated (coming soon)',
    ]);
    expect(screen.getByRole('option', { name: 'Remote (dp-remote)' })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    );
    expect(screen.getByRole('option', { name: 'Host-local (coming soon)' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    expect(screen.getByRole('option', { name: 'Federated (coming soon)' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    // The helper copy says why the other two cannot be chosen.
    expect(screen.getByText(/only mode available today/i)).not.toBeNull();
  });

  it('preselects dp-remote and keeps it when a reserved mode is clicked', async () => {
    renderDialog();
    const modeField = screen.getByLabelText(/^Deployment mode/);
    expect(modeField.textContent).toBe('Remote (dp-remote)');

    fireEvent.mouseDown(modeField);
    fireEvent.click(screen.getByRole('option', { name: 'Host-local (coming soon)' }));

    // A disabled option does not take the click: the value is unchanged.
    expect(modeField.textContent).toBe('Remote (dp-remote)');
    // Still dp-remote, so the Connection picker is still the binding shown.
    fireEvent.keyDown(screen.getByRole('listbox'), { key: 'Escape' });
    expect(
      await within(await screen.findByRole('dialog')).findByLabelText(/^Remote server connection/),
    ).not.toBeNull();
  });

  it('shows the Connection picker INSTEAD of the target picker — never both', () => {
    renderDialog();
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByLabelText(/^Remote server connection/)).not.toBeNull();
    expect(within(dialog).queryByLabelText(/^Target/)).toBeNull();
  });

  it('offers the repository’s remote-server connections by name, never a host', async () => {
    const other: RemoteServerConnectionRow = {
      ...CONNECTION,
      connectionId: 'conn-2',
      name: 'edge-host',
      attributes: { host: 'edge.example.test' },
    };
    renderDialog({ connectionsRepository: stubConnections([CONNECTION, other]) });

    await choose(/^Organization/, 'Acme Financial');
    await choose(/^Sub-organization/, 'Payments');
    await choose(/^Environment/, 'PROD');
    fireEvent.mouseDown(screen.getByLabelText(/^Remote server connection/));

    await waitFor(() => {
      expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual([
        'build-host',
        'edge-host',
      ]);
    });
    expect(screen.queryByText(/edge\.example\.test/)).toBeNull();
  });

  it('says when no remote-server connection exists rather than showing an empty picker silently', async () => {
    renderDialog({ connectionsRepository: stubConnections([]) });
    expect(
      await screen.findByText(
        'No active remote-server Connection is available. Add one under Connections first.',
      ),
    ).not.toBeNull();
  });

  it('flows the chosen connection into the submitted body as connectionId, with no targetId', async () => {
    const other: RemoteServerConnectionRow = { ...CONNECTION, connectionId: 'conn-2', name: 'edge-host' };
    const { onSubmit } = renderDialog({ connectionsRepository: stubConnections([CONNECTION, other]) });

    typeInto(/^Name/, 'cache-edge-01');
    chooseVersion('7');
    await choose(/^Organization/, 'Acme Financial');
    await choose(/^Sub-organization/, 'Payments');
    await choose(/^Environment/, 'PROD');
    await choose(/^Remote server connection/, 'edge-host');
    await fillPlacement();
    declare();

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledTimes(1);
    });
    const [, body] = onSubmit.mock.calls[0] as [string, Record<string, unknown>];
    expect(body['connectionId']).toBe('conn-2');
    expect(body['deploymentMode']).toBe('dp-remote');
    expect(body).not.toHaveProperty('targetId');
  });
});
