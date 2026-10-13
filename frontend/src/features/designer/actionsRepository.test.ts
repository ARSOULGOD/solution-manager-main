import { describe, expect, it } from 'vitest';

import { createDpClient } from '@shared/api';

import { createActionsRepository } from './actionsRepository';

const dpClient = createDpClient('/api');

describe('createActionsRepository (fixture-backed)', () => {
  it('run() returns a verdict, never a credential (G7)', async () => {
    const repo = createActionsRepository(dpClient);

    const result = await repo.run('cache-prod-01', 'TEST_BROKER_CONNECTION');

    expect(typeof result.ok).toBe('boolean');
    expect(JSON.stringify(result)).not.toMatch(/password|secret/i);
  });
});
