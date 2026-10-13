/**
 * `HierarchyRepository` (task 7, plan 132) — the real backend returns a
 * `ScopeNodeList` envelope (`{ stateVersion, items: [{ id, name }] }`) and
 * expects UUID path segments, not display names. These tests exercise that
 * unwrapping and the internal id-cascade resolution directly against a
 * mocked `PlaneClient`, rather than the fixture-backed MSW handlers (which
 * still speak the old bare-array/name-keyed shape and are out of this
 * task's scope — see the task 7 report).
 */
import { describe, expect, it, vi } from 'vitest';

import type { PlaneClient } from '@shared/api';

import { createHierarchyRepository } from './hierarchyRepository';

/** Minimal `PlaneClient` double — only `get` is exercised by this repository. */
function fakeCpClient(overrides: Partial<PlaneClient>): PlaneClient {
  const notImplemented = (method: string) => () => {
    throw new Error(`fakeCpClient: ${method} was not stubbed for this test`);
  };
  return {
    get: notImplemented('get'),
    post: notImplemented('post'),
    put: notImplemented('put'),
    patch: notImplemented('patch'),
    delete: notImplemented('delete'),
    postWithCorrelation: notImplemented('postWithCorrelation'),
    patchWithCorrelation: notImplemented('patchWithCorrelation'),
    deleteWithCorrelation: notImplemented('deleteWithCorrelation'),
    resolve: notImplemented('resolve'),
    ...overrides,
  };
}

describe('createHierarchyRepository', () => {
  it('unwraps the ScopeNodeList envelope for organizations', async () => {
    const get = vi.fn().mockResolvedValue({
      stateVersion: 1,
      availability: null,
      items: [
        { id: 'org-uuid-1', name: 'Acme Financial' },
        { id: 'org-uuid-2', name: 'Globex' },
      ],
    });
    const repo = createHierarchyRepository(fakeCpClient({ get }));

    await expect(repo.organizations()).resolves.toEqual(['Acme Financial', 'Globex']);
    expect(get).toHaveBeenCalledWith('/hierarchy/organizations', {});
  });

  it('resolves the parent organization id from the prior organizations() call when fetching sub-orgs', async () => {
    const get = vi
      .fn()
      .mockResolvedValueOnce({ stateVersion: 1, items: [{ id: 'org-uuid-1', name: 'Acme Financial' }] })
      .mockResolvedValueOnce({ stateVersion: 1, items: [{ id: 'sub-uuid-1', name: 'Payments' }] });
    const repo = createHierarchyRepository(fakeCpClient({ get }));

    await repo.organizations();
    const subOrgs = await repo.subOrgs('Acme Financial');

    expect(subOrgs).toEqual(['Payments']);
    expect(get).toHaveBeenNthCalledWith(2, '/hierarchy/organizations/org-uuid-1/sub-orgs', {});
  });

  it('throws a clear error when subOrgs is called for a name not seen in the last organizations() call', async () => {
    const get = vi
      .fn()
      .mockResolvedValue({ stateVersion: 1, items: [{ id: 'org-uuid-1', name: 'Acme Financial' }] });
    const repo = createHierarchyRepository(fakeCpClient({ get }));

    await repo.organizations();

    await expect(repo.subOrgs('Nonexistent Org')).rejects.toThrow(/Nonexistent Org/);
  });

  it('resolves the parent sub-org id from the prior subOrgs() call when fetching environments', async () => {
    const get = vi
      .fn()
      .mockResolvedValueOnce({ stateVersion: 1, items: [{ id: 'org-uuid-1', name: 'Acme Financial' }] })
      .mockResolvedValueOnce({ stateVersion: 1, items: [{ id: 'sub-uuid-1', name: 'Payments' }] })
      .mockResolvedValueOnce({ stateVersion: 1, items: [{ id: 'env-uuid-1', name: 'PROD' }] });
    const repo = createHierarchyRepository(fakeCpClient({ get }));

    await repo.organizations();
    await repo.subOrgs('Acme Financial');
    const environments = await repo.environments('Acme Financial', 'Payments');

    expect(get).toHaveBeenNthCalledWith(
      3,
      '/hierarchy/organizations/org-uuid-1/sub-orgs/sub-uuid-1/environments',
      {},
    );
    expect(environments).toEqual(['PROD']);
  });

  it('self-heals an empty id cache by re-fetching the parent level, rather than throwing immediately (final review finding, plan 132)', async () => {
    // Simulates a repository instance created fresh after a route
    // remount (`InstancesRoute.tsx`'s `useMemo`) while React Query still
    // hands back a cached organization NAME from before the remount —
    // `orgIds` starts empty here, `organizations()` is never called by the
    // test directly, only implicitly via the self-heal.
    const get = vi
      .fn()
      .mockResolvedValueOnce({ stateVersion: 2, items: [{ id: 'org-uuid-1', name: 'Acme Financial' }] })
      .mockResolvedValueOnce({ stateVersion: 1, items: [{ id: 'sub-uuid-1', name: 'Payments' }] });
    const repo = createHierarchyRepository(fakeCpClient({ get }));

    const subOrgs = await repo.subOrgs('Acme Financial');

    expect(subOrgs).toEqual(['Payments']);
    expect(get).toHaveBeenNthCalledWith(1, '/hierarchy/organizations', {});
    expect(get).toHaveBeenNthCalledWith(2, '/hierarchy/organizations/org-uuid-1/sub-orgs', {});
  });

  it('still throws a clear error when a name is genuinely missing even after the self-heal refetch', async () => {
    // Unlike the self-heal case above, the refetched parent list genuinely
    // does not contain the requested name — this is Review Focus 5's real
    // case (a stale query re-selecting a name no longer in the freshly
    // fetched list), and the self-heal must not paper over it.
    const get = vi
      .fn()
      .mockResolvedValue({ stateVersion: 1, items: [{ id: 'org-uuid-1', name: 'Acme Financial' }] });
    const repo = createHierarchyRepository(fakeCpClient({ get }));

    await expect(repo.subOrgs('Nonexistent Org')).rejects.toThrow(/Nonexistent Org/);
    // One call for the self-heal refetch, confirming it genuinely retried
    // before giving up rather than throwing off a permanently-empty cache.
    expect(get).toHaveBeenCalledWith('/hierarchy/organizations', {});
  });
});
