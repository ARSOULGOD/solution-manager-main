import { describe, expect, it } from 'vitest';

import { composerRoleLabel } from './composerRoleLabel';

describe('composerRoleLabel', () => {
  it('labels a source role', () => {
    expect(composerRoleLabel('source')).toBe('Source');
  });

  it('labels a sink role', () => {
    expect(composerRoleLabel('sink')).toBe('Sink');
  });

  it('falls back to the generic label for an undeclared role', () => {
    expect(composerRoleLabel(undefined)).toBe('Component');
  });

  it('falls back to the generic label for a role outside the two named ones (`UI-D1`: role is authored, not a closed vocabulary)', () => {
    expect(composerRoleLabel('cache')).toBe('Component');
  });
});
