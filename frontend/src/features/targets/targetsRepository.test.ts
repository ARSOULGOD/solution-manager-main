import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api';

import { createTargetsRepository } from './targetsRepository';

const cpClient = createCpClient('/api');

describe('createTargetsRepository (fixture-backed)', () => {
  it('lists targets', async () => {
    const repo = createTargetsRepository(cpClient);

    const targets = await repo.list();

    expect(targets.length).toBeGreaterThan(0);
  });

  it('has no write method — read-only by design', () => {
    const repo = createTargetsRepository(cpClient);

    expect('create' in repo).toBe(false);
    expect('update' in repo).toBe(false);
    expect('delete' in repo).toBe(false);
  });
});
