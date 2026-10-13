import { describe, expect, it } from 'vitest';

import { createDpClient } from '@shared/api';

import {
  confirmActual,
  createApplyRepository,
  outcomeFromObservation,
  type ApplyObservationWire,
  type ApplyOutcome,
} from './applyRepository';

const dpClient = createDpClient('/api');

describe('createApplyRepository (fixture-backed)', () => {
  it('stage() never returns a "succeeded" outcome — no such variant exists', async () => {
    const repo = createApplyRepository(dpClient);

    const outcome = await repo.stage('cache-prod-01', 'placement-0', 1, 'manual');

    expect(outcome.kind).not.toBe('CONFIRMED_BY_OPERATOR');
    expect(['NOT_STARTED', 'IN_FLIGHT', 'INDETERMINATE', 'DIVERGED']).toContain(outcome.kind);
  });

  it('observe() is the truth source for replica state', async () => {
    const repo = createApplyRepository(dpClient);

    const replicas = await repo.observe('cache-prod-01');

    expect(replicas.length).toBeGreaterThan(0);
  });
});

describe('outcomeFromObservation — definite vs. genuinely ambiguous', () => {
  const base: ApplyObservationWire = {
    applyId: 'apply-1',
    state: 'APPLY_INDETERMINATE',
    terminal: true,
  };

  it.each(['FAILED_NO_EFFECT', 'FAILED_ROLLED_BACK', 'STOP_FAILED'])(
    'marks %s as a definite, measured outcome',
    (state) => {
      const outcome = outcomeFromObservation('instance-1', { ...base, state });

      expect(outcome.kind).toBe('INDETERMINATE');
      expect((outcome as Extract<ApplyOutcome, { kind: 'INDETERMINATE' }>).definite).toBe(true);
    },
  );

  it.each(['APPLY_INDETERMINATE', 'CONFIG_DIVERGED', 'APPLIED', 'SUPERSEDED'])(
    'does not mark %s as definite — it is a real ambiguity or a success the operator still confirms',
    (state) => {
      const outcome = outcomeFromObservation('instance-1', { ...base, state });

      expect((outcome as Extract<ApplyOutcome, { kind: 'INDETERMINATE' }>).definite).toBe(false);
    },
  );
});

describe('confirmActual', () => {
  it('is the ONLY transition into CONFIRMED_BY_OPERATOR, and only from INDETERMINATE', () => {
    const indeterminate: ApplyOutcome = {
      kind: 'INDETERMINATE',
      reason: 'no idempotency key',
      observedAt: '2026-08-20T00:00:00Z',
      definite: false,
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
