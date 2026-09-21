/**
 * jsdom has no `window.matchMedia` at all. MUI's `useMediaQuery` (task 106,
 * `FE-D29`) handles that gracefully — it falls back to `defaultMatches`
 * (`false`) rather than throwing — but `false` for every query would make
 * every shell test see a permanently closed overlay drawer and a
 * collapsed app bar, regardless of what the test actually wants to
 * exercise. `setup.ts` installs a polyfill built on the mutable width this
 * module owns, defaulting to a desktop width so tests written before task
 * 106 keep seeing the chrome they already assert against; task 106's own
 * tests call `setViewportWidth` to exercise the narrower branches.
 *
 * Only `min-width`/`max-width` px queries and `prefers-reduced-motion` are
 * parsed — the width shapes are the only ones `theme.breakpoints.up`/`.down`
 * ever produce (verified against `@mui/system/createBreakpoints` source);
 * `prefers-reduced-motion` is task 072's (`ChartRenderer`, via MUI's
 * `useMediaQuery`). Anything else falls through to `true` — unrecognized
 * here, matching a real browser's `all`/empty-query default rather than
 * failing closed.
 */
const DESKTOP_WIDTH_PX = 1280;

let viewportWidth = DESKTOP_WIDTH_PX;
let reducedMotion = false;

export function setViewportWidth(widthPx: number): void {
  viewportWidth = widthPx;
}

export function resetViewportWidth(): void {
  viewportWidth = DESKTOP_WIDTH_PX;
}

export function setReducedMotion(prefersReduced: boolean): void {
  reducedMotion = prefersReduced;
}

export function resetReducedMotion(): void {
  reducedMotion = false;
}

function evaluateQuery(query: string): boolean {
  if (/prefers-reduced-motion/.test(query)) {
    return reducedMotion;
  }
  const minWidth = /min-width:\s*([\d.]+)px/.exec(query);
  const maxWidth = /max-width:\s*([\d.]+)px/.exec(query);
  if (minWidth?.[1] !== undefined && viewportWidth < Number(minWidth[1])) {
    return false;
  }
  if (maxWidth?.[1] !== undefined && viewportWidth > Number(maxWidth[1])) {
    return false;
  }
  return true;
}

export function installMatchMediaPolyfill(): void {
  if (typeof window.matchMedia === 'function') {
    return;
  }

  window.matchMedia = (query: string): MediaQueryList => {
    const target = new EventTarget();
    const list: MediaQueryList = {
      get matches() {
        return evaluateQuery(query);
      },
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: target.addEventListener.bind(target),
      removeEventListener: target.removeEventListener.bind(target),
      dispatchEvent: target.dispatchEvent.bind(target),
    };
    return list;
  };
}
