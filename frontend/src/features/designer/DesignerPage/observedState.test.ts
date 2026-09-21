import { describe, expect, it } from 'vitest';

import type { ObservedState, VersionState } from '@contracts/view-model/types';

import { isEditingBlocked } from './observedState';

function observed(versionState: VersionState): ObservedState {
  return { health: 'healthy', versionState, configState: 'in_sync' };
}

describe('isEditingBlocked', () => {
  it('blocks on drift', () => {
    expect(isEditingBlocked(observed('drift'))).toBe(true);
  });

  it('blocks on unknown', () => {
    expect(isEditingBlocked(observed('unknown'))).toBe(true);
  });

  it('does not block on unverified — the permanent-normal state for an MI with no version-reporting mechanism (UI-D12)', () => {
    expect(isEditingBlocked(observed('unverified'))).toBe(false);
  });

  it('does not block on ok', () => {
    expect(isEditingBlocked(observed('ok'))).toBe(false);
  });
});
