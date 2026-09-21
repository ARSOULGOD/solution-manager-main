import { describe, expect, it } from 'vitest';

import './tokens.css';
import { breakpointValues, columnsAtWidth, cssBreakpointVars, reflowFloorPx } from './breakpoints';
import { createSmTheme } from './createSmTheme';

function cssPx(name: string): number {
  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  expect(raw.endsWith('px')).toBe(true);
  return Number.parseInt(raw, 10);
}

describe('breakpoints — one source for theme and CSS', () => {
  it('CSS custom properties match breakpointValues and the reflow floor', () => {
    expect(cssPx(cssBreakpointVars.floor)).toBe(reflowFloorPx);
    expect(cssPx(cssBreakpointVars.xs)).toBe(breakpointValues.xs);
    expect(cssPx(cssBreakpointVars.sm)).toBe(breakpointValues.sm);
    expect(cssPx(cssBreakpointVars.md)).toBe(breakpointValues.md);
    expect(cssPx(cssBreakpointVars.lg)).toBe(breakpointValues.lg);
    expect(cssPx(cssBreakpointVars.xl)).toBe(breakpointValues.xl);
  });

  it('createSmTheme consumes breakpointValues, not a second literal set', () => {
    const theme = createSmTheme('light');
    expect(theme.breakpoints.values).toEqual({ ...breakpointValues });
  });

  it('columnsAtWidth resolves at the five asserted widths', () => {
    const columns = { xs: 1, sm: 2, md: 2, lg: 3, xl: 4 };
    expect(columnsAtWidth(320, columns)).toBe(1);
    expect(columnsAtWidth(600, columns)).toBe(2);
    expect(columnsAtWidth(905, columns)).toBe(2);
    expect(columnsAtWidth(1240, columns)).toBe(3);
    expect(columnsAtWidth(1560, columns)).toBe(4);
  });
});
