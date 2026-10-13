import { afterEach, describe, expect, it } from 'vitest';

import {
  discardExploreDraft,
  generateExploreDraftId,
  loadExploreDraft,
  saveExploreDraft,
  type ExploreDraftDocument,
} from './exploreDraftStore';

const DOC: ExploreDraftDocument = {
  metric: 'cache.hit_rate',
  seriesBy: 'replica',
  acrossInstances: 'avg',
  groupBy: ['region'],
  resolution: 'raw',
};

afterEach(() => {
  sessionStorage.clear();
});

describe('generateExploreDraftId', () => {
  it('mints a short, opaque id, different every call', () => {
    const a = generateExploreDraftId();
    const b = generateExploreDraftId();
    expect(a).not.toEqual(b);
    expect(a).toHaveLength(12);
    expect(a).toMatch(/^[0-9a-f]{12}$/);
  });
});

describe('saveExploreDraft / loadExploreDraft', () => {
  it('round-trips a document', () => {
    saveExploreDraft('d1', DOC);
    expect(loadExploreDraft('d1')).toEqual(DOC);
  });

  it('keeps two draft ids independent', () => {
    saveExploreDraft('d1', DOC);
    saveExploreDraft('d2', { ...DOC, metric: 'cache.evictions', groupBy: [] });
    expect(loadExploreDraft('d1')?.metric).toBe('cache.hit_rate');
    expect(loadExploreDraft('d2')?.metric).toBe('cache.evictions');
  });

  it('returns null for an id that was never saved', () => {
    expect(loadExploreDraft('never-saved')).toBeNull();
  });

  it('re-saving the same id overwrites, not merges', () => {
    saveExploreDraft('d1', DOC);
    saveExploreDraft('d1', { ...DOC, groupBy: [] });
    expect(loadExploreDraft('d1')?.groupBy).toEqual([]);
  });
});

describe('untrusted storage — a stored entry is revalidated, never trusted as-is', () => {
  it('treats unparseable JSON as no draft', () => {
    sessionStorage.setItem('smc.exploreDraft.d1', '{not json');
    expect(loadExploreDraft('d1')).toBeNull();
  });

  it('treats a non-object as no draft', () => {
    sessionStorage.setItem('smc.exploreDraft.d1', '"just a string"');
    expect(loadExploreDraft('d1')).toBeNull();
  });

  it('rejects an invalid seriesBy', () => {
    sessionStorage.setItem(
      'smc.exploreDraft.d1',
      JSON.stringify({ ...DOC, seriesBy: 'bogus' }),
    );
    expect(loadExploreDraft('d1')).toBeNull();
  });

  it('rejects an invalid acrossInstances', () => {
    sessionStorage.setItem(
      'smc.exploreDraft.d1',
      JSON.stringify({ ...DOC, acrossInstances: 'median' }),
    );
    expect(loadExploreDraft('d1')).toBeNull();
  });

  it('rejects a non-array groupBy', () => {
    sessionStorage.setItem(
      'smc.exploreDraft.d1',
      JSON.stringify({ ...DOC, groupBy: 'region' }),
    );
    expect(loadExploreDraft('d1')).toBeNull();
  });

  it('rejects an invalid resolution', () => {
    sessionStorage.setItem(
      'smc.exploreDraft.d1',
      JSON.stringify({ ...DOC, resolution: 'hourly' }),
    );
    expect(loadExploreDraft('d1')).toBeNull();
  });

  it('accepts a null metric (no metric chosen yet)', () => {
    saveExploreDraft('d1', { ...DOC, metric: null });
    expect(loadExploreDraft('d1')?.metric).toBeNull();
  });
});

describe('discardExploreDraft', () => {
  it('removes a saved draft', () => {
    saveExploreDraft('d1', DOC);
    discardExploreDraft('d1');
    expect(loadExploreDraft('d1')).toBeNull();
  });

  it('is a no-op for an id that was never saved', () => {
    expect(() => discardExploreDraft('never-saved')).not.toThrow();
  });
});
