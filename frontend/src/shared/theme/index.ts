/**
 * Design token layer — single point of import for tokens across the app.
 * Do NOT read tokens.css directly. Import from this module instead.
 */

export { tokens, type DesignTokens } from './tokens';
export { default } from './tokens';
export { createPalette, type SmThemeMode } from './palette';
export { createSmTheme } from './createSmTheme';
export { echartsTheme, type EChartsTheme } from './echartsTheme';
export {
  breakpointValues,
  breakpointKeys,
  reflowFloorPx,
  cssBreakpointVars,
  containerQuery,
  columnsAtWidth,
  type BreakpointKey,
} from './breakpoints';

// Note: tokens.css is imported by the root layout and must be included
// in the bundle. Import it here for convenience if needed:
// import './tokens.css';
