import { useSearchParams } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';
import { createCpClient } from '@shared/api/cpClient';
import { createDpClient } from '@shared/api/dpClient';
import type { HierarchyRepository } from '@shared/hierarchy';
import { CACHE_INSTANCES, type Env } from '@shared/testdoubles/data/instances';
import { SOLUTIONS, type SolutionPackageSummary } from '@shared/testdoubles/data/solutions';
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
import { createSolutionsRepository, type SolutionsRepository } from '../solutions';

import { InstancesPage } from './InstancesPage';
import { createInstancesRepository, type InstancesRepository } from './instancesRepository';

/**
 * The Cache card as the catalog serves it. `packageVersion` matters here
 * (FE-118): the declare form's version options come from it, and the
 * Declare action is gated until this query settles.
 */
const CACHE_CARD = { ...SOLUTIONS[0], packageVersion: [6, 7] } as SolutionPackageSummary;

function stubSolutions(overrides: Partial<SolutionsRepository> = {}): SolutionsRepository {
  return {
    list: () => Promise.resolve([CACHE_CARD]),
    load: () =>
      Promise.resolve({ verified: true, devMode: false, message: 'ok', correlationId: 'c1' }),
    ...overrides,
  };
}

/**
 * The cascade's backing repository (`FE-D46`). Real values rather than
 * empty lists: a declare test that cannot pick an organization cannot
 * reach Submit.
 */
function stubHierarchy(overrides: Partial<HierarchyRepository> = {}): HierarchyRepository {
  return {
    organizations: () => Promise.resolve(['Acme Financial', 'Globex']),
    subOrgs: (org: string) => Promise.resolve(org === 'Acme Financial' ? ['Payments'] : ['Platform']),
    environments: () => Promise.resolve(['PROD', 'TEST'] as Env[]),
    ...overrides,
  };
}

function stubInstances(overrides: Partial<InstancesRepository> = {}): InstancesRepository {
  return {
    list: () => Promise.resolve({ items: [], total: 0 }),
    get: () => Promise.reject(new Error('not used')),
    declare: () => Promise.reject(new Error('not used')),
    undeclare: () => Promise.reject(new Error('not used')),
    ...overrides,
  };
}

describe('InstancesPage', () => {
  it('renders a loading skeleton while the query is in flight', () => {
    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={stubInstances({ list: () => new Promise(() => undefined) })}
        solutionsRepository={stubSolutions()}
        hierarchyRepository={stubHierarchy()}
      />,
    );

    expect(document.querySelectorAll('[data-skeleton]').length).toBeGreaterThan(0);
  });

  it('renders an error panel with retry — distinct from empty — when the query fails', async () => {
    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={stubInstances({
          list: () => Promise.reject(new ApiError({ status: 500, message: 'boom' })),
        })}
        solutionsRepository={stubSolutions()}
        hierarchyRepository={stubHierarchy()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('Couldn’t load instances')).not.toBeNull();
    });
    expect(screen.getByText('boom')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Retry' })).not.toBeNull();
    expect(screen.queryByText('No instances declared yet')).toBeNull();
  });

  it('renders the empty state, with Declare instance present and enabled', async () => {
    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={stubInstances()}
        solutionsRepository={stubSolutions()}
        hierarchyRepository={stubHierarchy()}
      />,
    );

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

  // Scoped to the DIALOG: `Package version` is now also a grid column
  // header, and the table behind the dialog carries one too.
  async function fillDeclareForm(name: string): Promise<void> {
    const dialog = within(screen.getByRole('dialog'));
    fireEvent.change(dialog.getByLabelText(/^Name/), { target: { value: name } });
    // Two versions are served, so one has to be chosen — no silent default.
    fireEvent.mouseDown(dialog.getByLabelText(/^Package version/));
    fireEvent.click(screen.getByRole('option', { name: '7' }));

    // Organization → Sub-organization → Environment, in that order and
    // awaiting each load (`FE-D46`). Setting the three independently would
    // go around the cascade the operator actually uses.
    fireEvent.mouseDown(dialog.getByLabelText(/^Organization/));
    fireEvent.click(await screen.findByRole('option', { name: 'Acme Financial' }));
    await waitFor(() => {
      expect(dialog.getByLabelText(/^Sub-organization/)).not.toHaveAttribute(
        'aria-disabled',
        'true',
      );
    });
    fireEvent.mouseDown(dialog.getByLabelText(/^Sub-organization/));
    fireEvent.click(await screen.findByRole('option', { name: 'Payments' }));
    const env = await dialog.findByRole('button', { name: 'PROD' });
    await waitFor(() => {
      expect(env).toBeEnabled();
    });
    fireEvent.click(env);
  }

  describe('undeclare', () => {
    async function renderWithSelection(overrides = {}) {
      const repository = stubInstances({
        list: () => Promise.resolve({ items: CACHE_INSTANCES, total: CACHE_INSTANCES.length }),
        ...overrides,
      });
      renderWithProviders(
        <InstancesPage
          solutionId="cache"
          repository={repository}
          solutionsRepository={stubSolutions()}
          hierarchyRepository={stubHierarchy()}
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
      // Both selected instances are named, so nothing is removed unseen.
      expect(within(dialog).getByText('cache-prod-01')).not.toBeNull();
      expect(within(dialog).getByText('cache-prod-02')).not.toBeNull();
    });

    it('removes each selected instance and clears them from ?sel=', async () => {
      const undeclare = vi.fn().mockResolvedValue({ correlationId: 'corr-9' });
      renderWithProviders(
        <>
          <InstancesPage
            solutionId="cache"
            repository={stubInstances({
              list: () =>
                Promise.resolve({ items: CACHE_INSTANCES, total: CACHE_INSTANCES.length }),
              undeclare,
            })}
            solutionsRepository={stubSolutions()}
            hierarchyRepository={stubHierarchy()}
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
      // A selection pointing at rows that no longer exist would re-arm the
      // bulk actions against nothing.
      await waitFor(() => {
        expect(screen.getByTestId('declare-search-params').textContent).toBe('');
      });
    });

    it('never counts a row that no longer exists as selected', async () => {
      // Regression: `DataTable` restores a selected id whose row has left
      // `rows` — correct for an env filter, wrong for a removal, and it
      // cannot tell them apart. Observed in the browser as "2 selected"
      // persisting over a grid the two rows had already left.
      renderWithProviders(
        <InstancesPage
          solutionId="cache"
          repository={stubInstances({
            list: () =>
              Promise.resolve({
                items: CACHE_INSTANCES.filter((row) => row.id !== 'cache-prod-01'),
                total: CACHE_INSTANCES.length - 1,
              }),
          })}
          solutionsRepository={stubSolutions()}
          hierarchyRepository={stubHierarchy()}
        />,
        // `?sel=` names a removed row and a live one.
        { route: '/?sel=cache-prod-01,cache-prod-02' },
      );
      await waitFor(() => {
        expect(screen.getByText('cache-prod-02')).not.toBeNull();
      });

      // Only the live row counts.
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
      // The dialog stays open: a partial failure the operator must act on
      // is not delivered as something that auto-dismisses.
      expect(screen.getByRole('dialog')).not.toBeNull();
    });
  });

  it('gates Declare until the catalog settles — you cannot pick from a list you cannot see', async () => {
    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={stubInstances({
          list: () => Promise.resolve({ items: CACHE_INSTANCES, total: CACHE_INSTANCES.length }),
        })}
        // Never settles: the version list is unknowable, so the action
        // must not be offered — and the tooltip says why.
        solutionsRepository={stubSolutions({ list: () => new Promise(() => undefined) })}
        hierarchyRepository={stubHierarchy()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });
    // The TABLE still renders — the catalog query is non-blocking for it.
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
    const declared = { ...CACHE_INSTANCES[0], id: 'ledger-eu-01', name: 'ledger-eu-01' };
    const declare = vi.fn().mockResolvedValue({ instance: declared, correlationId: 'corr-1' });

    await openDeclareDialog(
      stubInstances({
        list: () => Promise.resolve({ items: CACHE_INSTANCES, total: CACHE_INSTANCES.length }),
        declare,
      }),
    );

    await fillDeclareForm('ledger-eu-01');
    fireEvent.click(screen.getByRole('button', { name: 'Declare' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(declare).toHaveBeenCalledWith(
      'cache',
      expect.objectContaining({ name: 'ledger-eu-01', packageVersion: 7 }),
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
    // The operator's choices survive — a failure that discarded the form
    // would make them re-make every selection from memory. A MUI select
    // renders as a button-like div, so this reads its displayed text
    // rather than an input `value`.
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

    // Walk one level of the cascade, then abandon it.
    fireEvent.mouseDown(screen.getByLabelText(/^Organization/));
    fireEvent.click(await screen.findByRole('option', { name: 'Acme Financial' }));
    // By ROLE, not by label: `fireEvent.click` leaves the menu open, and
    // its `<ul>` shares the select's `aria-labelledby`, so a label query
    // matches both.
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

    // Scoped to the dialog: MUI keeps the closed one mounted through its
    // exit transition, so an unscoped query matches the abandoned draft
    // too — and would pass against exactly the state this test forbids.
    const reopened = within(screen.getByRole('dialog'));
    // MUI paints a zero-width space in an empty select, so this asserts
    // the absence of the abandoned choice rather than a literal ''.
    expect(reopened.getByRole('combobox', { name: /^Organization/ }).textContent).not.toContain(
      'Acme Financial',
    );
    // And the levels below it are back to gated, not merely blank.
    expect(reopened.getByText('Choose an organization first.')).not.toBeNull();
  });

  it('falls back to the raw solution id for the title when the solutions lookup has not resolved it', async () => {
    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={stubInstances()}
        solutionsRepository={stubSolutions({ list: () => new Promise(() => undefined) })}
        hierarchyRepository={stubHierarchy()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'cache' })).not.toBeNull();
    });
  });

  it('renders all 12 fixture instances against the real repository, title from the resolved solution name', async () => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Cache Engine' })).not.toBeNull();
    });

    for (const instance of CACHE_INSTANCES) {
      expect(screen.getAllByText(instance.name).length).toBeGreaterThan(0);
    }
  });

  it('expands a row into the replica detail panel on the expander click', async () => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
      />,
    );

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
    expect(screen.getByText('Configuration')).not.toBeNull();
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
      />,
      { route: '/?sel=cache-prod-01,cache-prod-02&focus=cache-prod-02' },
    );

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });

    expect(document.querySelector('[data-table-footer]')?.textContent).toContain('2 selected');
    expect(screen.getByText('ledger-b-0')).not.toBeNull(); // cache-prod-02's replica detail panel
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

    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('cache-prod-01')).not.toBeNull();
    });
    expect(screen.getByText('cache-dev-01')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'DEV' }));

    expect(screen.queryByText('cache-prod-01')).toBeNull();
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

    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
      />,
    );

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

    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
      />,
    );

    await waitFor(() => {
      expect(document.querySelector('[data-card-view]')).not.toBeNull();
    });
    for (const instance of CACHE_INSTANCES) {
      expect(screen.getAllByText(instance.name).length).toBeGreaterThan(0);
    }
    expect(screen.queryByRole('grid')).toBeNull();
  });

  it('the card view emits the same state-chip labels as the grid — zero driver/locus branches (REQ-005, REQ-008)', async () => {
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    setViewportWidth(1280);
    const wide = renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
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

  it('expands a card into the replica detail panel below md, same content as the grid', async () => {
    setViewportWidth(320);
    const repository = createInstancesRepository(createDpClient('/api'));
    const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

    renderWithProviders(
      <InstancesPage
        solutionId="cache"
        repository={repository}
        solutionsRepository={solutionsRepository}
        hierarchyRepository={stubHierarchy()}
      />,
    );

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
    expect(screen.getByText('Configuration')).not.toBeNull();
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
