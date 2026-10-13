import { describe, expect, it } from 'vitest';

import { getBuildInfo } from './buildInfo';

describe('getBuildInfo', () => {
  it('falls back to non-blank labels when CI has not injected build identity', () => {
    const info = getBuildInfo();

    expect(info.version.length).toBeGreaterThan(0);
    expect(info.commit.length).toBeGreaterThan(0);
  });
});
