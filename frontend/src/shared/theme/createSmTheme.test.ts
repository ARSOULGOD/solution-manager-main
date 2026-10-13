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

  // The global ring is sized against whatever element actually receives
  // focus. For an outlined `TextField`/`NumberField`/`Select`, that's the
  // `.MuiInputBase-input` itself, whose own border-box sits almost flush
  // with the visible outlined border — a ring drawn just outside it lands
  // on that border line, exactly where a floated label's notch sits, and
  // visually crosses the label. `Checkbox`/`Radio`/`Switch` never carry
  // this class (confirmed against `@mui/material` source — they render
  // their own `input` with a component-specific class instead), so
  // excluding it here cannot silently remove their indicator.
  it.each(['light', 'dark'] as const)(
    "excludes the outlined input's own focused element from the global ring (%s)",
    (mode) => {
      const theme = createSmTheme(mode);
      const override = theme.components?.MuiCssBaseline?.styleOverrides as
        Record<string, unknown> | undefined;
      const exclusion = override?.['.MuiInputBase-input:focus-visible'] as
        Record<string, unknown> | undefined;

      expect(exclusion?.boxShadow).toBe('none');
    },
  );

  // In its place: the field's OWN notch-aware indicator (MUI's
  // `.Mui-focused .notchedOutline`, which already goes to a 2px border
  // automatically) gets `palette.fieldFocusBorder` — sampled from the
  // Solace Broker Manager's own Try Me page (RGB 128,194,178 / #80C2B2,
  // pixel-sampled from a screenshot, not eyeballed) — used as-is in dark
  // mode (7.93:1 vs page / 7.02:1 vs paper, well clear of 3:1), and
  // darkened to #4B9C89 for light mode (same hue/saturation, lightness
  // moved — the technique `palette.ts`'s own "Contrast-derived values"
  // section already documents) since the sampled value itself measures
  // only ~2:1 there (`primary.main`, MUI's own default, fares no better —
  // 2.18:1 on this theme's dark surface — so neither can be trusted
  // un-adjusted).
  it.each(['light', 'dark'] as const)(
    "colours the outlined input's own focused border from palette.fieldFocusBorder, never an unverified literal (%s)",
    (mode) => {
      const theme = createSmTheme(mode);
      const root = theme.components?.MuiOutlinedInput?.styleOverrides?.root as
        Record<string, unknown> | undefined;
      const focusedRule = root?.['&.Mui-focused .MuiOutlinedInput-notchedOutline'] as
        Record<string, unknown> | undefined;

      expect(focusedRule?.borderColor).toBe(theme.palette.fieldFocusBorder);
      expect(theme.palette.fieldFocusBorder).toBe(mode === 'light' ? '#4B9C89' : '#80C2B2');
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

  // The Designer's fields are plain MUI `TextField`/`Select` (outlined
  // variant) with no per-field styling of their own — MUI's own baked-in
  // default hover (a jump straight to `text.primary`, against a resting
  // border that isn't even this theme's own `divider` token) is what the
  // reference CSS's `.tf input` never asked for: it defines a resting
  // border and a `:focus` state only, no hover rule. This theme-level
  // override is the one place that gap can be closed for every form field
  // at once.
  it.each(['light', 'dark'] as const)(
    "styles the outlined input's resting and hover border from this theme's own tokens (%s)",
    (mode) => {
      const theme = createSmTheme(mode);
      const styleOverrides = theme.components?.MuiOutlinedInput?.styleOverrides;

      // Resting border — the `notchedOutline` slot, not MUI's own baked-in
      // translucent black/white.
      const notchedOutline = styleOverrides?.notchedOutline as Record<string, unknown> | undefined;
      expect(notchedOutline?.borderColor).toBe(theme.palette.divider);

      // Hover — a subtler step than MUI's own default (`text.primary`),
      // scoped to the same `&:hover .notchedOutline` selector the base
      // component uses, so it overrides that one rule and nothing else.
      const root = styleOverrides?.root as Record<string, unknown> | undefined;
      const hoverRule = root?.['&:hover .MuiOutlinedInput-notchedOutline'] as
        Record<string, unknown> | undefined;
      expect(hoverRule?.borderColor).toBe(theme.palette.text.secondary);
      expect(hoverRule?.borderColor).not.toBe(theme.palette.text.primary);
    },
  );
});
