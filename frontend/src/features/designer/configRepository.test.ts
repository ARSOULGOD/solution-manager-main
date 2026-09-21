import { describe, expect, it } from 'vitest';

import { createDpClient } from '@shared/api';

import { createConfigRepository } from './configRepository';

const dpClient = createDpClient('/api');

describe('createConfigRepository (fixture-backed)', () => {
  it('loads a designer view model, saves, and reads history', async () => {
    const repo = createConfigRepository(dpClient);

    const load = await repo.loadDesigner('cache-prod-01');
    expect(load.package.solution).toBe('cache');
    expect(load.packageVersion).toBeTruthy();
    expect(load.configGeneration).toBeTruthy();

    const saved = await repo.save('cache-prod-01', {
      fields: {},
      generation: load.configGeneration,
    });
    expect(saved.generation).toBeTruthy();
    expect(saved.correlationId).toBeTruthy();

    const history = await repo.history('cache-prod-01');
    expect(history.length).toBeGreaterThan(0);
  });

  it('setSecret() never echoes the value back — only a ref (G7, I6)', async () => {
    const repo = createConfigRepository(dpClient);

    const result = await repo.setSecret('cache-prod-01', 'solace.password', {
      value: 'super-secret-value',
    });

    expect(JSON.stringify(result)).not.toContain('super-secret-value');
    expect(result.ref).toBeTruthy();
  });
});
