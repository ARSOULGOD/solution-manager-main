import { describe, expect, it } from 'vitest';

import { evaluateExploreGating } from './exploreGating';

describe('evaluateExploreGating', () => {
  it('step 1 (metric) is never locked, regardless of state', () => {
    expect(evaluateExploreGating({ metricState: null, acrossInstances: 'none' }).metric).toEqual({
      locked: false,
    });
    expect(
      evaluateExploreGating({ metricState: 'needs-typing', acrossInstances: 'none' }).metric,
    ).toEqual({ locked: false });
    expect(
      evaluateExploreGating({ metricState: 'plottable', acrossInstances: 'none' }).metric,
    ).toEqual({ locked: false });
  });

  it('locks 2-4 with metric-not-selected when nothing is chosen yet', () => {
    const locks = evaluateExploreGating({ metricState: null, acrossInstances: 'none' });
    expect(locks.instances).toEqual({ locked: true, reason: 'metric-not-selected' });
    expect(locks.seriesBy).toEqual({ locked: true, reason: 'metric-not-selected' });
    expect(locks.aggregation).toEqual({ locked: true, reason: 'metric-not-selected' });
  });

  it('locks 2-4 with metric-needs-typing for an untyped metric', () => {
    const locks = evaluateExploreGating({ metricState: 'needs-typing', acrossInstances: 'none' });
    expect(locks.instances).toEqual({ locked: true, reason: 'metric-needs-typing' });
    expect(locks.seriesBy).toEqual({ locked: true, reason: 'metric-needs-typing' });
    expect(locks.aggregation).toEqual({ locked: true, reason: 'metric-needs-typing' });
  });

  it('unlocks 2-4 for plottable and type-suspect metrics alike (type-suspect still plots)', () => {
    for (const metricState of ['plottable', 'type-suspect'] as const) {
      const locks = evaluateExploreGating({ metricState, acrossInstances: 'none' });
      expect(locks.instances).toEqual({ locked: false });
      expect(locks.seriesBy).toEqual({ locked: false });
      expect(locks.aggregation).toEqual({ locked: false });
    }
  });

  it('locks 5 (group by) while acrossInstances is none, independent of the metric', () => {
    const locks = evaluateExploreGating({ metricState: 'plottable', acrossInstances: 'none' });
    expect(locks.groupBy).toEqual({ locked: true, reason: 'aggregation-not-set' });
  });

  it('unlocks 5 (group by) once aggregation is any non-none value', () => {
    for (const acrossInstances of ['sum', 'avg', 'min', 'max'] as const) {
      const locks = evaluateExploreGating({ metricState: 'plottable', acrossInstances });
      expect(locks.groupBy).toEqual({ locked: false });
    }
  });

  it('group-by lock is unaffected by an untyped/unselected metric (independent gates)', () => {
    const locks = evaluateExploreGating({ metricState: null, acrossInstances: 'sum' });
    expect(locks.groupBy).toEqual({ locked: false });
  });
});
