import { describe, expect, it } from 'vitest';

import { createDpClient } from '@shared/api';

import { createGovernanceRepository } from './governanceRepository';

const dpClient = createDpClient('/api');

describe('createGovernanceRepository (fixture-backed)', () => {
  it('resolves health and a config ladder', async () => {
    const repo = createGovernanceRepository(dpClient);

    const health = await repo.health('cache-prod-01');
    expect(health.rollup).toBeTruthy();

    const ladder = await repo.configLadder('cache-prod-01');
    expect(ladder.steps.length).toBeGreaterThan(0);
  });

  it('fetchLogs() returns a timestamp-addressable page', async () => {
    const repo = createGovernanceRepository(dpClient);
    const page = await repo.fetchLogs('cache-prod-01', 'cache-engine', { limit: 200 });

    expect(page.lines.length).toBeGreaterThan(0);
    expect(page.hasOlder).toBe(false);
  });
});
