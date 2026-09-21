import { describe, expect, it } from 'vitest';

import { isScopePrefix, scopePathString, scopePathsEqual } from './scopePath';

describe('scopePathString', () => {
  it('joins a scope path with " / "', () => {
    expect(scopePathString(['Acme Financial', 'Payments'])).toBe('Acme Financial / Payments');
  });

  it('returns the single segment for a one-level path', () => {
    expect(scopePathString(['Payments'])).toBe('Payments');
  });

  // The EMPTY path is the global scope (`FE-D46`). Rendering it as '' would
  // print a blank cell in the assignments table where a super-admin's grant
  // should read.
  it('names the empty path rather than rendering nothing', () => {
    expect(scopePathString([])).toBe('All organizations');
  });
});

describe('isScopePrefix', () => {
  it('an ancestor scope reaches a descendant (grants downward)', () => {
    expect(isScopePrefix(['Acme Financial'], ['Acme Financial', 'Payments'])).toBe(true);
  });

  // What makes `[]` usable as the global scope at all.
  it('the empty path reaches everything', () => {
    expect(isScopePrefix([], ['Payments'])).toBe(true);
    expect(isScopePrefix([], ['Acme Financial', 'Trading', 'PROD'])).toBe(true);
    expect(isScopePrefix([], [])).toBe(true);
  });

  it('the same scope reaches itself', () => {
    expect(isScopePrefix(['Payments'], ['Payments'])).toBe(true);
  });

  it('a descendant scope does not reach its ancestor', () => {
    expect(isScopePrefix(['Acme Financial', 'Payments'], ['Acme Financial'])).toBe(false);
    expect(isScopePrefix(['Payments'], [])).toBe(false);
  });

  it('sibling scopes do not reach each other', () => {
    expect(isScopePrefix(['Acme Financial', 'Trading'], ['Acme Financial', 'Payments'])).toBe(false);
  });
});

describe('scopePathsEqual', () => {
  it('is true only when every segment matches', () => {
    expect(scopePathsEqual(['a', 'b'], ['a', 'b'])).toBe(true);
    expect(scopePathsEqual(['a'], ['a', 'b'])).toBe(false);
    expect(scopePathsEqual(['a', 'b'], ['a', 'c'])).toBe(false);
  });
});
