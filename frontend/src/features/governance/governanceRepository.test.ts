import { describe, expect, it } from 'vitest';

import { createDpClient } from '@shared/api';

import { createGovernanceRepository } from './governanceRepository';

const dpClient = createDpClient('/api');

describe('createGovernanceRepository (fixture-backed)', () => {
  it('resolves health as a placement list, with no top-level rollup', async () => {
    const repo = createGovernanceRepository(dpClient);

    const health = await repo.health('cache-prod-01');
    expect(health.placements.length).toBeGreaterThan(0);
    expect(health).not.toHaveProperty('rollup');
    expect(health).not.toHaveProperty('state');
    expect(health).not.toHaveProperty('stateVersion');
    for (const placement of health.placements) {
      expect(placement.placementId).toBeTruthy();
      expect(placement.state).toBeTruthy();
    }
  });

  it('resolves a config ladder with a top-level reached and a per-placement reached', async () => {
    const repo = createGovernanceRepository(dpClient);

    const ladder = await repo.configLadder('cache-prod-01');
    expect(ladder.rungs.length).toBe(5);
    expect(ladder.reached).toBeTruthy();
    expect(ladder.placements.length).toBeGreaterThan(0);
    for (const placement of ladder.placements) {
      expect(placement.placementId).toBeTruthy();
      expect(placement.reached).toBeTruthy();
    }
  });

  it('readLogs() resolves a bounded page, not a stream', async () => {
    const repo = createGovernanceRepository(dpClient);

    const page = await repo.readLogs('cache-prod-01', 'cache-engine');
    expect(page.lines.length).toBeGreaterThan(0);
    expect(page.unavailable).toBe(false);
    for (const line of page.lines) {
      expect(line.at).toBeTruthy();
      expect(line.message).toBeTruthy();
    }
  });

  it('readLogs() honours an after cursor', async () => {
    const repo = createGovernanceRepository(dpClient);

    const first = await repo.readLogs('cache-prod-01', 'cache-engine');
    const lastAt = first.lines[first.lines.length - 1]?.at ?? '';
    const next = await repo.readLogs('cache-prod-01', 'cache-engine', { after: lastAt });
    expect(next.lines.every((line) => line.at > lastAt)).toBe(true);
  });

  it('getLogLevel() resolves the current level for a component', async () => {
    const repo = createGovernanceRepository(dpClient);

    const state = await repo.getLogLevel('cache-prod-01', 'cache-engine');
    expect(state.component).toBe('cache-engine');
    expect(state.supported).toBe(true);
  });

  it('setLogLevel() answers an ApplyAccepted carrying the server correlation id', async () => {
    const repo = createGovernanceRepository(dpClient);

    const result = await repo.setLogLevel('cache-prod-01', 'cache-engine', {
      placementId: 'app01',
      level: 'DEBUG',
      mode: 'config',
    });
    expect(result.applyId).toBeTruthy();
    expect(result.correlationId).toBeTruthy();
  });
});
