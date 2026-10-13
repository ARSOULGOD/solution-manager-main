/**
 * Single breakpoint source (FE-D29, G16, task 105). Consumed by the MUI
 * theme (`createSmTheme`) and mirrored as CSS custom properties in
 * tokens.css. Feature code must not invent a second set of widths.
 *
 * MUI's `xs` is the 0-floor by convention. The supported reflow floor is
 * `reflowFloorPx` (320), which is what `assertReflow` asserts — it is not
 * an MUI breakpoint key.
 */
export const breakpointValues = {
  xs: 0,
  sm: 600,
  md: 905,
  lg: 1240,
  xl: 1560,
} as const;

export type BreakpointKey = keyof typeof breakpointValues;

export const breakpointKeys = [
  'xs',
  'sm',
  'md',
  'lg',
  'xl',
] as const satisfies ReadonlyArray<BreakpointKey>;

/** WCAG 2.2 AA SC 1.4.10 reflow width. Not an MUI `breakpoints.values` key. */
export const reflowFloorPx = 320;

export const cssBreakpointVars = {
  floor: '--bp-floor',
  xs: '--bp-xs',
  sm: '--bp-sm',
  md: '--bp-md',
  lg: '--bp-lg',
  xl: '--bp-xl',
} as const;

export function containerQuery(minWidthPx: number): string {
  return `@container (min-width: ${String(minWidthPx)}px)`;
}

export function columnsAtWidth(
  widthPx: number,
  columns: Partial<Record<BreakpointKey, number>>,
): number {
  let resolved = columns.xs ?? 1;
  for (const key of breakpointKeys) {
    const value = columns[key];
    if (widthPx >= breakpointValues[key] && value !== undefined) {
      resolved = value;
    }
  }
  return resolved;
}
