import { describe, expect, it } from 'vitest';

import { permittedAggregationFunctions } from './aggregationOptions';

const ALL: readonly ('sum' | 'avg' | 'min' | 'max')[] = ['sum', 'avg', 'min', 'max'];

describe('permittedAggregationFunctions', () => {
  it('offers all four functions, in canonical order, for a gauge with full capabilities', () => {
    expect(permittedAggregationFunctions('gauge', ALL)).toEqual(['sum', 'avg', 'min', 'max']);
  });

  it('offers all four functions for a counter too — same UI list, different server formula', () => {
    expect(permittedAggregationFunctions('counter', ALL)).toEqual(['sum', 'avg', 'min', 'max']);
  });

  it('offers nothing for a histogram, regardless of advertised capabilities (unsupported in v1)', () => {
    expect(permittedAggregationFunctions('histogram', ALL)).toEqual([]);
  });

  it('offers nothing for an untyped (null) metric', () => {
    expect(permittedAggregationFunctions(null, ALL)).toEqual([]);
  });

  it('intersects with a restricted capability set — proves real intersection, not just type-based', () => {
    expect(permittedAggregationFunctions('gauge', ['sum', 'avg'])).toEqual(['sum', 'avg']);
  });

  it('canonicalizes order regardless of the input capability array order', () => {
    expect(permittedAggregationFunctions('gauge', ['max', 'sum'])).toEqual(['sum', 'max']);
  });

  it('offers nothing when the deployment advertises no aggregations at all', () => {
    expect(permittedAggregationFunctions('gauge', [])).toEqual([]);
  });
});
