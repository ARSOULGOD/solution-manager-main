/**
 * Design tokens for Solution Manager — typed re-export of tokens.css
 * Source: Solace Agent Mesh Design System + console layer + dark theme
 * Do NOT read tokens.css directly in components. Import from this module instead.
 */

export interface DesignTokens {
  // Brand colors
  brand: {
    wMain: string;
    w100: string;
    w60: string;
    w30: string;
    w10: string;
  };

  // Primary colors (deep navy blue)
  primary: {
    w100: string;
    w90: string;
    wMain: string;
    w60: string;
    w40: string;
    w20: string;
    w10: string;
    text: {
      wMain: string;
      w100: string;
      w10: string;
    };
  };

  // Secondary colors (neutrals)
  secondary: {
    w100: string;
    w80: string;
    w70: string;
    wMain: string;
    w40: string;
    w20: string;
    w10: string;
    text: {
      wMain: string;
      w50: string;
    };
  };

  // Background colors
  background: {
    w100: string; // darkest navy
    wMain: string; // dark surface
    w20: string; // light panel
    w10: string; // light default
  };

  // Semantic colors
  info: {
    wMain: string;
    w100: string;
    w30: string;
    w10: string;
  };

  error: {
    wMain: string;
    w100: string;
    w30: string;
    w10: string;
  };

  warning: {
    wMain: string;
    w100: string;
    w30: string;
    w10: string;
  };

  success: {
    wMain: string;
    w100: string;
    w30: string;
    w10: string;
  };

  // Accent colors (for charts, tags, chips)
  accent: {
    blue: string;
    blue10: string;
    indigo: string;
    indigo10: string;
    teal: string;
    teal10: string;
    purple: string;
    purple10: string;
    magenta: string;
    coral: string;
    orange: string;
    sky: string;
    slate: string;
    red: string;
  };

  // State layers (overlays)
  stateLayer: {
    w10: string;
    w20: string;
  };

  // Dark surface (e.g. nav sidebar on light theme)
  darkSurface: {
    bg: string;
    bgHover: string;
    text: string;
    textMuted: string;
    brandMain: string;
    border: string;
  };

  // Typography — font families
  font: {
    sans: string;
    display: string;
    mono: string;
  };

  // Typography — font sizes
  fontSize: {
    xs: string;
    sm: string;
    base: string;
    md: string;
    lg: string;
    xl: string;
    '2xl': string;
    '3xl': string;
    '4xl': string;
    '5xl': string;
  };

  // Typography — line heights
  lineHeight: {
    xs: string;
    sm: string;
    base: string;
    md: string;
    lg: string;
    xl: string;
    '2xl': string;
    '3xl': string;
    '4xl': string;
    '5xl': string;
  };

  // Typography — font weights
  fontWeight: {
    regular: number;
    medium: number;
    semibold: number;
    bold: number;
  };

  // Spacing (4px base)
  space: {
    1: string;
    2: string;
    3: string;
    4: string;
    5: string;
    6: string;
    8: string;
    10: string;
    12: string;
    16: string;
    20: string;
  };

  // Border radii
  radius: {
    xs: string;
    sm: string;
    md: string;
    lg: string;
    xl: string;
    full: string;
    default: string;
  };

  // Shadows
  shadow: {
    xs: string;
    card: string;
    hover: string;
    popover: string;
    modal: string;
  };

  // Motion — easing functions
  ease: {
    standard: string;
    enter: string;
    exit: string;
  };

  // Motion — durations
  duration: {
    fast: string;
    base: string;
    slow: string;
  };

  // Console layer — MUI shortcuts & aliases
  console: {
    primary: string;
    primaryDark: string;        // --primary-d
    primaryDarkest: string;     // --primary-dd
    primary050: string;
    primary100: string;
    primary160: string;
    accent: string;
    navy: string;
    line: string;
    lineSoft: string;
    e1: string;
    e2: string;
    e4: string;
    e8: string;
    appbarHeight: string;
    drawerWidth: string;
    drawerWidthRail: string;
    duration: string;           // --dur
    durationShort: string;      // --dur-s
    easing: string;
  };

  // Dark theme overrides
  darkTheme: {
    line: string;
    lineSoft: string;
    surfaceBg: string;
    surfaceCard: string;
    surfaceInput: string;
    surfaceText: string;
    surfaceTextStrong: string;
    surfaceTextMuted: string;
    surfaceBorder: string;
    surfaceGridBorder: string;
  };

  // Primitive semantic mappings (shorthand)
  semantic: {
    fg1: string;      // foreground 1
    fg2: string;      // foreground 2
    fgMuted: string;  // foreground muted
    fgBrand: string;  // foreground brand
    bg1: string;      // background 1
    bg2: string;      // background 2
    bgDark: string;   // background dark
    borderDefault: string;
    borderSubtle: string;
  };
}

/**
 * Retrieve the value of a CSS custom property from the computed style.
 * Falls back to undefined if the property is not set.
 */
function getTokenValue(propertyName: string): string | undefined {
  if (typeof window === 'undefined') {
    // Server-side rendering fallback
    return undefined;
  }
  const value = getComputedStyle(document.documentElement).getPropertyValue(
    propertyName
  );
  return value ? value.trim() : undefined;
}

/**
 * Design tokens object — resolves CSS custom properties at runtime.
 * All values are pulled from the :root selector in tokens.css.
 */
export const tokens: DesignTokens = {
  // Brand
  brand: {
    wMain: getTokenValue('--brand-wMain') ?? '#00C895',
    w100: getTokenValue('--brand-w100') ?? '#00AD93',
    w60: getTokenValue('--brand-w60') ?? '#66DEBF',
    w30: getTokenValue('--brand-w30') ?? '#B3EFDF',
    w10: getTokenValue('--brand-w10') ?? '#E6FAF4',
  },

  // Primary
  primary: {
    w100: getTokenValue('--primary-w100') ?? '#01374E',
    w90: getTokenValue('--primary-w90') ?? '#014968',
    wMain: getTokenValue('--primary-wMain') ?? '#015B82',
    w60: getTokenValue('--primary-w60') ?? '#679DB4',
    w40: getTokenValue('--primary-w40') ?? '#99BDCD',
    w20: getTokenValue('--primary-w20') ?? '#CCDEE6',
    w10: getTokenValue('--primary-w10') ?? '#E6EFF2',
    text: {
      wMain: getTokenValue('--primary-text-wMain') ?? '#273749',
      w100: getTokenValue('--primary-text-w100') ?? '#000000',
      w10: getTokenValue('--primary-text-w10') ?? '#FFFFFF',
    },
  },

  // Secondary
  secondary: {
    w100: getTokenValue('--secondary-w100') ?? '#000000',
    w80: getTokenValue('--secondary-w80') ?? '#354E62',
    w70: getTokenValue('--secondary-w70') ?? '#536574',
    wMain: getTokenValue('--secondary-wMain') ?? '#8790A0',
    w40: getTokenValue('--secondary-w40') ?? '#CFD3D9',
    w20: getTokenValue('--secondary-w20') ?? '#E7E9EC',
    w10: getTokenValue('--secondary-w10') ?? '#F3F4F6',
    text: {
      wMain: getTokenValue('--secondary-text-wMain') ?? '#647481',
      w50: getTokenValue('--secondary-text-w50') ?? '#B1B9C0',
    },
  },

  // Background
  background: {
    w100: getTokenValue('--background-w100') ?? '#021B2F',
    wMain: getTokenValue('--background-wMain') ?? '#03223B',
    w20: getTokenValue('--background-w20') ?? '#F7F8F9',
    w10: getTokenValue('--background-w10') ?? '#FFFFFF',
  },

  // Semantic
  info: {
    wMain: getTokenValue('--info-wMain') ?? '#0591D3',
    w100: getTokenValue('--info-w100') ?? '#2B71B1',
    w30: getTokenValue('--info-w30') ?? '#B4DEF2',
    w10: getTokenValue('--info-w10') ?? '#E6F4FB',
  },

  error: {
    wMain: getTokenValue('--error-wMain') ?? '#E94C4E',
    w100: getTokenValue('--error-w100') ?? '#C33135',
    w30: getTokenValue('--error-w30') ?? '#F8C9CA',
    w10: getTokenValue('--error-w10') ?? '#FDEDED',
  },

  warning: {
    wMain: getTokenValue('--warning-wMain') ?? '#FF8E2B',
    w100: getTokenValue('--warning-w100') ?? '#E1681F',
    w30: getTokenValue('--warning-w30') ?? '#FFDDBF',
    w10: getTokenValue('--warning-w10') ?? '#FFF4EA',
  },

  success: {
    wMain: getTokenValue('--success-wMain') ?? '#009A80',
    w100: getTokenValue('--success-w100') ?? '#006B53',
    w30: getTokenValue('--success-w30') ?? '#B3E1D9',
    w10: getTokenValue('--success-w10') ?? '#E6F5F2',
  },

  // Accents
  accent: {
    blue: getTokenValue('--accent-blue') ?? '#3C69E1',
    blue10: getTokenValue('--accent-blue-10') ?? '#ECF0FC',
    indigo: getTokenValue('--accent-indigo') ?? '#3A4880',
    indigo10: getTokenValue('--accent-indigo-10') ?? '#EBEDF2',
    teal: getTokenValue('--accent-teal') ?? '#009193',
    teal10: getTokenValue('--accent-teal-10') ?? '#E6F4F4',
    purple: getTokenValue('--accent-purple') ?? '#7841A8',
    purple10: getTokenValue('--accent-purple-10') ?? '#F2ECF6',
    magenta: getTokenValue('--accent-magenta') ?? '#CB1AA5',
    coral: getTokenValue('--accent-coral') ?? '#F66651',
    orange: getTokenValue('--accent-orange') ?? '#FCA829',
    sky: getTokenValue('--accent-sky') ?? '#7CD3F6',
    slate: getTokenValue('--accent-slate') ?? '#86939E',
    red: getTokenValue('--accent-red') ?? '#DA162D',
  },

  // State layers
  stateLayer: {
    w10: getTokenValue('--stateLayer-w10') ?? '#03223B1a',
    w20: getTokenValue('--stateLayer-w20') ?? '#03223B33',
  },

  // Dark surface
  darkSurface: {
    bg: getTokenValue('--darkSurface-bg') ?? '#03223B',
    bgHover: getTokenValue('--darkSurface-bgHover') ?? '#021B2F',
    text: getTokenValue('--darkSurface-text') ?? '#F5F5F5',
    textMuted: getTokenValue('--darkSurface-textMuted') ?? '#ACBAC2',
    brandMain: getTokenValue('--darkSurface-brandMain') ?? '#00C895',
    border: getTokenValue('--darkSurface-border') ?? '#536574',
  },

  // Typography — fonts
  font: {
    sans: getTokenValue('--font-sans') ??
      '"Rubik", "Inter", system-ui, -apple-system, "Segoe UI", sans-serif',
    display: getTokenValue('--font-display') ??
      '"Rubik", "Inter", system-ui, sans-serif',
    mono: getTokenValue('--font-mono') ??
      '"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace',
  },

  // Typography — font sizes
  fontSize: {
    xs: getTokenValue('--fs-xs') ?? '12px',
    sm: getTokenValue('--fs-sm') ?? '13px',
    base: getTokenValue('--fs-base') ?? '14px',
    md: getTokenValue('--fs-md') ?? '16px',
    lg: getTokenValue('--fs-lg') ?? '18px',
    xl: getTokenValue('--fs-xl') ?? '20px',
    '2xl': getTokenValue('--fs-2xl') ?? '24px',
    '3xl': getTokenValue('--fs-3xl') ?? '30px',
    '4xl': getTokenValue('--fs-4xl') ?? '36px',
    '5xl': getTokenValue('--fs-5xl') ?? '48px',
  },

  // Typography — line heights
  lineHeight: {
    xs: getTokenValue('--lh-xs') ?? '16px',
    sm: getTokenValue('--lh-sm') ?? '18px',
    base: getTokenValue('--lh-base') ?? '20px',
    md: getTokenValue('--lh-md') ?? '24px',
    lg: getTokenValue('--lh-lg') ?? '26px',
    xl: getTokenValue('--lh-xl') ?? '28px',
    '2xl': getTokenValue('--lh-2xl') ?? '32px',
    '3xl': getTokenValue('--lh-3xl') ?? '38px',
    '4xl': getTokenValue('--lh-4xl') ?? '44px',
    '5xl': getTokenValue('--lh-5xl') ?? '56px',
  },

  // Typography — font weights
  fontWeight: {
    regular: parseInt(getTokenValue('--fw-regular') ?? '400'),
    medium: parseInt(getTokenValue('--fw-medium') ?? '500'),
    semibold: parseInt(getTokenValue('--fw-semibold') ?? '600'),
    bold: parseInt(getTokenValue('--fw-bold') ?? '700'),
  },

  // Spacing
  space: {
    1: getTokenValue('--space-1') ?? '4px',
    2: getTokenValue('--space-2') ?? '8px',
    3: getTokenValue('--space-3') ?? '12px',
    4: getTokenValue('--space-4') ?? '16px',
    5: getTokenValue('--space-5') ?? '20px',
    6: getTokenValue('--space-6') ?? '24px',
    8: getTokenValue('--space-8') ?? '32px',
    10: getTokenValue('--space-10') ?? '40px',
    12: getTokenValue('--space-12') ?? '48px',
    16: getTokenValue('--space-16') ?? '64px',
    20: getTokenValue('--space-20') ?? '80px',
  },

  // Radii
  radius: {
    xs: getTokenValue('--radius-xs') ?? '2px',
    sm: getTokenValue('--radius-sm') ?? '4px',
    md: getTokenValue('--radius-md') ?? '6px',
    lg: getTokenValue('--radius-lg') ?? '8px',
    xl: getTokenValue('--radius-xl') ?? '12px',
    full: getTokenValue('--radius-full') ?? '9999px',
    default: getTokenValue('--radius') ?? '0.5rem',
  },

  // Shadows
  shadow: {
    xs: getTokenValue('--shadow-xs') ?? '0px 1px 2px 0px rgba(3, 34, 59, 0.06)',
    card: getTokenValue('--shadow-card') ??
      '0px 1px 4px 0px rgba(3, 34, 59, 0.10)',
    hover: getTokenValue('--shadow-hover') ??
      '0px 4px 12px 0px rgba(3, 34, 59, 0.10)',
    popover: getTokenValue('--shadow-popover') ??
      '0px 8px 24px 0px rgba(3, 34, 59, 0.14)',
    modal: getTokenValue('--shadow-modal') ??
      '0px 16px 40px 0px rgba(3, 34, 59, 0.18)',
  },

  // Motion — easing
  ease: {
    standard: getTokenValue('--ease-standard') ?? 'cubic-bezier(0.4, 0, 0.2, 1)',
    enter: getTokenValue('--ease-enter') ?? 'cubic-bezier(0, 0, 0.2, 1)',
    exit: getTokenValue('--ease-exit') ?? 'cubic-bezier(0.4, 0, 1, 1)',
  },

  // Motion — durations
  duration: {
    fast: getTokenValue('--dur-fast') ?? '120ms',
    base: getTokenValue('--dur-base') ?? '200ms',
    slow: getTokenValue('--dur-slow') ?? '320ms',
  },

  // Console layer
  console: {
    primary: getTokenValue('--primary') ?? '#015B82',
    primaryDark: getTokenValue('--primary-d') ?? '#014968',
    primaryDarkest: getTokenValue('--primary-dd') ?? '#01374E',
    primary050: getTokenValue('--primary-050') ?? 'rgba(1, 91, 130, 0.055)',
    primary100: getTokenValue('--primary-100') ?? 'rgba(1, 91, 130, 0.12)',
    primary160: getTokenValue('--primary-160') ?? 'rgba(1, 91, 130, 0.16)',
    accent: getTokenValue('--accent') ?? '#00C895',
    navy: getTokenValue('--navy') ?? '#03223B',
    line: getTokenValue('--line') ?? '#CFD3D9',
    lineSoft: getTokenValue('--line-soft') ?? '#E7E9EC',
    e1: getTokenValue('--e1') ??
      '0 1px 3px rgba(3, 34, 59, 0.12), 0 1px 2px rgba(3, 34, 59, 0.07)',
    e2: getTokenValue('--e2') ??
      '0 2px 6px rgba(3, 34, 59, 0.10), 0 1px 3px rgba(3, 34, 59, 0.07)',
    e4: getTokenValue('--e4') ?? '0 6px 16px rgba(3, 34, 59, 0.12)',
    e8: getTokenValue('--e8') ?? '0 14px 34px rgba(3, 34, 59, 0.20)',
    appbarHeight: getTokenValue('--appbar-h') ?? '64px',
    drawerWidth: getTokenValue('--drawer-w') ?? '256px',
    drawerWidthRail: getTokenValue('--drawer-w-rail') ?? '76px',
    duration: getTokenValue('--dur') ?? '0.18s',
    durationShort: getTokenValue('--dur-s') ?? '0.12s',
    easing: getTokenValue('--ease') ?? 'cubic-bezier(0.4, 0, 0.2, 1)',
  },

  // Dark theme — hardcoded constants (not mode-scoped resolution)
  // Note: --line and --line-soft are scoped to [data-theme="dark"] in CSS,
  // but getTokenValue resolves only from :root scope at module load time.
  // These values must be hardcoded. Live mode-switching is FE-003's job.
  darkTheme: {
    line: '#2b4358', // From theme.css [data-theme="dark"]{--line:#2b4358}
    lineSoft: '#213546', // From theme.css [data-theme="dark"]{--line-soft:#213546}
    // INVENTED — surface colors are interpreted/composed from theme.css component selectors
    surfaceBg: '#03223B',
    surfaceCard: '#0a2c47',
    surfaceInput: '#0e344f',
    surfaceText: '#c9d4dd',
    surfaceTextStrong: '#eaf1f6',
    surfaceTextMuted: '#8ea9bd',
    surfaceBorder: '#2b4358',
    surfaceGridBorder: '#1c344a',
  },

  // Semantic mappings
  semantic: {
    fg1: getTokenValue('--fg1') ?? '#273749',
    fg2: getTokenValue('--fg2') ?? '#647481',
    fgMuted: getTokenValue('--fg-muted') ?? '#B1B9C0',
    fgBrand: getTokenValue('--fg-brand') ?? '#00C895',
    bg1: getTokenValue('--bg1') ?? '#FFFFFF',
    bg2: getTokenValue('--bg2') ?? '#F7F8F9',
    bgDark: getTokenValue('--bg-dark') ?? '#03223B',
    borderDefault: getTokenValue('--border-default') ?? '#CFD3D9',
    borderSubtle: getTokenValue('--border-subtle') ?? '#E7E9EC',
  },
};

export default tokens;
