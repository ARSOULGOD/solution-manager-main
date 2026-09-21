import { describe, expect, it } from 'vitest';

import { deriveReachedRung } from './configLadderCursor';
import type { ConfigLadder } from './governanceRepository';

function ladder(reached: Partial<Record<string, boolean>>): ConfigLadder {
  const keys = [
    'desired',
    'rendered',
    'persisted',
    'restart-observed',
    'effective-verified',
  ] as const;
  return {
    steps: keys.map((key) => ({ key, label: key, reached: reached[key] ?? false })),
  };
}

describe('deriveReachedRung', () => {
  it('stops at restart-observed when every rung up to it is reached', () => {
    const result = deriveReachedRung(
      ladder({ desired: true, rendered: true, persisted: true, 'restart-observed': true }).steps,
    );
    expect(result).toBe('restart-observed');
  });

  it('never returns effective-verified, even when the wire claims it is reached', () => {
    const result = deriveReachedRung(
      ladder({
        desired: true,
        rendered: true,
        persisted: true,
        'restart-observed': true,
        'effective-verified': true,
      }).steps,
    );
    expect(result).toBe('restart-observed');
  });

  it('stops at the first gap rather than the highest reached rung', () => {
    const result = deriveReachedRung(
      ladder({ desired: true, rendered: true, persisted: false, 'restart-observed': true }).steps,
    );
    expect(result).toBe('rendered');
  });

  it('defaults to desired when nothing is reached', () => {
    const result = deriveReachedRung(ladder({}).steps);
    expect(result).toBe('desired');
  });
});
