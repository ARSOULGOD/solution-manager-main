import { describe, expect, it } from 'vitest';

import { allVocabEntries, healthVocab } from './index';

describe('state vocabulary', () => {
  it.each(allVocabEntries.map((entry) => [entry.key, entry] as const))(
    '%s renders a non-empty label and a non-null icon',
    (_key, entry) => {
      expect(entry.label.length).toBeGreaterThan(0);
      expect(entry.icon).not.toBeNull();
      expect(entry.icon).not.toBeUndefined();
    },
  );

  it('never resolves not_applicable to the error tone', () => {
    expect(healthVocab.not_applicable.tone).not.toBe('err');
  });
});
