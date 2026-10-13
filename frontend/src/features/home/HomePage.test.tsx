import { describe, expect, it } from 'vitest';

import { ApiError } from '@shared/api';
import { createCpClient } from '@shared/api/cpClient';
import { createDpClient } from '@shared/api/dpClient';

import { renderWithProviders, screen, waitFor } from '../../test/renderWithProviders';

import { HomePage } from './HomePage';
import {
  createHomeRepository,
  type AttentionItem,
  type HomeRepository,
  type HomeSummary,
} from './homeRepository';

const POPULATED_SUMMARY: HomeSummary = {
  solutionsManaged: 2,
  instancesDeclared: 12,
  needAttentionNow: 3,
  configStagedNotApplied: 1,
};

const SAMPLE_ATTENTION: readonly AttentionItem[] = [
  {
    id: 'cache-stage-01-health',
    severity: 'err',
    title: 'cache-stage-01 · Health',
    detail: 'State: red.',
    instanceId: 'cache-stage-01',
    href: '/solutions/cache/instances/cache-stage-01/governance',
  },
];

function stubRepository(overrides: Partial<HomeRepository> = {}): HomeRepository {
  return {
    summary: () => Promise.resolve(POPULATED_SUMMARY),
    attention: () => Promise.resolve(SAMPLE_ATTENTION.slice()),
    ...overrides,
  };
}

describe('HomePage', () => {
  it('renders a loading skeleton while the queries are in flight', () => {
    const repository: HomeRepository = {
      summary: () => new Promise(() => undefined),
      attention: () => new Promise(() => undefined),
    };

    renderWithProviders(<HomePage repository={repository} />);

    expect(document.querySelectorAll('[data-skeleton]').length).toBeGreaterThan(0);
    expect(screen.queryByText('Home')).toBeNull();
  });

  it('renders an error panel with retry — distinct from empty — when a query fails', async () => {
    const repository = stubRepository({
      summary: () => Promise.reject(new ApiError({ status: 500, message: 'boom' })),
    });

    renderWithProviders(<HomePage repository={repository} />);

    await waitFor(() => {
      expect(screen.getByText('Couldn’t load your home view')).not.toBeNull();
    });
    expect(screen.getByText('boom')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Retry' })).not.toBeNull();
    expect(screen.queryByText('Nothing needs attention.')).toBeNull();
  });

  it('renders the first-run card when no solution package is loaded', async () => {
    const repository = stubRepository({
      summary: () =>
        Promise.resolve({
          solutionsManaged: 0,
          instancesDeclared: 0,
          needAttentionNow: null,
          configStagedNotApplied: null,
        }),
      attention: () => Promise.resolve([]),
    });

    renderWithProviders(<HomePage repository={repository} />);

    await waitFor(() => {
      expect(screen.getByText('Load a Solution Package to begin')).not.toBeNull();
    });
  });

  it('renders the partial banner and the "+1?" suffix when the summary is partial', async () => {
    const repository = stubRepository({
      summary: () =>
        Promise.resolve({ ...POPULATED_SUMMARY, partial: { unreportingHost: 'cache-edge-01' } }),
    });

    renderWithProviders(<HomePage repository={repository} />);

    await waitFor(() => {
      expect(screen.getByRole('status')).not.toBeNull();
    });
    const banner = screen.getByRole('status');
    expect(banner.textContent).toContain('Live state for one target is unavailable');
    expect(banner.textContent).toContain('cache-edge-01');
    expect(screen.getByText('12 +1?')).not.toBeNull();
  });

  it('renders the populated view — strip, feed and the no-alerting footer line', async () => {
    const repository = createHomeRepository(createCpClient('/api'), createDpClient('/api'));

    renderWithProviders(<HomePage repository={repository} />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Home' })).not.toBeNull();
    });
    expect(screen.getByText('Solutions managed')).not.toBeNull();
    expect(screen.getByText('Needs attention')).not.toBeNull();
    expect(
      screen.getByText(
        'Solution Manager surfaces these here. It does not notify or page anyone — no alerting is configured in this release.',
      ),
    ).not.toBeNull();
  });
});
