import { ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';
import { createCpClient } from '@shared/api/cpClient';
import type { AuthAdapter, Identity } from '@shared/auth/AuthAdapter';
import { AuthProvider } from '@shared/auth/AuthProvider';
import { createSmTheme } from '@shared/theme';
import { reflowFloorPx } from '@shared/theme/breakpoints';
import { useToastStore } from '@shared/ui';

import { assertReflow } from '../../../test/reflow';
import {
  fireEvent,
  renderWithProviders,
  screen,
  waitFor,
  within,
} from '../../../test/renderWithProviders';
import { validSolutionPackageFile } from '../../../test/solutionPackageFile';

import { SolutionsPage } from './SolutionsPage';
import { createSolutionsRepository, type SolutionsRepository } from './solutionsRepository';

const cpClient = createCpClient('/api');

function fakeAdapter(superAdmin: boolean): AuthAdapter {
  const identity: Identity = {
    username: 'devon.ramirez',
    source: 'local',
    mustRotate: false,
    superAdmin,
    organizationNames: ['ACME'],
    permissions: [],
  };
  return {
    getIdentity: () => Promise.resolve(identity),
    login: () => Promise.resolve(identity),
    logout: () => Promise.resolve(),
    rotateCredential: () => Promise.resolve(),
    onSessionExpired: () => () => {},
    getAccessToken: () => undefined,
  };
}

// `renderWithProviders` always yields `superAdmin: true` (Vitest always
// selects `devMockAdapter`) — this local helper is how `LoginForm.test.tsx`
// exercises a non-default identity, via `AuthProvider`'s test-only
// `adapterOverride` seam.
function renderAsIdentity(ui: ReactElement, superAdmin: boolean) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <ThemeProvider theme={createSmTheme('light')}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider cpClient={cpClient} adapterOverride={fakeAdapter(superAdmin)}>
          <MemoryRouter>{ui}</MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

function stubRepository(overrides: Partial<SolutionsRepository> = {}): SolutionsRepository {
  return {
    list: () => Promise.resolve([]),
    getPackage: () => Promise.reject(new Error('not used in this test')),
    load: () =>
      Promise.resolve({
        solutionId: 'widget-mesh',
        packageVersion: [1],
        signature: 'unsigned',
        stateVersion: 1,
        correlationId: 'corr-load-stub',
      }),
    ...overrides,
  };
}

describe('SolutionsPage', () => {
  it('renders a loading skeleton while the query is in flight', () => {
    const repository = stubRepository({ list: () => new Promise(() => undefined) });

    renderWithProviders(<SolutionsPage repository={repository} />);

    expect(document.querySelectorAll('[data-skeleton]').length).toBeGreaterThan(0);
    expect(screen.queryByText('Solutions')).toBeNull();
  });

  it('renders an error panel with retry — distinct from empty — when the query fails', async () => {
    const repository = stubRepository({
      list: () => Promise.reject(new ApiError({ status: 500, message: 'boom' })),
    });

    renderWithProviders(<SolutionsPage repository={repository} />);

    await waitFor(() => {
      expect(screen.getByText('Couldn’t load the catalog')).not.toBeNull();
    });
    expect(screen.getByText('boom')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Retry' })).not.toBeNull();
    expect(screen.queryByText('No solution packages loaded')).toBeNull();
  });

  it('renders the empty state when no packages are loaded', async () => {
    renderWithProviders(<SolutionsPage repository={stubRepository()} />);

    await waitFor(() => {
      expect(screen.getByText('No solution packages loaded')).not.toBeNull();
    });
  });

  it('renders one card per package from the real fixture', async () => {
    const repository = createSolutionsRepository(createCpClient('/api'));

    renderWithProviders(<SolutionsPage repository={repository} />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Solutions' })).not.toBeNull();
    });

    expect(screen.getByText('Cache Engine')).not.toBeNull();
    expect(screen.getByText('Cache Engine · nightly')).not.toBeNull();

    // v1 loads no signed packages (`Solution.signature` is always
    // `unsigned`) — every card shows the same informational chip, and
    // every Open button is enabled (plan 005 Task 2).
    const unsignedChips = screen.getAllByText('Unsigned (v1 loads no signed packages)');
    expect(unsignedChips.length).toBeGreaterThan(0);
    const openButtons = screen.getAllByRole('button', { name: 'Open' });
    expect(openButtons.length).toBeGreaterThan(0);
    expect(openButtons.every((button) => !button.hasAttribute('disabled'))).toBe(true);
  });

  it('does not force horizontal page-body scroll at 320px', async () => {
    const repository = createSolutionsRepository(createCpClient('/api'));
    const { container } = renderWithProviders(<SolutionsPage repository={repository} />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Solutions' })).not.toBeNull();
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

describe('SolutionsPage · load a package', () => {
  beforeEach(() => {
    useToastStore.setState({ toasts: [] });
  });

  it('shows the header trigger for a super-admin and opens the dialog', async () => {
    renderAsIdentity(<SolutionsPage repository={stubRepository()} />, true);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Solutions' })).not.toBeNull();
    });

    // The empty state ALSO renders its own "Load a package" action for a
    // super-admin (matches the reference: the header action is always
    // present, independent of catalog state) — scope to the header so this
    // asserts the trigger this task's Implementation bullet names.
    const header = document.querySelector('[data-page-header-actions]');
    expect(header).toBeInstanceOf(HTMLElement);
    const trigger = within(header as HTMLElement).getByRole('button', { name: 'Load a package' });
    fireEvent.click(trigger);

    expect(screen.getByText('Load a Solution Package')).not.toBeNull();
  });

  it('omits the header trigger for a non-super-admin and shows the ask-admin alert in the empty state', async () => {
    renderAsIdentity(<SolutionsPage repository={stubRepository()} />, false);

    await waitFor(() => {
      expect(screen.getByText('No solution packages loaded')).not.toBeNull();
    });

    expect(screen.queryByRole('button', { name: 'Load a package' })).toBeNull();
    expect(screen.getByText('Ask a Solace super-admin to load one.')).not.toBeNull();
  });

  it('on a successful load: closes the dialog, refreshes the catalog, and pushes a toast carrying the server correlation id', async () => {
    const list = vi.fn().mockResolvedValueOnce([]).mockResolvedValueOnce([]);
    const repository = stubRepository({
      list,
      load: () =>
        Promise.resolve({
          solutionId: 'widget-mesh',
          packageVersion: [1],
          signature: 'unsigned',
          stateVersion: 1,
          correlationId: 'corr-success-1',
        }),
    });

    renderAsIdentity(<SolutionsPage repository={repository} />, true);

    await waitFor(() => {
      expect(document.querySelector('[data-page-header-actions]')).not.toBeNull();
    });
    const header = document.querySelector('[data-page-header-actions]') as HTMLElement;
    fireEvent.click(within(header).getByRole('button', { name: 'Load a package' }));

    const input = document.querySelector('input[type="file"]');
    await userEvent.upload(input as HTMLInputElement, validSolutionPackageFile());
    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: 'Confirm and load' }).hasAttribute('disabled'),
      ).toBe(false);
    });

    fireEvent.click(screen.getByRole('button', { name: 'Confirm and load' }));

    await waitFor(() => {
      expect(screen.queryByText('Load a Solution Package')).toBeNull();
    });
    await waitFor(() => {
      expect(list).toHaveBeenCalledTimes(2);
    });
    const toasts = useToastStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0]).toMatchObject({
      title: 'Package loaded',
      correlationId: 'corr-success-1',
      severity: 'ok',
    });
  });
});
