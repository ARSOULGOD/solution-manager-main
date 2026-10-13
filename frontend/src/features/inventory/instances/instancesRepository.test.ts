import { describe, expect, it, vi } from 'vitest';

import { ApiError, createDpClient } from '@shared/api';
import type { PlaneClient } from '@shared/api';

import { createInstancesRepository } from './instancesRepository';

const dpClient = createDpClient('/api');

describe('createInstancesRepository (fixture-backed, real console.yaml shapes)', () => {
  it('lists and gets a single Cache instance', async () => {
    const repo = createInstancesRepository(dpClient);

    const items = await repo.list('cache', {});
    expect(items.length).toBe(12);

    const one = await repo.get('cache-prod-01');
    expect(one.instanceId).toBe('cache-prod-01');
    expect(one.placements.length).toBeGreaterThan(0);
  });

  describe('undeclare', () => {
    it('explicitly acknowledges that the workload is left running', async () => {
      const deleteWithCorrelation = vi.fn().mockResolvedValue({ correlationId: 'corr-1' });
      const repo = createInstancesRepository({ deleteWithCorrelation } as unknown as PlaneClient);

      await repo.undeclare('instance-1');

      expect(deleteWithCorrelation).toHaveBeenCalledWith('/instances/instance-1?workload=leave-running');
    });

    it('removes SM’s record and reports a server correlation id', async () => {
      const repo = createInstancesRepository(dpClient);

      const { correlationId } = await repo.undeclare('cache-prod-01');

      expect(correlationId).not.toBe('');
    });

    it('rejects an instance nothing has declared', async () => {
      const repo = createInstancesRepository(dpClient);

      await expect(repo.undeclare('never-declared-01')).rejects.toBeInstanceOf(ApiError);
    });
  });

  describe('declare', () => {
    it('returns the created instance (environment-addressed route, OR-52)', async () => {
      const repo = createInstancesRepository(dpClient);

      const { instance, correlationId } = await repo.declare('env-fixture-1', {
        solutionId: 'cache',
        name: 'ledger-eu-01',
        targetId: 'target-fixture-1',
        declaredPackageVersion: 7,
        placement: {
          containerName: 'ledger-eu-01',
          configDirectory: '/etc/cache',
          containerConfigPath: '/config',
          fileName: 'application.yml',
          healthPort: 8080,
        },
        acquisition: { mode: 'direct-http' },
      });

      expect(instance).toMatchObject({
        instanceId: 'ledger-eu-01',
        name: 'ledger-eu-01',
        solutionId: 'cache',
        declaredPackageVersion: 7,
        acquisition: { mode: 'direct-http' },
      });
      expect(instance.placements.length).toBe(1);
      expect(correlationId).not.toBe('');
    });

    it('rejects a name an existing instance already holds', async () => {
      const repo = createInstancesRepository(dpClient);

      await expect(
        repo.declare('env-fixture-1', {
          solutionId: 'cache',
          name: 'cache-prod-01',
          targetId: 'target-fixture-1',
          declaredPackageVersion: 7,
          placement: {
            containerName: 'cache-prod-01',
            configDirectory: '/etc/cache',
            containerConfigPath: '/config',
            fileName: 'application.yml',
            healthPort: 8080,
          },
          acquisition: { mode: 'direct-http' },
        }),
      ).rejects.toBeInstanceOf(ApiError);
    });
  });
});
