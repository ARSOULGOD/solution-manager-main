import { useSearchParams } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';
import type { Instance } from '@shared/api/contracts/instances';
import { createCpClient } from '@shared/api/cpClient';
import { createDpClient } from '@shared/api/dpClient';
import type { HierarchyRepository } from '@shared/hierarchy';
import { CACHE_INSTANCES, type Env } from '@shared/testdoubles/data/instances';
import { reflowFloorPx } from '@shared/theme/breakpoints';

import { setViewportWidth } from '../../../test/matchMedia';
import { assertReflow } from '../../../test/reflow';
import {
  fireEvent,
  renderWithProviders,
  screen,
  waitFor,
  within,
} from '../../../test/renderWithProviders';
import {
  createSolutionsRepository,
  type SolutionCatalogItem,
  type SolutionsRepository,
} from '../solutions';

import type { RemoteServerConnectionRow } from './connectionPickerRepository';
import { InstancesPage } from './InstancesPage';
import { createInstancesRepository, type InstancesRepository } from './instancesRepository';

/**
 * The Cache card as the catalog serves it. `packageVersions` matters here
 * (FE-118): the declare form's version options come from it, and the
 * Declare action is gated until this query settles.
 */
const CACHE_CARD: SolutionCatalogItem = {
  solutionId: 'cache',
  solution: 'cache',
  name: 'Cache Engine',
  packageVersions: [6, 7],
  latestPackageVersion: 7,
  signature: 'unsigned',
  stateVersion: 1,
};

function stubSolutions(overrides: Partial<SolutionsRepository> = {}): SolutionsRepository {
  return {
    list: () => Promise.resolve([CACHE_CARD]),
    getPackage: () => Promise.reject(new Error('not used in this test')),
    load: () =>
      Promise.resolve({
        solutionId: 'cache',
        packageVersion: [6, 7],
        signature: 'unsigned',
        stateVersion: 1,
        correlationId: 'c1',
      }),
    ...overrides,
  };
}

/**
 * The cascade's backing repository (`FE-D46`). Real values rather than
 * empty lists: a declare test that cannot pick an organization cannot
 * reach Submit. `environmentId`/`environmentPath` are the two methods
 * plan 007 added for the real `declareInstance` route and the grid's env
 * column.
 */
function stubHierarchy(overrides: Partial<HierarchyRepository> = {}): HierarchyRepository {
  return {
    organizations: () => Promise.resolve(['Acme Financial', 'Globex']),
    subOrgs: (org: string) => Promise.resolve(org === 'Acme Financial' ? ['Payments'] : ['Platform']),
    environments: () => Promise.resolve(['PROD', 'TEST'] as Env[]),
    environmentId: () => Promise.resolve('env-fixture-1'),
    environmentPath: (environmentId: string) =>
      Promise.resolve(
        ENV_NAME_BY_ID[environmentId] === undefined
          ? undefined
          : { org: 'Acme Financial', subOrg: 'Payments', env: ENV_NAME_BY_ID[environmentId] },
      ),
    ...overrides,
  };
}

/**
 * `toRealInstance` (dp.ts) derives each fixture row's `environmentId` as
 * `nodeId(row.org, row.subOrg, row.env)` — reproduced here so the stub
 * hierarchy's `environmentPath` can resolve the SAME ids the real MSW
 * handlers hand back, for the tests that exercise the real repository.
 */
const ENV_NAME_BY_ID: Record<string, string> = Object.fromEntries(
  CACHE_INSTANCES.map((row) => [nodeIdFor(row.org, row.subOrg, row.env), row.env]),
);

// Mirrors `shared/testdoubles/data/hierarchy.ts`'s `fnv1a`/`nodeId` exactly
// — duplicated rather than imported to keep this test file's stub
// self-contained.
function fnv1a(text: string, seed: number): number {
  let hash = seed >>> 0;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash >>> 0;
}
function nodeIdFor(...path: readonly string[]): string {
  const key = path.join('\u0000');
  const a = fnv1a(key, 0x811c9dc5).toString(16).padStart(8, '0');
  const b = fnv1a(key, 0x9747b28c).toString(16).padStart(8, '0');
  return `${a.slice(0, 8)}-${b.slice(0, 4)}-4${b.slice(4, 7)}-8${a.slice(1, 4)}-${a}${b.slice(0, 4)}`;
}

const CONNECTION: RemoteServerConnectionRow = {
  connectionId: 'conn-1',
  name: 'build-host',
  connectionType: 'remote-server',
  attributes: {},
  active: true,
};
function stubConnections() {
  return { listRemoteServers: () => Promise.resolve([CONNECTION]) };
}

function toInstance(row: (typeof CACHE_INSTANCES)[number]): Instance {
  return {
    stateVersion: 1,
    instanceId: row.id,
    name: row.name,
    solutionId: row.solutionId,
    environmentId: nodeIdFor(row.org, row.subOrg, row.env),
    declaredPackageVersion: row.packageVersion,
    acquisition: { mode: 'direct-http' },
    placements: row.replicas.map((replica, index) => ({
      placementId: `${row.id}-p${String(index)}`,
      targetId: 'target-1',
      containerName: replica.name,
      probeStrength: 'readiness' as const,
    })),
  };
}
const ALL_INSTANCES: readonly Instance[] = CACHE_INSTANCES.map(toInstance);

function stubInstances(overrides: Partial<InstancesRepository> = {}): InstancesRepository {
  return {
    list: () => Promise.resolve([]),
    get: () => Promise.reject(new Error('not used')),
    declare: () => Promise.reject(new Error('not used')),
    undeclare: () => Promise.reject(new Error('not used')),
    ...overrides,
  };
}

function renderPage(props: Partial<Parameters<typeof InstancesPage>[0]> = {}) {
  renderWithProviders(
    <InstancesPage
      solutionId="cache"
      repository={stubInstances()}
      solutionsRepository={stubSolutions()}
      hierarchyRepository={stubHierarchy()}
      connectionsRepository={stubConnections()}
      {...props}
    />,
  );
}

describe('InstancesPage', () => {
  it('renders a loading skeleton while the query is in flight', () => {
    renderPage({ repository: stubInstances({ list: () => new Promise(() => undefined) }) });

    expect(document.querySelectorAll('[data-skeleton]').length).toBeGreaterThan(0);
  });

  it('renders an error panel with retry — distinct from empty — when the query fails', async () => {
    renderPage({
      repository: stubInstances({
        list: () => Promise.reject(new ApiError({ status: 500, message: 'boom' })),
      }),
    });

    await waitFor(() => {
      expect(screen.getByText('Couldn’t load instances')).not.toBeNull();
    });
    expect(screen.getByText('boom')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Retry' })).not.toBeNull();
    expect(screen.queryByText('No instances declared yet')).toBeNull();
  });

  it('renders the empty state, with Declare instance present and enabled', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('No instances declared yet')).not.toBeNull();
    });
    const declareButtons = screen.getAllByRole('button', { name: 'Declare instance' });
    expect(declareButtons.length).toBeGreaterThan(0);
    for (const button of declareButtons) {
      expect(button.hasAttribute('disabled')).toBe(false);
    }
  });

  function LocationProbe() {
    const [params] = useSearchParams();
    return <div data-testid="declare-search-params">{params.toString()}</div>;
  }

  async function openDeclareDialog(repository: InstancesRepository): Promise<void> {
    renderWithProviders(
      <>
        <InstancesPage
          solutionId="cache"
          repository={repository}
          solutionsRepository={stubSolutions()}
          hierarchyRepository={stubHierarchy()}
          connectionsRepository={stubConnections()}
        />
        <LocationProbe />
      </>,
    );

    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: 'Declare instance' }).length).toBeGreaterThan(0);
    });
    const declareButton = screen.getAllByRole('button', { name: 'Declare instance' })[0];
    if (declareButton === undefined) {
      throw new Error('Declare instance button not rendered');
    }
    fireEvent.click(declareButton);
  }

  async function choose(dialogEl: HTMLElement, label: RegExp, option: string): Promise<void> {
    fireEvent.mouseDown(within(dialogEl).getByLabelText(label));
    fireEvent.click(await screen.findByRole('option', { name: option }));
  }

  // Scoped to the DIALOG: `Package version` is now also a grid column
  // header, and the table behind the dialog carries one too.
  async function fillDeclareForm(name: string): Promise<void> {
    const dialogEl = screen.getByRole('dialog');
    const dialog = within(dialogEl);
    fireEvent.change(dialog.getByLabelText(/^Name/), { target: { value: name } });
    fireEvent.mouseDown(dialog.getByLabelText(/^Package version/));
    fireEvent.click(screen.getByRole('option', { name: '7' }));

    await choose(dialogEl, /^Organization/, 'Acme Financial');
    await choose(dialogEl, /^Sub-organization/, 'Payments');
    await choose(dialogEl, /^Environment/, 'PROD');
    await choose(dialogEl, /^Remote server connection/, 'build-host');

    fireEvent.change(dialog.getByLabelText(/^Container name/), { target: { value: name } });
    fireEvent.change(dialog.getByLabelText(/^File name/), { target: { value: 'application.yml' } });
    fireEvent.change(dialog.getByLabelText(/^Config directory/), { target: { value: '/etc/cache' } });
    fireEvent.change(dialog.getByLabelText(/^Container config path/), { target: { value: '/config' } });
    fireEvent.change(dialog.getByLabelText(/^Health port/), { target: { value: '8080' } });
    await choose(dialogEl, /^Acquisition mode/, 'Direct HTTP (the target’s own address)');
  }

  describe('undeclare', () => {
    async function renderWithSelection(overrides: Partial<InstancesRepository> = {}) {
      const repository = stubInstances({
        list: () => Promise.resolve(ALL_INSTANCES),
        ...overrides,
      });
      renderWithProviders(
        <InstancesPage
          solutionId="cache"
          repository={repository}
          solutionsRepository={stubSolutions()}
          hierarchyRepository={stubHierarchy()}
          connectionsRepository={stubConnections()}
        />,
        { route: '/?sel=cache-prod-01,cache-prod-02' },
      );
      await waitFor(() => {
        expect(screen.getByText('cache-prod-01')).not.toBeNull();
      });
      return repository;
    }

    it('offers Remove alongside Restart once rows are selected', async () => {
      await renderWithSelection();

      expect(screen.getByRole('button', { name: 'Remove 2…' })).not.toBeNull();
      expect(screen.getByRole('button', { name: /Restart 2/ })).not.toBeNull();
    });

    it('states that the workload keeps running — the whole point of the action', async () => {
      await renderWithSelection();

      fireEvent.click(screen.getByRole('button', { name: 'Remove 2…' }));

      const dialog = screen.getByRole('dialog');
      expect(within(dialog).getByText(/the running workload is not touched/i)).not.toBeNull();
      expect(within(dialog).getByText(/doesn.t deploy, stop or delete/i)).not.toBeNull();
      expect(within(dialog).getByText('cache-prod-01')).not.toBeNull();
      expect(within(dialog).getByText('cache-prod-02')).not.toBeNull();
    });

    it('removes each selected instance and clears them from ?sel=', async () => {
      const undeclare = vi.fn().mockResolvedValue({ correlationId: 'corr-9' });
      renderWithProviders(
        <>
          <InstancesPage
            solutionId="cache"
            repository={stubInstances({ list: () => Promise.resolve(ALL_INSTANCES), undeclare })}
            solutionsRepository={stubSolutions()}
            hierarchyRepository={stubHierarchy()}
            connectionsRepository={stubConnections()}
          />
          <LocationProbe />
        </>,
        { route: '/?sel=cache-prod-01,cache-prod-02' },
      );
      await waitFor(() => {
        expect(screen.getByText('cache-prod-01')).not.toBeNull();
      });

      fireEvent.click(screen.getByRole('button', { name: 'Remove 2…' }));
      fireEvent.click(screen.getByRole('button', { name: 'Remove 2' }));

      await waitFor(() => {
        expect(screen.queryByRole('dialog')).toBeNull();
      });
      expect(undeclare).toHaveBeenCalledWith('cache-prod-01');
      expect(undeclare).toHaveBeenCalledWith('cache-prod-02');
      await waitFor(() => {
        expect(screen.getByTestId('declare-search-params').textContent).toBe('');
      });
    });

    it('never counts a row that no longer exists as selected', async () => {
      renderWithProviders(
        <InstancesPage
          solutionId="cache"
          repository={stubInstances({
            list: () => Promise.resolve(ALL_INSTANCES.filter((row) => row.instanceId !== 'cache-prod-01')),
          })}
          solutionsRepository={stubSolutions()}
          hierarchyRepository={stubHierarchy()}
          connectionsRepository={stubConnections()}
        />,
        { route: '/?sel=cache-prod-01,cache-prod-02' },
      );
      await waitFor(() => {
        expect(screen.getByText('cache-prod-02')).not.toBeNull();
      });

      expect(screen.getByRole('button', { name: 'Remove 1…' })).not.toBeNull();
    });

    it('names exactly which instances are still declared when only some removals fail', async () => {
      const undeclare = vi.fn((id: string) =>
        id === 'cache-prod-02'
          ? Promise.reject(new ApiError({ status: 500, message: 'boom' }))
          : Promise.resolve({ correlationId: 'corr-9' }),
      );
      await renderWithSelection({ undeclare });

      fireEvent.click(screen.getByRole('button', { name: 'Remove 2…' }));
      fireEvent.click(screen.getByRole('button', { name: 'Remove 2' }));

      await waitFor(() => {
        expect(screen.getByText(/still declared: cache-prod-02/i)).not.toBeNull();
      });
      expect(screen.getByRole('dialog')).not.toBeNull();
    });
  });

  it('gates Declare until the catalog settles — you cannot pick from a list you cannot see', async () => {
    renderPage({
      repository: stubInstances({ list: () => Promise.resolve(ALL_INSTANCES) }),
      solutionsRepository: stubSolutions({ list: () => new Promise(() => undefined) }),
    });

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });
    const declareButton = screen.getAllByRole('button', { name: 'Declare instance' })[0];
    expect(declareButton?.hasAttribute('disabled')).toBe(true);
  });

  it('offers the package versions the catalog reports for THIS solution', async () => {
    await openDeclareDialog(stubInstances());

    const dialog = within(screen.getByRole('dialog'));
    fireEvent.mouseDown(dialog.getByLabelText(/^Package version/));
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(['6', '7']);
  });

  it('Declare instance opens the form, defaulting its name off the solution', async () => {
    await openDeclareDialog(stubInstances());

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByLabelText(/^Name/)).toHaveProperty('value', 'cache-01');
  });

  it('posts the declared instance, then focuses the new row via ?focus=', async () => {
    const declared: Instance = {
      stateVersion: 1,
      instanceId: 'ledger-eu-01',
      name: 'ledger-eu-01',
      solutionId: 'cache',
      environmentId: 'env-fixture-1',
      declaredPackageVersion: 7,
      acquisition: { mode: 'direct-http' },
      placements: [],
    };
    const declare = vi.fn().mockResolvedValue({ instance: declared, correlationId: 'corr-1' });

    await openDeclareDialog(
      stubInstances({ list: () => Promise.resolve(ALL_INSTANCES), declare }),
    );

    await fillDeclareForm('ledger-eu-01');
    fireEvent.click(screen.getByRole('button', { name: 'Declare' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(declare).toHaveBeenCalledWith(
      'env-fixture-1',
      expect.objectContaining({ name: 'ledger-eu-01', declaredPackageVersion: 7 }),
    );
    await waitFor(() => {
      expect(screen.getByTestId('declare-search-params').textContent).toBe('focus=ledger-eu-01');
    });
  });

  it('keeps the dialog open and states the failure in place when declaring fails', async () => {
    await openDeclareDialog(
      stubInstances({
        declare: () => Promise.reject(new ApiError({ status: 409, message: 'already declared' })),
      }),
    );

    await fillDeclareForm('ledger-eu-01');
    fireEvent.click(screen.getByRole('button', { name: 'Declare' }));

    await waitFor(() => {
      expect(screen.getByText('already declared')).not.toBeNull();
    });
    expect(screen.getByRole('dialog')).not.toBeNull();
    expect(screen.getByRole('combobox', { name: /^Organization/ }).textContent).toBe(
      'Acme Financial',
    );
    expect(screen.getByRole('combobox', { name: /^Sub-organization/ }).textContent).toBe(
      'Payments',
    );
  });

  it('closing discards the draft, so the next opening starts fresh', async () => {
    await openDeclareDialog(stubInstances());

    fireEvent.mouseDown(screen.getByLabelText(/^Organization/));
    fireEvent.click(await screen.findByRole('option', { name: 'Acme Financial' }));
    expect(screen.getByRole('combobox', { name: /^Organization/ }).textContent).toBe(
      'Acme Financial',
    );

    const closeButtons = within(screen.getByRole('dialog')).getAllByRole('button', {
      name: 'Close',
    });
    const closeButton = closeButtons[0];
    if (closeButton === undefined) {
      throw new Error('Close button not rendered');
    }
    fireEvent.click(closeButton);
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });

    fireEvent.click(screen.getAllByRole('button', { name: 'Declare instance' })[0] as HTMLElement);

    const reopened = within(screen.getByRole('dialog'));
    expect(reopened.getByRole('combobox', { name: /^Organization/ }).textContent).not.toContain(
      'Acme Financial',
    );
    expect(reopened.getByText('Choose an organization first.')).not.toBeNull();
  });

  it('falls back to the raw solution id for the title when the solutions lookup has not resolved it', async () => {
    renderPage({ solutionsRepository: stubSolutions({ list: () => new Promise(() => undefined) }) });

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'cache' })).not.toBeNull();
    });
  });

  it('renders all 12 fixture instances against the real repository, title from the resolved solution name', async () => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    renderPage({ repository, solutionsRepository });

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Cache Engine' })).not.toBeNull();
    });

    for (const instance of CACHE_INSTANCES) {
      expect(screen.getAllByText(instance.name).length).toBeGreaterThan(0);
    }
  });

  it('expands a row into the placement detail panel on the expander click', async () => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    renderPage({ repository, solutionsRepository });

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });

    const expander = screen.getAllByRole('button', { name: 'Expand row details' })[0];
    expect(expander).toBeDefined();
    if (expander === undefined) {
      return;
    }
    fireEvent.click(expander);

    expect(screen.getByText('ledger-0')).not.toBeNull();
  });

  it('restores selection and expands+focuses the ?focus= row from the URL on load', async () => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
        connectionsRepository={stubConnections()}
      />,
      { route: '/?sel=cache-prod-01,cache-prod-02&focus=cache-prod-02' },
    );

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });

    expect(document.querySelector('[data-table-footer]')?.textContent).toContain('2 selected');
    expect(screen.getByText('ledger-b-0')).not.toBeNull(); // cache-prod-02's placement detail panel
    expect(document.activeElement?.closest('[role="row"]')?.getAttribute('data-id')).toBe(
      'cache-prod-02',
    );
  });

  it('selecting a row updates ?sel= in the URL while preserving ?focus=', async () => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    function LocationProbe() {
      const [params] = useSearchParams();
      return <div data-testid="search-params">{params.toString()}</div>;
    }

    renderWithProviders(
      <>
        <InstancesPage
          solutionId="cache"
          repository={repository}
          solutionsRepository={solutionsRepository}
          hierarchyRepository={stubHierarchy()}
          connectionsRepository={stubConnections()}
        />
        <LocationProbe />
      </>,
      { route: '/?focus=cache-prod-02' },
    );

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });

    const row = screen.getByText('cache-prod-01').closest('[role="row"]');
    expect(row).toBeInstanceOf(HTMLElement);
    if (!(row instanceof HTMLElement)) {
      return;
    }
    fireEvent.click(within(row).getByRole('checkbox'));

    expect(screen.getByTestId('search-params').textContent).toBe(
      'focus=cache-prod-02&sel=cache-prod-01',
    );
  });

  it('narrows the grid to the selected environment, without touching the URL', async () => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    renderPage({ repository, solutionsRepository });

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });
    await waitFor(() => {
      expect(screen.getByText('cache-dev-01')).not.toBeNull();
    });

    fireEvent.click(screen.getByRole('button', { name: 'DEV' }));

    await waitFor(() => {
      expect(screen.queryByText('cache-prod-01')).toBeNull();
    });
    expect(screen.getByText('cache-dev-01')).not.toBeNull();
  });

  it('does not force horizontal page-body scroll at 320px', async () => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));
    const { container } = renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
        connectionsRepository={stubConnections()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });

    const root = container.querySelector('[data-layout="page-width"]');
    expect(root).toBeInstanceOf(HTMLElement);
    if (!(root instanceof HTMLElement)) {
      return;
    }
    expect(() => {
      assertReflow(root, reflowFloorPx);
    }).not.toThrow();
  });

  it('shows the bulk-restart trigger only once a selection exists, with the selected count', async () => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    renderPage({ repository, solutionsRepository });

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });
    expect(screen.queryByRole('button', { name: /Restart \d+…/ })).toBeNull();

    const row = screen.getByText('cache-prod-01').closest('[role="row"]');
    expect(row).toBeInstanceOf(HTMLElement);
    if (!(row instanceof HTMLElement)) {
      return;
    }
    fireEvent.click(within(row).getByRole('checkbox'));

    expect(screen.getByRole('button', { name: 'Restart 1…' })).not.toBeNull();
  });

  it('the bulk-restart dialog states, per selected instance, name/replica count/unavailability, and never reports success on confirm', async () => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
        connectionsRepository={stubConnections()}
      />,
      { route: '/?sel=cache-prod-01,cache-prod-02' },
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Restart 2…' })).not.toBeNull();
    });
    fireEvent.click(screen.getByRole('button', { name: 'Restart 2…' }));

    const dialog = screen.getByRole('dialog');
    const listItems = [...dialog.querySelectorAll('[data-bulk-restart-list] li')].map(
      (li) => li.textContent,
    );
    expect(listItems).toContain(
      'cache-prod-01 · 3 replicas · expect ~10–20s unavailability per replica',
    );
    expect(listItems).toContain(
      'cache-prod-02 · 2 replicas · expect ~10–20s unavailability per replica',
    );
    expect(
      within(dialog).getByText(
        'Writing configuration is not the same as applying it. These instances will restart to pick up staged config.',
      ),
    ).not.toBeNull();
    expect(
      within(dialog).getByText(
        'Restart is operator-initiated. The console confirms only that it wrote config and observed a restart — never that the process is using it.',
      ),
    ).not.toBeNull();

    fireEvent.click(within(dialog).getByRole('button', { name: 'Restart 2' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(screen.queryByText(/success/i)).toBeNull();
  });
});

describe('InstancesPage responsive (task 107, FE-D29)', () => {
  it('renders all 12 fixture instances as cards below md, same as the grid', async () => {
    setViewportWidth(320);
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    renderPage({ repository, solutionsRepository });

    await waitFor(() => {
      expect(document.querySelector('[data-card-view]')).not.toBeNull();
    });
    for (const instance of CACHE_INSTANCES) {
      expect(screen.getAllByText(instance.name).length).toBeGreaterThan(0);
    }
    expect(screen.queryByRole('grid')).toBeNull();
  });

  it('the card view emits the same chip labels as the grid', async () => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    setViewportWidth(1280);
    const wide = renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
        connectionsRepository={stubConnections()}
      />,
    );
    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });
    const wideLabels = [...document.querySelectorAll('.MuiChip-label')]
      .map((el) => el.textContent)
      .sort();
    wide.unmount();

    setViewportWidth(320);
    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
        connectionsRepository={stubConnections()}
      />,
    );
    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });
    const cardLabels = [...document.querySelectorAll('.MuiChip-label')]
      .map((el) => el.textContent)
      .sort();

    expect(cardLabels).toEqual(wideLabels);
  });

  it('selecting a card checkbox updates ?sel= in the URL, same contract as the grid', async () => {
    setViewportWidth(320);
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    function LocationProbe() {
      const [params] = useSearchParams();
      return <div data-testid="search-params">{params.toString()}</div>;
    }

    renderWithProviders(
      <>
        <InstancesPage
          solutionId="cache"
          repository={repository}
          solutionsRepository={solutionsRepository}
          hierarchyRepository={stubHierarchy()}
          connectionsRepository={stubConnections()}
        />
        <LocationProbe />
      </>,
    );

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select cache-prod-01' }));

    expect(screen.getByTestId('search-params').textContent).toBe('sel=cache-prod-01');
  });

  it('expands a card into the placement detail panel below md, same content as the grid', async () => {
    setViewportWidth(320);
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    renderPage({ repository, solutionsRepository });

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });
    const expander = screen.getAllByRole('button', { name: 'Expand row details' })[0];
    expect(expander).toBeDefined();
    if (expander === undefined) {
      return;
    }
    fireEvent.click(expander);

    expect(screen.getByText('ledger-0')).not.toBeNull();
  });

  it('the bulk-restart trigger moves to a sticky bottom bar below md', async () => {
    setViewportWidth(320);
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
        connectionsRepository={stubConnections()}
      />,
      { route: '/?sel=cache-prod-01' },
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Restart 1…' })).not.toBeNull();
    });
    const bar = document.querySelector('[data-bulk-actions-bar]');
    expect(bar).not.toBeNull();
    expect(within(bar as HTMLElement).getByRole('button', { name: 'Restart 1…' })).not.toBeNull();
  });

  it('does not force page-body horizontal scroll at the 320px reflow floor in the card view', async () => {
    setViewportWidth(reflowFloorPx);
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));
    const { container } = renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
        connectionsRepository={stubConnections()}
      />,
      { route: '/?sel=cache-prod-01' },
    );

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });

    const root = container.querySelector('[data-layout="page-width"]');
    expect(root).toBeInstanceOf(HTMLElement);
    if (!(root instanceof HTMLElement)) {
      return;
    }
    expect(() => {
      assertReflow(root, reflowFloorPx);
    }).not.toThrow();
  });
});
