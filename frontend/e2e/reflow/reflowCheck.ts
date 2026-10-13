/**
 * FE-110: turns `FE-D29` (console reflows to 320 CSS px, WCAG 2.2 AA
 * SC 1.4.10) from a claim into something a real browser checks. Mirrors
 * `src/test/reflow.ts`'s jsdom criterion (task 105) but adds the one
 * thing a synthetic jsdom layout can't prove and the task's own
 * Implementation line demands: **an undeclared horizontal scroller must
 * fail**, not merely "no CSS rule happened to force one." A container
 * earns its exception by carrying `data-reflow-exception`, set to one of
 * `REFLOW_EXCEPTIONS` — the two this codebase actually uses today
 * (`DataTable.tsx`'s Data Grid scroller, `DashboardToolbar.tsx`'s sticky
 * control row). Anything else that scrolls horizontally is a bug to fix,
 * not a third exception to add quietly.
 *
 * `collectViolations` runs *inside the page* via `page.evaluate` — it
 * must not close over anything outside its own body (Playwright only
 * ships the function's source across the CDP boundary). That's also why
 * this file needs `"DOM"` in `tsconfig.node.json`'s `lib` and
 * `globals.browser` in `eslint.config.js`'s e2e block (both added by this
 * task): the function is authored here but executed as browser code.
 */
import type { Page } from '@playwright/test';

/** SC 1.4.10's own floor, plus the reference's own breakpoints
 * (`src/shared/theme/breakpoints.ts`) and one common device width (375)
 * between the floor and `sm` — the task's own Implementation line names
 * exactly these six. */
export const REFLOW_WIDTHS = [320, 375, 600, 905, 1240, 1560] as const;

export const REFLOW_EXCEPTIONS = ['data-table', 'persistent-toolbar'] as const;
export type ReflowException = (typeof REFLOW_EXCEPTIONS)[number];

export type ReflowViolationKind =
  | 'body-scroll'
  | 'undeclared-scroller'
  | 'unknown-exception'
  | 'clipped-focusable';

export interface ReflowViolation {
  kind: ReflowViolationKind;
  detail: string;
}

interface CollectArgs {
  widthPx: number;
  knownExceptions: readonly string[];
}

// Self-contained on purpose — see the file header. No reference to
// anything declared outside this function's own body.
function collectViolations({ widthPx, knownExceptions }: CollectArgs): ReflowViolation[] {
  const violations: ReflowViolation[] = [];

  const describe = (el: Element): string => {
    const id = el.id !== '' ? `#${el.id}` : '';
    const classes =
      el instanceof HTMLElement && el.className.trim() !== ''
        ? `.${el.className.trim().split(/\s+/).slice(0, 2).join('.')}`
        : '';
    return `${el.tagName.toLowerCase()}${id}${classes}`;
  };

  const bodyScrollWidth = document.documentElement.scrollWidth;
  if (bodyScrollWidth > widthPx + 1) {
    violations.push({
      kind: 'body-scroll',
      detail: `document scrolls horizontally: scrollWidth ${String(bodyScrollWidth)} > viewport ${String(widthPx)}`,
    });
  }

  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
  let node: Node | null = walker.currentNode;
  while (node !== null) {
    if (node instanceof HTMLElement) {
      const style = getComputedStyle(node);
      const isScroller = style.overflowX === 'auto' || style.overflowX === 'scroll';
      if (isScroller && node.scrollWidth > node.clientWidth + 1) {
        const declaring = node.closest('[data-reflow-exception]');
        if (declaring === null) {
          violations.push({
            kind: 'undeclared-scroller',
            detail: `${describe(node)} scrolls horizontally with no data-reflow-exception ancestor`,
          });
        } else {
          const declaredValue = declaring.getAttribute('data-reflow-exception') ?? '';
          if (!knownExceptions.includes(declaredValue)) {
            violations.push({
              kind: 'unknown-exception',
              detail: `${describe(declaring)} declares unrecognized data-reflow-exception="${declaredValue}"`,
            });
          }
        }
      }
    }
    node = walker.nextNode();
  }

  const FOCUSABLE_SELECTOR = [
    'a[href]',
    'button:not([disabled])',
    'input:not([disabled])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
  ].join(', ');

  for (const el of Array.from(document.querySelectorAll(FOCUSABLE_SELECTOR))) {
    if (!(el instanceof HTMLElement)) {
      continue;
    }
    const rect = el.getBoundingClientRect();
    if (rect.width === 0 && rect.height === 0) {
      continue;
    }
    // Off-screen until scrolled, inside a container that already
    // declared its horizontal scroll — the reachable-by-scroll case, not
    // the clipped-with-no-escape case SC 1.4.10 cares about.
    if (el.closest('[data-reflow-exception]') !== null) {
      continue;
    }
    if (rect.right > widthPx + 1 || rect.left < -1) {
      violations.push({
        kind: 'clipped-focusable',
        detail: `${describe(el)} is clipped outside the ${String(widthPx)}px viewport (left ${String(rect.left)}, right ${String(rect.right)})`,
      });
    }
  }

  return violations;
}

/**
 * Reads the already-rendered page at `widthPx` and returns every reflow
 * violation found. An empty array is the only passing result — callers
 * assert `toEqual([])` rather than a boolean so a failure's `detail`
 * shows up in the test output.
 *
 * Deliberately does **not** resize the viewport itself. `useMediaQuery`
 * and MUI's own resize-observer-driven components (`DataGrid`,
 * `Collapse`) react to a `matchMedia`/`ResizeObserver` callback that
 * fires a tick *after* the CDP viewport resize lands — measuring in the
 * same call as the resize raced that tick and produced real but
 * non-reproducible violations (both `isCardView` and the toolbar's own
 * layout were still mid-transition). Resizing before navigation, so the
 * first render already happens at the target width, avoids the race
 * instead of papering over it with a wait.
 */
export async function measureReflowViolations(page: Page, widthPx: number): Promise<ReflowViolation[]> {
  return page.evaluate(collectViolations, { widthPx, knownExceptions: REFLOW_EXCEPTIONS });
}

/** Resizes then measures — for the self-test fixtures, which are static
 * `page.setContent` markup with no responsive React state to race. */
export async function checkReflow(page: Page, widthPx: number): Promise<ReflowViolation[]> {
  const viewport = page.viewportSize();
  await page.setViewportSize({ width: widthPx, height: viewport?.height ?? 900 });
  return measureReflowViolations(page, widthPx);
}
