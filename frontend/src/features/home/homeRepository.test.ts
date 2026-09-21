import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api';

import { createHomeRepository } from './homeRepository';

const cpClient = createCpClient('/api');

describe('createHomeRepository (fixture-backed)', () => {
  it('resolves a summary and an attention feed', async () => {
    const repo = createHomeRepository(cpClient);

    const summary = await repo.summary();
    expect(summary.instancesDeclared).toBeGreaterThan(0);

    const attention = await repo.attention();
    expect(attention.length).toBeGreaterThan(0);
  });
});
