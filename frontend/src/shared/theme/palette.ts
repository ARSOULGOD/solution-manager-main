/**
 * Palette factory — one function parameterised by mode (design §8.1, FE-D5).
 * All values trace to tokens.ts; nothing here invents a colour. Where a
 * value has no direct token counterpart the choice is a documented mapping,
 * called out inline, not a fresh hex literal.
 */
import type { Palette, PaletteOptions } from '@mui/material/styles';

import { tokens } from './tokens';

export type SmThemeMode = 'light' | 'dark';

declare module '@mui/material/styles' {
  interface Palette {
    /** Instance/replica health — status vocabulary, design §8.1. */
    health: {
      ok: string;
      down: string;
      restartExpected: string;
      inactiveReplica: string;
    };
    /** A change is accepted but not yet confirmed applied. */
    pending: string;
    /** State cannot be confirmed from available telemetry. */
    unverified: string;
    /** Categorical series colours for ECharts (echartsTheme.ts). */
    chart: {
      categorical: string[];
      /**
       * How many leading entries of `categorical` are guaranteed
       * distinguishable under simulated dichromacy (`FE-D39`). Beyond this
       * count colour alone stops separating series and the chart must lean
       * on its other channels. Asserted, not aspirational — see
       * `palette.contrast.test.ts`.
       */
      cvdSafeCount: number;
    };
    /**
     * `Chip`/`StateChip` tones (task 012's kit, task 013's vocabulary
     * module) — `main` for text/icon/border, `bg` for the fill. `ok` /
     * `warn` / `err` / `info` mirror the standard semantic palette above;
     * `idle`, `neutral` and `purple` have no MUI-standard slot to reuse.
     */
    tone: Record<'ok' | 'warn' | 'err' | 'info' | 'idle' | 'neutral' | 'purple', ToneColor>;
    /**
     * Always-dark drawer rail (UI §A.2) — navy regardless of colour mode,
     * so the shell marker is the same surface in light and dark.
     */
    nav: {
      bg: string;
      text: string;
      muted: string;
      accent: string;
    };
    /**
     * The two bands of the focus ring (`FE-D39`). A single brand-green ring
     * reaches only 2.17:1 on white — SC 1.4.11 wants 3:1 — so the indicator
     * is two concentric bands: whichever one the surface swallows, the
     * other one carries it.
     */
    focusRing: {
      inner: string;
      outer: string;
    };
  }

  interface PaletteOptions {
    health?: Palette['health'];
    pending?: string;
    unverified?: string;
    chart?: Palette['chart'];
    tone?: Palette['tone'];
    nav?: Palette['nav'];
    focusRing?: Palette['focusRing'];
  }
}

interface ToneColor {
  main: string;
  bg: string;
}

/**
 * Chip / alert / badge tones, **per mode** (`FE-D39`).
 *
 * `main` is read as *text* by `Chip`, `Alert`, `StatStrip`, `LogViewer` and
 * `SectionNav`, and as a *mark* by the chip dot, the review meter and the
 * threshold line — so it has to clear **4.5:1 against its own `bg` and
 * against the page surfaces of its mode**, the stricter of the two duties.
 *
 * The previous single-mode table failed that in six of seven tones: `warn`
 * label text sat at 2.12:1 on its own fill. It also shipped the *light*
 * tints into dark mode, so a dark-theme chip was a near-white pill on a
 * navy page — legible by accident, and only because it was ignoring the
 * theme it was in.
 *
 * Dark `bg` values hold the tone's hue at low lightness rather than
 * blending it into the card colour: an 18% blend of `warning` into
 * `#0a2c47` gives `#363E42`, a mud that reads as neither orange nor navy.
 */
const toneByMode: Record<SmThemeMode, Palette['tone']> = {
  light: {
    // main: source token darkened until ≥4.5:1 on both its own bg and the page
    ok: { main: tokens.success.w100, bg: tokens.success.w10 }, //    #006B53  5.80:1
    warn: { main: '#B75418', bg: tokens.warning.w10 }, // warning.w100 darkened  4.51:1
    err: { main: tokens.error.w100, bg: tokens.error.w10 }, //       #C33135  4.86:1
    info: { main: tokens.info.w100, bg: tokens.info.w10 }, //        #2B71B1  4.56:1
    idle: { main: '#5E6A75', bg: tokens.secondary.w20 }, // accent.slate darkened  4.55:1
    neutral: { main: '#667081', bg: tokens.secondary.w10 }, // secondary.wMain drk  4.54:1
    purple: { main: tokens.accent.purple, bg: tokens.accent.purple10 }, // #7841A8 5.80:1
  },
  dark: {
    // bg: source hue at s≤0.42 / l=0.155 · main: source lightened to ≥4.5:1
    ok: { main: '#00AE90', bg: '#173833' }, //      success  4.52:1 on bg · 5.09:1 on page
    warn: { main: tokens.warning.wMain, bg: '#382617' }, // #FF8E2B 6.28:1 · 6.25:1
    err: { main: '#EC6567', bg: '#381717' }, //     error    5.08:1 · 4.52:1
    info: { main: '#0599DE', bg: '#172D38' }, //    info     4.51:1 · 4.52:1
    idle: { main: tokens.accent.slate, bg: '#23282C' }, // #86939E 4.73:1 · 4.56:1
    neutral: { main: '#8891A0', bg: '#23262C' }, // secondary 4.77:1 · 4.51:1
    purple: { main: '#A97FCE', bg: '#291738' }, //  accent.purple  5.19:1 · 4.52:1
  },
};

/* ─────────────────────────────────────────────────────────────────────────
 * Contrast-derived values (`FE-D39`, task FE-097)
 *
 * Every hex below is a DS accent or semantic token with its **lightness
 * moved, hue and saturation held**, until it clears a stated contrast bar
 * against a stated surface. The source token and the resulting ratio are
 * named on each line, so the derivation is reviewable in the diff.
 *
 * ⚠️ These are baked literals and must stay baked. Deriving them at theme
 * -build time by clamping against the live surface would make FE-097's own
 * gate **vacuous** — the check would be asserting a property the code had
 * just finished forcing to be true. Baked values can regress; that is the
 * point of having a gate.
 *
 * ⚠️ This does **not** pick brand paint. Open item 19 and `FE-D5` stay
 * open: no new hue is introduced, and every value traces to a token in
 * `tokens.ts`. What changed is legibility, which is not the architect
 * group's question to answer.
 * ──────────────────────────────────────────────────────────────────────── */

/**
 * Ten hues from the DS accent set, excluding the "10" light-tint variants
 * (those are fill/background tints, not line-series colours).
 *
 * **Per mode, because one list cannot serve both.** The light surfaces are
 * near-white and the dark ones near-navy; a single fixed list left
 * `accent.sky` at 1.58:1 on light and `accent.indigo` at 1.87:1 on dark.
 * Each entry is clamped to ≥3:1 against *both* surfaces of its own mode
 * (SC 1.4.11 — a series line is non-text content).
 *
 * **Order is load-bearing.** It is a maximin ordering over simulated
 * dichromacy: the first `CVD_SAFE_COUNT` entries stay mutually separable
 * for protan, deutan and tritan viewers in both themes. Reordering it
 * silently degrades a 6-series chart. See `palette.contrast.test.ts`.
 */
const chartCategorical: Record<SmThemeMode, string[]> = {
  light: [
    tokens.accent.blue, //                     #3C69E1 as-is        4.59:1
    '#CC7C03', // accent.orange  #FCA829 darkened            3.06:1
    '#83909B', // accent.slate   #86939E darkened            3.07:1
    '#0E98D0', // accent.sky     #7CD3F6 darkened            3.08:1
    tokens.accent.red, //                      #DA162D as-is        4.78:1
    tokens.accent.magenta, //                  #CB1AA5 as-is        4.67:1
    tokens.accent.indigo, //                   #3A4880 as-is        8.17:1
    '#F55B45', // accent.coral   #F66651 darkened            3.05:1
    tokens.accent.teal, //                     #009193 as-is        3.61:1
    tokens.accent.purple, //                   #7841A8 as-is        6.33:1
  ],
  dark: [
    '#416DE2', // accent.blue    #3C69E1 lightened           3.08:1
    tokens.accent.orange, //                   #FCA829 as-is        7.36:1
    tokens.accent.slate, //                    #86939E as-is        4.56:1
    tokens.accent.sky, //                      #7CD3F6 as-is        8.54:1
    '#E5172F', // accent.red     #DA162D lightened           3.07:1
    '#D21BAB', // accent.magenta #CB1AA5 lightened           3.06:1
    '#5E70B6', // accent.indigo  #3A4880 lightened           3.05:1
    tokens.accent.coral, //                    #F66651 as-is        4.74:1
    tokens.accent.teal, //                     #009193 as-is        3.74:1
    '#925CC0', // accent.purple  #7841A8 lightened           3.08:1
  ],
};

/**
 * Ten hues drawn from a fourteen-token accent set cannot all be
 * colour-blind-safe — `indigo`/`purple` and `purple`/`magenta` sit at
 * ΔE2000 ≈ 2.3 under deuteranopia no matter how they are ordered. Rather
 * than pretend otherwise, the palette states where the guarantee stops.
 * Six is what the best available ordering achieves (min ΔE2000 8.78);
 * charts past six series need a non-colour channel, which is why the
 * legend and the data table are not optional extras.
 */
const CVD_SAFE_COUNT = 6;

export function createPalette(mode: SmThemeMode): PaletteOptions {
  const isDark = mode === 'dark';

  return {
    mode,
    primary: {
      main: tokens.primary.wMain,
      dark: tokens.primary.w90,
      light: tokens.primary.w60,
    },
    secondary: {
      main: tokens.secondary.wMain,
      dark: tokens.secondary.w80,
      light: tokens.secondary.w40,
    },
    error: {
      main: tokens.error.wMain,
      dark: tokens.error.w100,
      light: tokens.error.w30,
    },
    warning: {
      main: tokens.warning.wMain,
      dark: tokens.warning.w100,
      light: tokens.warning.w30,
    },
    info: {
      main: tokens.info.wMain,
      dark: tokens.info.w100,
      light: tokens.info.w30,
    },
    success: {
      main: tokens.success.wMain,
      dark: tokens.success.w100,
      light: tokens.success.w30,
    },
    // Status vocabulary — mapped, not invented: ok/down/restartExpected read
    // off the existing semantic colours; inactiveReplica uses the neutral
    // secondary tone since "inactive" is not a health failure.
    health: {
      ok: tokens.success.wMain,
      down: tokens.error.wMain,
      restartExpected: tokens.warning.wMain,
      inactiveReplica: tokens.secondary.wMain,
    },
    pending: tokens.info.wMain,
    unverified: tokens.accent.slate,
    chart: {
      categorical: chartCategorical[mode],
      cvdSafeCount: CVD_SAFE_COUNT,
    },
    // `idle`/`neutral` reuse the muted grey-blue family (no hue-specific
    // token fits "non-alarming, non-positive"); `purple` is the DS accent
    // used for the PROD env tone (§A.13.1) and reused here for any other
    // "attention, not alarm" chip.
    tone: toneByMode[mode],
    nav: {
      bg: tokens.background.w100,
      text: tokens.darkTheme.surfaceTextStrong,
      muted: tokens.darkTheme.surfaceTextMuted,
      accent: tokens.brand.wMain,
    },
    // Brand green keeps the ring's identity (§B.2) but reaches only 2.17:1
    // on white; navy backs it at 15.24:1 there, and green carries the dark
    // theme at 7.48:1. The two bands contrast 7.48:1 against each other, so
    // the indicator is visible whichever surface swallows one of them.
    focusRing: {
      inner: tokens.brand.wMain,
      outer: tokens.background.wMain,
    },
    background: {
      default: isDark ? tokens.background.wMain : tokens.background.w20,
      // INVENTED (tokens.ts darkTheme.surfaceCard) — dark paper has no
      // direct DS token; interpreted from theme.css component selectors.
      paper: isDark ? tokens.darkTheme.surfaceCard : tokens.background.w10,
    },
    text: {
      primary: isDark ? tokens.darkTheme.surfaceTextStrong : tokens.primary.text.wMain,
      secondary: isDark ? tokens.darkTheme.surfaceText : tokens.secondary.text.wMain,
      disabled: isDark ? tokens.darkTheme.surfaceTextMuted : tokens.secondary.text.w50,
    },
    divider: isDark ? tokens.darkTheme.line : tokens.semantic.borderDefault,
    action: {
      hover: tokens.stateLayer.w10,
      selected: tokens.stateLayer.w20,
    },
  };
}

export type { Palette };
