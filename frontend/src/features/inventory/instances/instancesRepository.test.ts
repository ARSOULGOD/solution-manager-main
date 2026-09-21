import { describe, expect, it } from 'vitest';

import { ApiError, createDpClient } from '@shared/api';

import { createInstancesRepository } from './instancesRepository';

const dpClient = createDpClient('/api');

describe('createInstancesRepository (fixture-backed)', () => {
  it('lists and gets a single Cache instance', async () => {
    const repo = createInstancesRepository(dpClient);

    const page = await repo.list('cache', {});
    expect(page.total).toBe(12);

    const one = await repo.get('cache-prod-01');
    expect(one.id).toBe('cache-prod-01');
  });

  describe('undeclare', () => {
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
    it('returns the created row with every observed fact stated as not-yet-observed', async () => {
      const repo = createInstancesRepository(dpClient);

      const { instance, correlationId } = await repo.declare('cache', {
        name: 'ledger-eu-01',
        packageVersion: 7,
        env: 'TEST',
        org: 'Acme Financial',
        subOrg: 'Payments',
        driver: 'k8s',
        locus: 'host_local',
        replicas: ['ledger-eu-0', 'ledger-eu-1'],
      });

      expect(instance).toMatchObject({
        id: 'ledger-eu-01',
        name: 'ledger-eu-01',
        solutionId: 'cache',
        env: 'TEST',
        driver: 'k8s',
        locus: 'host_local',
      });
      // Never `healthy`, never a timestamp — nothing has observed it.
      expect(instance.healthRollup).toBe('not_applicable');
      expect(instance.configState).toBe('na');
      expect(instance.lastSeen).toBe('never');
      expect(instance.version).toEqual({ state: 'unverified', observed: '—', declared: '—' });
      expect(instance.replicas).toEqual([
        { name: 'ledger-eu-0', health: 'not_applicable' },
        { name: 'ledger-eu-1', health: 'not_applicable' },
      ]);
      expect(correlationId).not.toBe('');
    });

    it('reports a declared version, and only that, as declared', async () => {
      const repo = createInstancesRepository(dpClient);

      const { instance } = await repo.declare('cache', {
        name: 'ledger-eu-02',
        packageVersion: 7,
        env: 'DEV',
        org: 'Acme Financial',
        subOrg: 'Payments',
        driver: 'docker',
        locus: 'dp_remote',
        replicas: ['ledger-eu-02-0'],
        declaredVersion: '2.3.0',
      });

      expect(instance.version).toEqual({
        state: 'unverified',
        observed: '—',
        declared: '2.3.0',
      });
    });

    it('rejects a name an existing instance already holds', async () => {
      const repo = createInstancesRepository(dpClient);

      await expect(
        repo.declare('cache', {
          name: 'cache-prod-01',
          packageVersion: 7,
          env: 'PROD',
          org: 'Acme Financial',
          subOrg: 'Payments',
          driver: 'k8s',
          locus: 'dp_remote',
          replicas: ['ledger-0'],
        }),
      ).rejects.toBeInstanceOf(ApiError);
    });
  });
});
