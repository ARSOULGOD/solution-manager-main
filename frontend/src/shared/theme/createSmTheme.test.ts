import { describe, expect, it } from 'vitest';

import { createSmTheme } from './createSmTheme';

const HEX_LITERAL = /#[0-9a-f]{3,8}\b/i;

describe('createSmTheme', () => {
  it('builds a light and a dark theme from the same factory', () => {
    const light = createSmTheme('light');
    const dark = createSmTheme('dark');

    expect(light.palette.mode).toBe('light');
    expect(dark.palette.mode).toBe('dark');
  });

  it('mode changes the resolved palette, not just the mode flag', () => {
    const light = createSmTheme('light');
    const dark = createSmTheme('dark');

    expect(light.palette.background.default).not.toBe(dark.palette.background.default);
    expect(light.palette.background.paper).not.toBe(dark.palette.background.paper);
    expect(light.palette.text.primary).not.toBe(dark.palette.text.primary);
    expect(light.palette.divider).not.toBe(dark.palette.divider);
  });

  it('exposes the status vocabulary typed on the palette', () => {
    const theme = createSmTheme('light');

    expect(theme.palette.health.ok).toBeTruthy();
    expect(theme.palette.health.down).toBeTruthy();
    expect(theme.palette.health.restartExpected).toBeTruthy();
    expect(theme.palette.health.inactiveReplica).toBeTruthy();
    expect(theme.palette.pending).toBeTruthy();
    expect(theme.palette.unverified).toBeTruthy();
  });

  it('exposes a ten-colour categorical chart palette', () => {
    const theme = createSmTheme('light');

    expect(theme.palette.chart.categorical).toHaveLength(10);
    expect(new Set(theme.palette.chart.categorical).size).toBe(10);
  });

  it('takes breakpoints from the token layer, not MUI defaults', () => {
    const theme = createSmTheme('light');

    expect(theme.breakpoints.values).toEqual({ xs: 0, sm: 600, md: 905, lg: 1240, xl: 1560 });
  });

  it('derives shape.borderRadius from the design tokens (8px)', () => {
    const theme = createSmTheme('light');

    expect(theme.shape.borderRadius).toBe(8);
  });

  it('produces exactly 25 shadow entries, index 0 is none', () => {
    const theme = createSmTheme('light');

    expect(theme.shadows).toHaveLength(25);
    expect(theme.shadows[0]).toBe('none');
  });

  // FE-D39: the ring is two concentric box-shadow bands, not one outline —
  // brand green alone is 2.17:1 on white, short of SC 1.4.11's 3:1. Green
  // is still the band nearest the control, so the ring reads as brand. The
  // contrast side of this is asserted in `palette.contrast.test.ts`; here
  // we only pin the shape of the rule.
  it.each(['light', 'dark'] as const)(
    'sets a two-band focus-visible ring, brand green innermost (%s)',
    (mode) => {
      const theme = createSmTheme(mode);
      const override = theme.components?.MuiCssBaseline?.styleOverrides as
        Record<string, unknown> | undefined;
      const rule = override?.['*:focus-visible'] as Record<string, unknown> | undefined;
      const shadow = rule?.boxShadow as string | undefined;

      expect(shadow).toContain(theme.palette.focusRing.inner);
      expect(shadow).toContain(theme.palette.focusRing.outer);
      expect(theme.palette.focusRing.inner).toBe('#00C895');
      // Green first = green nearest the control.
      expect(shadow?.indexOf(theme.palette.focusRing.inner)).toBeLessThan(
        shadow?.indexOf(theme.palette.focusRing.outer) ?? -1,
      );
      // Transparent outline underneath, so forced-colors mode — which drops
      // box-shadows — still repaints a ring in the user's system colour.
      expect(rule?.outline).toBe('2px solid transparent');
    },
  );

  it('spacing unit is 4px, matching the token base', () => {
    const theme = createSmTheme('light');

    expect(theme.spacing(1)).toBe('4px');
  });

  it('no literal hex colour appears in typography or breakpoint config', () => {
    const theme = createSmTheme('light');

    expect(HEX_LITERAL.test(JSON.stringify(theme.typography))).toBe(false);
    expect(HEX_LITERAL.test(JSON.stringify(theme.breakpoints))).toBe(false);
  });
});
