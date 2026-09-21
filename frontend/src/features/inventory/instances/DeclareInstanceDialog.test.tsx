/**
 * The dialog's behaviour: what it renders, when errors appear, and the
 * exact payload it hands `onSubmit`.
 */
import { describe, expect, it, vi } from 'vitest';

import type { HierarchyRepository } from '@shared/hierarchy';
import { CACHE_INSTANCES, type Env, type InstanceRow } from '@shared/testdoubles/data/instances';

import {
  fireEvent,
  renderWithProviders,
  screen,
  waitFor,
  within,
} from '../../../test/renderWithProviders';

import { DeclareInstanceDialog } from './DeclareInstanceDialog';

/** What the Cache package declares (FE-118) — two versions, so nothing is preselected. */
const VERSIONS: readonly (string | number)[] = [6, 7];

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
    ...overrides,
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
 * Walk the whole cascade in the order `FE-D46` mandates. Every test that
 * needs a submittable form goes through here rather than setting the three
 * fields independently — going around the cascade would prove nothing
 * about the screen an operator actually uses.
 */
async function walkCascade(
  org = 'Acme Financial',
  subOrg = 'Payments',
  env = 'PROD',
): Promise<void> {
  await choose(/^Organization/, org);
  await choose(/^Sub-organization/, subOrg);
  await waitFor(() => {
    expect(screen.getByRole('button', { name: env })).toBeEnabled();
  });
  fireEvent.click(screen.getByRole('button', { name: env }));
}

/** MUI renders a `select` TextField as a listbox-backed button, not a `<select>`. */
function chooseVersion(label: string): void {
  fireEvent.mouseDown(screen.getByLabelText(/^Package version/));
  fireEvent.click(screen.getByRole('option', { name: label }));
}

function typeInto(label: string | RegExp, value: string): void {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

function declare(): void {
  fireEvent.click(screen.getByRole('button', { name: 'Declare' }));
}

describe('DeclareInstanceDialog', () => {
  it('asks for identity fields, and for no observed fact', () => {
    renderDialog();
    const dialog = screen.getByRole('dialog');

    for (const label of [
      /^Name/,
      /^Package version/,
      /^Organization/,
      /^Sub-organization/,
      /^Where it runs/,
      /^How it’s reached/,
    ]) {
      expect(within(dialog).getByLabelText(label)).not.toBeNull();
    }
    expect(within(dialog).getByRole('group', { name: 'Environment' })).not.toBeNull();

    // Health, config state and last-seen are reported by an instance, never
    // asserted by whoever declares it — see the module header.
    for (const forbidden of [/health/i, /last seen/i, /config state/i]) {
      expect(within(dialog).queryByLabelText(forbidden)).toBeNull();
    }
  });

  it('states plainly that no API backs the form yet', () => {
    renderDialog();
    expect(screen.getByText(/no api backs this yet/i)).not.toBeNull();
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

    // TWO, not three: package version (the package serves two, so none is
    // preselected) and Organization. Sub-organization and Environment are
    // below an unanswered level, and flagging them would name the wrong
    // field — see `validateDeclareForm`.
    expect(screen.getAllByText('Required.')).toHaveLength(2);
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
    declare();

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ packageVersion: 1 }));
  });

  it('clears the required error as soon as a version is chosen', () => {
    renderDialog();

    declare();
    expect(screen.getAllByText('Required.')).toHaveLength(2);

    chooseVersion('6');
    expect(screen.getAllByText('Required.')).toHaveLength(1);
  });

  it('distinguishes the package version from the MI version in its helper copy', () => {
    renderDialog();
    expect(screen.getByText(/which fields the Designer shows/i)).not.toBeNull();
    expect(screen.getByText(/version of the micro-integration build itself/i)).not.toBeNull();
  });

  it('submits the trimmed identity payload once the form is valid', async () => {
    const { onSubmit } = renderDialog();

    typeInto(/^Name/, 'ledger-eu-01');
    chooseVersion('6');
    await walkCascade('Acme Financial', 'Payments', 'TEST');
    declare();

    expect(onSubmit).toHaveBeenCalledWith({
      name: 'ledger-eu-01',
      // The number the package declared, not the string the select held.
      packageVersion: 6,
      // Chosen, never defaulted — `initialDeclareForm` leaves it '' now.
      env: 'TEST',
      org: 'Acme Financial',
      subOrg: 'Payments',
      driver: 'docker',
      locus: 'dp_remote',
      // Renaming rewrote the untouched replica default with it.
      replicas: ['ledger-eu-01-0'],
    });
  });

  it('refuses a name an existing instance already has', async () => {
    const existing = [{ ...CACHE_INSTANCES[0], name: 'cache-prod-01' } as InstanceRow];
    const { onSubmit } = renderDialog({ existing });

    typeInto(/^Name/, 'cache-prod-01');
    chooseVersion('7');
    await walkCascade();
    declare();

    expect(screen.getByText('An instance with this name is already declared.')).not.toBeNull();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('adds and removes replica rows', () => {
    renderDialog();

    fireEvent.click(screen.getByRole('button', { name: 'Add replica' }));
    expect(screen.getByLabelText(/^Replica 2/)).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Remove replica 1' }));
    expect(screen.queryByLabelText(/^Replica 2/)).toBeNull();
  });

  it('shows the chosen locus’s credential posture inline', () => {
    renderDialog();
    expect(screen.getByText(/holding scoped credentials for it/i)).not.toBeNull();
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
});

describe('the Organization → Sub-organization → Environment cascade (FE-D46)', () => {
  it('gates each level on the one above it', async () => {
    renderDialog();

    // Nothing is chosen: only the organization select is live.
    expect(screen.getByLabelText(/^Sub-organization/)).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByText('Choose an organization first.')).not.toBeNull();
    // No environment buttons at all — not three disabled ones. The valid
    // set is unknown until the cascade runs, so offering DEV/TEST/PROD
    // greyed out would be showing options that may not exist here.
    expect(screen.queryByRole('button', { name: 'PROD' })).toBeNull();

    await choose(/^Organization/, 'Acme Financial');

    // One level unlocks, the one below it does not.
    await waitFor(() => {
      expect(screen.getByLabelText(/^Sub-organization/)).not.toHaveAttribute(
        'aria-disabled',
        'true',
      );
    });
    expect(screen.getByText('Choose a sub-organization first.')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'PROD' })).toBeNull();

    await choose(/^Sub-organization/, 'Payments');

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'PROD' })).toBeEnabled();
    });
  });

  it('loads each level from the repository rather than a list of its own', async () => {
    const subOrgs = vi.fn((org: string) =>
      Promise.resolve(org === 'Acme Financial' ? ['Payments'] : ['Platform']),
    );
    renderDialog({ hierarchyRepository: stubHierarchy({ subOrgs }) });

    // Level two is NOT fetched before level one is answered.
    expect(subOrgs).not.toHaveBeenCalled();

    await choose(/^Organization/, 'Globex');

    await waitFor(() => {
      expect(subOrgs).toHaveBeenCalledWith('Globex', expect.anything());
    });
    // The options are the repository's answer, not the fixture's.
    fireEvent.mouseDown(screen.getByLabelText(/^Sub-organization/));
    expect(await screen.findByRole('option', { name: 'Platform' })).not.toBeNull();
  });

  it('clears the levels below when the organization changes', async () => {
    const { onSubmit } = renderDialog();

    typeInto(/^Name/, 'cache-01');
    chooseVersion('6');
    await walkCascade('Acme Financial', 'Payments', 'PROD');

    // Re-pick a different organization. 'Payments' does not exist under
    // Globex, and neither does PROD.
    await choose(/^Organization/, 'Globex');

    await waitFor(() => {
      expect(screen.getByText('Choose a sub-organization first.')).not.toBeNull();
    });
    declare();
    expect(onSubmit).not.toHaveBeenCalled();

    // And the environment toggle now offers Globex's set, not Acme Financial's.
    await choose(/^Sub-organization/, 'Platform');
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'DEV' })).toBeEnabled();
    });
    expect(screen.queryByRole('button', { name: 'PROD' })).toBeNull();
  });

  it('requires an environment — nothing is defaulted', async () => {
    const { onSubmit } = renderDialog();

    typeInto(/^Name/, 'cache-01');
    chooseVersion('6');
    await choose(/^Organization/, 'Acme Financial');
    await choose(/^Sub-organization/, 'Payments');
    declare();

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText('Required.')).not.toBeNull();
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
});
