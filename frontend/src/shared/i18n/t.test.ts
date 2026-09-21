import { describe, expect, it } from 'vitest';

import { t } from './t';

describe('t', () => {
  it('returns a plain catalogue string unchanged', () => {
    expect(t('retry')).toBe('Retry');
  });

  it('selects the singular plural form for count 1', () => {
    expect(t('replicaCount', { count: 1 })).toBe('1 replica');
  });

  it('selects the plural form for count other than 1', () => {
    expect(t('replicaCount', { count: 3 })).toBe('3 replicas');
    expect(t('replicaCount', { count: 0 })).toBe('0 replicas');
  });

  it('interpolates additional named params alongside count', () => {
    expect(t('fieldMatchCount', { count: 2, query: 'timeout' })).toBe('2 fields match “timeout”');
    expect(t('fieldMatchCount', { count: 1, query: 'timeout' })).toBe('1 field matches “timeout”');
  });

  it('throws when a plural message is requested without a numeric count', () => {
    expect(() => t('replicaCount', {} as unknown as { count: number })).toThrow(/count/);
  });
});
