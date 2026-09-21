/**
 * Reflow harness (task 105, G16 / FE-D29). `assertReflow(el, width)` fails
 * when the subtree would force two-dimensional scrolling at that CSS width,
 * or when a laid-out focusable is clipped out of the width.
 *
 * jsdom does not run a real layout engine, so besides `scrollWidth` this
 * also walks computed `width` / `min-width` on descendants. Elements that
 * scroll on one axis inside themselves (`overflow-x: auto|scroll|hidden`)
 * are the criterion's own exceptions (data tables, persistent toolbars)
 * and are not treated as page-body overflow.
 */
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ');

function parsePx(value: string): number | undefined {
  if (!value.endsWith('px')) {
    return undefined;
  }
  const parsed = Number.parseFloat(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function isInternalScroller(style: CSSStyleDeclaration): boolean {
  return style.overflowX === 'auto' || style.overflowX === 'scroll' || style.overflowX === 'hidden';
}

function descendantDeclaresOverflow(root: HTMLElement, widthPx: number): boolean {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
  let node: Node | null = walker.currentNode;
  while (node !== null) {
    if (node instanceof HTMLElement) {
      const style = getComputedStyle(node);
      if (!isInternalScroller(style)) {
        const minWidth = parsePx(style.minWidth);
        const width = parsePx(style.width);
        if (minWidth !== undefined && minWidth > widthPx) {
          return true;
        }
        if (width !== undefined && width > widthPx) {
          return true;
        }
      }
    }
    node = walker.nextNode();
  }
  return false;
}

function clippedFocusable(root: HTMLElement, widthPx: number): HTMLElement | undefined {
  const nodes = root.querySelectorAll(FOCUSABLE_SELECTOR);
  for (const node of nodes) {
    if (!(node instanceof HTMLElement)) {
      continue;
    }
    const rect = node.getBoundingClientRect();
    if (rect.width === 0 && rect.height === 0) {
      continue;
    }
    if (rect.right > widthPx + 1 || rect.left < -1) {
      return node;
    }
  }
  return undefined;
}

export function assertReflow(el: HTMLElement, width: number): void {
  const previousWidth = el.style.width;
  const previousMaxWidth = el.style.maxWidth;
  el.style.width = `${String(width)}px`;
  el.style.maxWidth = `${String(width)}px`;

  try {
    if (el.scrollWidth > el.clientWidth + 1) {
      throw new Error(
        `assertReflow(${String(width)}): horizontal scroll on the element (scrollWidth ${String(el.scrollWidth)} > clientWidth ${String(el.clientWidth)})`,
      );
    }
    if (descendantDeclaresOverflow(el, width)) {
      throw new Error(
        `assertReflow(${String(width)}): a descendant declares width/min-width larger than ${String(width)}px`,
      );
    }
    const clipped = clippedFocusable(el, width);
    if (clipped !== undefined) {
      throw new Error(
        `assertReflow(${String(width)}): focusable element clipped out of the ${String(width)}px viewport`,
      );
    }
  } finally {
    el.style.width = previousWidth;
    el.style.maxWidth = previousMaxWidth;
  }
}
