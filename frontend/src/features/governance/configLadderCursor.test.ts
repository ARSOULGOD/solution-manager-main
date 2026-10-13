import { describe, expect, it } from 'vitest';

import { clampReached } from './configLadderCursor';

describe('clampReached', () => {
  it('passes through any rung below effective-verified unchanged', () => {
    expect(clampReached('desired')).toBe('desired');
    expect(clampReached('rendered')).toBe('rendered');
    expect(clampReached('persisted')).toBe('persisted');
    expect(clampReached('restart-observed')).toBe('restart-observed');
  });

  it('never returns effective-verified, even when the wire claims it is reached', () => {
    expect(clampReached('effective-verified')).toBe('restart-observed');
  });
});
