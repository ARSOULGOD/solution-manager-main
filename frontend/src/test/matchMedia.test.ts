import { afterEach, describe, expect, it } from 'vitest';

import { resetReducedMotion, resetViewportWidth, setReducedMotion, setViewportWidth } from './matchMedia';

// `setup.ts` already installs the polyfill globally before this file runs
// (guarded, so a second install here would be a no-op) — this only
// exercises the query-parsing behaviour itself against the real
// `window.matchMedia` every other test in the suite already relies on.
describe('matchMedia polyfill', () => {
  afterEach(() => {
    resetViewportWidth();
  });

  it('defaults to a desktop width — min-width queries at typical breakpoints match', () => {
    expect(window.matchMedia('@media (min-width:1240px)').matches).toBe(true);
    expect(window.matchMedia('@media (max-width:904.95px)').matches).toBe(false);
  });

  it('setViewportWidth moves both min-width and max-width queries', () => {
    setViewportWidth(320);

    expect(window.matchMedia('@media (min-width:1240px)').matches).toBe(false);
    expect(window.matchMedia('@media (min-width:600px)').matches).toBe(false);
    expect(window.matchMedia('@media (max-width:904.95px)').matches).toBe(true);
  });

  it('is inclusive at the exact boundary, matching CSS @media semantics', () => {
    setViewportWidth(1240);

    expect(window.matchMedia('@media (min-width:1240px)').matches).toBe(true);
  });

  it('resetViewportWidth returns to the desktop default', () => {
    setViewportWidth(320);
    resetViewportWidth();

    expect(window.matchMedia('@media (min-width:1240px)').matches).toBe(true);
  });

  it('exposes addEventListener/removeEventListener as no-ops MUI can subscribe to safely', () => {
    const list = window.matchMedia('@media (min-width:600px)');

    expect(() => {
      const handler = () => undefined;
      list.addEventListener('change', handler);
      list.removeEventListener('change', handler);
    }).not.toThrow();
  });
});

describe('matchMedia polyfill — prefers-reduced-motion', () => {
  afterEach(() => {
    resetReducedMotion();
  });

  it('defaults to no preference — reduced motion does not match', () => {
    expect(window.matchMedia('(prefers-reduced-motion: reduce)').matches).toBe(false);
  });

  it('setReducedMotion(true) makes the query match', () => {
    setReducedMotion(true);

    expect(window.matchMedia('(prefers-reduced-motion: reduce)').matches).toBe(true);
  });

  it('resetReducedMotion returns to the no-preference default', () => {
    setReducedMotion(true);
    resetReducedMotion();

    expect(window.matchMedia('(prefers-reduced-motion: reduce)').matches).toBe(false);
  });

  it('does not affect unrelated min-width/max-width queries', () => {
    setReducedMotion(true);

    expect(window.matchMedia('@media (min-width:1240px)').matches).toBe(true);
  });
});
