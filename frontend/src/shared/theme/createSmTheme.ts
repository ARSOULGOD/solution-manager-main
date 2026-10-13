/**
 * One createTheme object, light and dark from the same tokens (design §8.1,
 * FE-D5). Feature code reads theme.spacing / palette / typography /
 * breakpoints only — never the token layer directly.
 */
import { createTheme, type Shadows, type Theme, type ThemeOptions } from '@mui/material/styles';

import { breakpointValues } from './breakpoints';
import { createPalette, type SmThemeMode } from './palette';
import { tokens } from './tokens';

export type { SmThemeMode };

// MUI expects exactly 25 shadow strings (index 0 = 'none'). The DS defines
// five depths; each is held across a contiguous elevation band so component
// defaults (Paper=1, AppBar=4, Menu=8, Dialog=24, …) land on a DS shadow
// rather than a shade MUI invented.
function buildShadows(): Shadows {
  const bands: [count: number, shadow: string][] = [
    [1, 'none'],
    [4, tokens.shadow.xs],
    [4, tokens.shadow.card],
    [4, tokens.shadow.hover],
    [6, tokens.shadow.popover],
    [6, tokens.shadow.modal],
  ];
  const shadows = bands.flatMap(([count, shadow]) => Array<string>(count).fill(shadow));
  return shadows as Shadows;
}

function buildTypography(): ThemeOptions['typography'] {
  return {
    fontFamily: tokens.font.sans,
    fontWeightRegular: tokens.fontWeight.regular,
    fontWeightMedium: tokens.fontWeight.medium,
    fontWeightBold: tokens.fontWeight.bold,
    h1: {
      fontFamily: tokens.font.display,
      fontSize: tokens.fontSize['5xl'],
      lineHeight: tokens.lineHeight['5xl'],
      fontWeight: tokens.fontWeight.bold,
    },
    h2: {
      fontFamily: tokens.font.display,
      fontSize: tokens.fontSize['4xl'],
      lineHeight: tokens.lineHeight['4xl'],
      fontWeight: tokens.fontWeight.bold,
    },
    h3: {
      fontFamily: tokens.font.display,
      fontSize: tokens.fontSize['3xl'],
      lineHeight: tokens.lineHeight['3xl'],
      fontWeight: tokens.fontWeight.semibold,
    },
    h4: {
      fontFamily: tokens.font.display,
      fontSize: tokens.fontSize['2xl'],
      lineHeight: tokens.lineHeight['2xl'],
      fontWeight: tokens.fontWeight.semibold,
    },
    h5: {
      fontSize: tokens.fontSize.xl,
      lineHeight: tokens.lineHeight.xl,
      fontWeight: tokens.fontWeight.semibold,
    },
    h6: {
      fontSize: tokens.fontSize.lg,
      lineHeight: tokens.lineHeight.lg,
      fontWeight: tokens.fontWeight.semibold,
    },
    subtitle1: {
      fontSize: tokens.fontSize.md,
      lineHeight: tokens.lineHeight.md,
      fontWeight: tokens.fontWeight.medium,
    },
    subtitle2: {
      fontSize: tokens.fontSize.base,
      lineHeight: tokens.lineHeight.base,
      fontWeight: tokens.fontWeight.medium,
    },
    body1: {
      fontSize: tokens.fontSize.base,
      lineHeight: tokens.lineHeight.base,
      fontWeight: tokens.fontWeight.regular,
    },
    body2: {
      fontSize: tokens.fontSize.sm,
      lineHeight: tokens.lineHeight.sm,
      fontWeight: tokens.fontWeight.regular,
    },
    // Reference CSS renders buttons uppercase with tracked letterspacing
    // (.btn, theme.css) — kept here rather than per-component.
    button: {
      fontSize: tokens.fontSize.sm,
      fontWeight: tokens.fontWeight.semibold,
      textTransform: 'uppercase',
      letterSpacing: '0.02em',
    },
    caption: {
      fontSize: tokens.fontSize.xs,
      lineHeight: tokens.lineHeight.xs,
      fontWeight: tokens.fontWeight.regular,
    },
    overline: {
      fontSize: tokens.fontSize.xs,
      lineHeight: tokens.lineHeight.xs,
      fontWeight: tokens.fontWeight.semibold,
      textTransform: 'uppercase',
    },
  };
}

export function createSmTheme(mode: SmThemeMode): Theme {
  const palette = createPalette(mode);
  // Non-null: createPalette always sets focusRing. The declaration-merged
  // PaletteOptions marks it optional because MUI requires every extension
  // field to be optional on the options type.
  const focusRing = palette.focusRing!;

  return createTheme({
    palette,
    typography: buildTypography(),
    // 4px spacing unit; component-level rhythm stays on the 8px grid
    // (design §8.1) by using even multiples of theme.spacing().
    spacing: 4,
    shape: {
      borderRadius: parseInt(tokens.radius.lg, 10),
    },
    shadows: buildShadows(),
    // From the token layer, not MUI's defaults, so the CSS token file and
    // the theme cannot disagree about where a layout changes (G16, task 105).
    breakpoints: {
      values: { ...breakpointValues },
    },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          // §B.2: focus-visible ring is brand green, not the primary marine
          // — but green alone is 2.17:1 on white, short of SC 1.4.11's 3:1.
          // So the ring is two concentric bands (FE-D39, task FE-097):
          // green 0–2px out, navy 2–4px out. On light surfaces the navy
          // band carries the indicator (15.24:1 against the page); on dark
          // the green does (7.48:1); the two bands separate from each other
          // at 7.48:1 in both. Green stays the band nearest the control, so
          // the ring still reads as brand green.
          //
          // Both bands are `box-shadow` rather than `outline` + shadow: an
          // outline at `outlineOffset: 2px` would paint *over* the middle
          // of the shadow instead of nesting inside it. The transparent
          // `outline` is the Windows-high-contrast fallback — forced-colors
          // mode drops box-shadows and repaints a transparent outline in
          // the user's own system colour, so the ring survives there.
          '*:focus-visible': {
            outline: '2px solid transparent',
            outlineOffset: '2px',
            boxShadow: `0 0 0 2px ${focusRing.inner}, 0 0 0 4px ${focusRing.outer}`,
            borderRadius: tokens.radius.xs,
          },
          // The static skip link in index.html (task 001, design §18) — off
          // -screen until focused, same treatment `AppShell`'s own in-app
          // skip link (task 036) uses. A global rule, not a component, since
          // the link itself is raw markup outside the React tree.
          '.skip-link': {
            position: 'fixed',
            top: '-48px',
            left: '8px',
            zIndex: 2000,
            padding: '8px 16px',
            backgroundColor: tokens.semantic.bg1,
            color: tokens.semantic.fg1,
            borderRadius: tokens.radius.sm,
            boxShadow: tokens.console.e4,
            transition: `top ${tokens.console.durationShort} ${tokens.console.easing}`,
          },
          '.skip-link:focus': {
            top: '8px',
          },
          // An outlined `TextField`/`NumberField`/`Select`'s actual focused
          // element is `.MuiInputBase-input` (verified against
          // `@mui/material` source — present whichever of the three it is;
          // absent from `Checkbox`/`Radio`/`Switch`, which never reach this
          // rule). Its own border-box sits almost flush with the visible
          // outlined border, so the ring above — drawn just outside it —
          // would land on that border line, exactly where a floated
          // label's notch sits, and visually cross the label. Excluded
          // here; `MuiOutlinedInput` below gives it a notch-aware indicator
          // instead, one that was never going to collide with the label in
          // the first place.
          '.MuiInputBase-input:focus-visible': {
            outline: 'none',
            boxShadow: 'none',
          },
        },
      },
      // Designer/dialog form fields are plain MUI `TextField`/`Select`
      // (outlined) with no per-field styling — so this is the one place to
      // fix, for every field at once, MUI's own baked-in default: a resting
      // border that ISN'T this theme's own `divider` token, hovering to a
      // stark jump straight to `text.primary`. The reference CSS (`.tf
      // input`, theme.css) defines only a resting border and a `:focus`
      // state — no hover rule at all — so a subtler step is the design
      // intent, not a removal of feedback. Error (`.Mui-error`, `error.main`)
      // and disabled (`.Mui-disabled`, `action.disabled`) are separate
      // selectors MUI itself already declares after its own hover rule —
      // untouched here.
      //
      // Focus IS touched, replacing MUI's own default (`.Mui-focused`,
      // `primary.main`): measured against this theme's own surfaces (not
      // assumed), `primary.main` is 7.00:1 in light but only 2.18:1 in dark
      // — short of SC 1.4.11's 3:1. The global ring above no longer reaches
      // these fields at all (`.MuiInputBase-input:focus-visible` is
      // excluded from it, since a ring sized to the input's own box would
      // sit on the same border line a floated label's notch does), so this
      // is now the field's ONLY focus indicator, and it has to carry the
      // load alone, in both themes. `palette.fieldFocusBorder` (see its own
      // doc comment in `palette.ts`) is what colours it — matched to the
      // Solace Broker Manager's own field-focus colour, contrast-verified
      // in both modes, rather than reusing the global ring's colours (a
      // different visual identity, not this field's). MUI's own
      // `.Mui-focused` rule still supplies the `borderWidth: 2` half of the
      // change; only the colour is overridden.
      MuiOutlinedInput: {
        styleOverrides: {
          notchedOutline: {
            borderColor: palette.divider,
          },
          root: {
            '&:hover .MuiOutlinedInput-notchedOutline': {
              borderColor: palette.text!.secondary,
            },
            '&.Mui-focused .MuiOutlinedInput-notchedOutline': {
              borderColor: palette.fieldFocusBorder,
            },
          },
        },
      },
    },
  });
}
