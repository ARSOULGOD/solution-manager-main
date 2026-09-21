import { describe, expect, it } from 'vitest';

import { createDpClient } from '@shared/api';

import { confirmActual, createApplyRepository, type ApplyOutcome } from './applyRepository';

const dpClient = createDpClient('/api');

describe('createApplyRepository (fixture-backed)', () => {
  it('stage() never returns a "succeeded" outcome — no such variant exists', async () => {
    const repo = createApplyRepository(dpClient);

    const outcome = await repo.stage('cache-prod-01', 'manual');

    expect(outcome.kind).not.toBe('CONFIRMED_BY_OPERATOR');
    expect(['NOT_STARTED', 'IN_FLIGHT', 'INDETERMINATE', 'DIVERGED']).toContain(outcome.kind);
  });

  it('observe() is the truth source for replica state', async () => {
    const repo = createApplyRepository(dpClient);

    const replicas = await repo.observe('cache-prod-01');

    expect(replicas.length).toBeGreaterThan(0);
  });
});

describe('confirmActual', () => {
  it('is the ONLY transition into CONFIRMED_BY_OPERATOR, and only from INDETERMINATE', () => {
    const indeterminate: ApplyOutcome = {
      kind: 'INDETERMINATE',
      reason: 'no idempotency key',
      observedAt: '2026-08-20T00:00:00Z',
      replicas: [],
    };

    const confirmed = confirmActual(indeterminate, '2026-08-20T00:05:00Z');

    expect(confirmed).toEqual({
      kind: 'CONFIRMED_BY_OPERATOR',
      confirmedAt: '2026-08-20T00:05:00Z',
      replicas: [],
    });
  });

  it('throws for any outcome that is not INDETERMINATE — no other kind may claim success', () => {
    const notStarted: ApplyOutcome = { kind: 'NOT_STARTED' };
    expect(() => confirmActual(notStarted, '2026-08-20T00:05:00Z')).toThrow();
  });
});
