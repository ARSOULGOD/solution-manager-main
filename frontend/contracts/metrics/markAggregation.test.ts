import { describe, expect, it } from 'vitest';

import { evaluateMarkAggregation, MARKS, marksRequiringAggregation } from './markAggregation';

describe('MARKS', () => {
  it('is the closed three-member set (DM-D12) — never a fourth "area" member', () => {
    expect(MARKS).toEqual(['line', 'gauge', 'single-stat']);
  });
});

describe('marksRequiringAggregation', () => {
  it('is false for line', () => {
    expect(marksRequiringAggregation('line')).toBe(false);
  });

  it('is true for gauge and single-stat', () => {
    expect(marksRequiringAggregation('gauge')).toBe(true);
    expect(marksRequiringAggregation('single-stat')).toBe(true);
  });
});

describe('evaluateMarkAggregation (DM-D22)', () => {
  it('refuses a gauge over more than one instance with acrossInstances: none', () => {
    expect(
      evaluateMarkAggregation({ mark: 'gauge', instanceCount: 3, acrossInstances: 'none' }),
    ).toEqual({ refused: true, code: 'STAT_REQUIRES_AGGREGATION' });
  });

  it('refuses a single-stat over more than one instance with acrossInstances: none', () => {
    expect(
      evaluateMarkAggregation({ mark: 'single-stat', instanceCount: 2, acrossInstances: 'none' }),
    ).toEqual({ refused: true, code: 'STAT_REQUIRES_AGGREGATION' });
  });

  it('does not refuse a single instance with acrossInstances: none — already one value', () => {
    expect(
      evaluateMarkAggregation({ mark: 'gauge', instanceCount: 1, acrossInstances: 'none' }),
    ).toEqual({ refused: false });
  });

  it('does not refuse multiple instances once aggregation is non-none', () => {
    for (const acrossInstances of ['sum', 'avg', 'min', 'max'] as const) {
      expect(
        evaluateMarkAggregation({ mark: 'single-stat', instanceCount: 5, acrossInstances }),
      ).toEqual({ refused: false });
    }
  });

  it('never refuses a line mark, regardless of instance count or aggregation', () => {
    expect(
      evaluateMarkAggregation({ mark: 'line', instanceCount: 10, acrossInstances: 'none' }),
    ).toEqual({ refused: false });
  });
});
