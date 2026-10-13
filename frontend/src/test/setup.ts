/**
 * Vitest `unit` project setup (vitest.config.ts). Wires the things every
 * component/route test gets for free: jest-dom matchers, the jest-axe
 * matcher (typed via ./jest-axe.d.ts), RTL cleanup, the MSW node server
 * with `onUnhandledRequest: "error"` — an unmocked request fails the test
 * rather than a fixture silently drifting from what a component actually
 * calls (task 008 acceptance) — and a `matchMedia` polyfill (task 106;
 * see `./matchMedia.ts`'s own header for why one is needed at all).
 */
import '@testing-library/jest-dom/vitest';

import { cleanup, configure } from '@testing-library/react';
import { toHaveNoViolations } from 'jest-axe';
import { afterAll, afterEach, beforeAll, expect } from 'vitest';

import { resetConnectionStore } from '@shared/testdoubles/handlers/cp';
import { resetDashboardStore } from '@shared/testdoubles/handlers/dashboardStore';
import { resetConfigGenerations } from '@shared/testdoubles/handlers/dp';
import { resetRbacStore } from '@shared/testdoubles/handlers/rbacStore';
import { server } from '@shared/testdoubles/server';

import { installMatchMediaPolyfill, resetReducedMotion, resetViewportWidth } from './matchMedia';

expect.extend(toHaveNoViolations);
installMatchMediaPolyfill();

// `waitFor`'s own timeout is separate from Vitest's `testTimeout` and was
// left at Testing Library's 1 s default, so the reasoning recorded against
// `testTimeout` in `vitest.config.ts` never actually reached the async
// helpers. It applies just as much here: a `waitFor` that is comfortable
// idle has far less than 1 s of real budget once the worker pool is
// saturated, and what times out first is usually a MUI exit transition —
// producing an intermittent "expected <div> to be null" on a dialog that
// does close, in whichever file happens to be unlucky. Raised to match, so
// suite size changes stop showing up as unrelated flakes. Still short
// enough to catch a genuine hang well inside the 20 s test timeout.
configure({ asyncUtilTimeout: 5_000 });

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = ResizeObserverStub;
}

// jsdom has no layout, so "is this element visible" has no real answer —
// default-visible (fire once, synchronously, `isIntersecting: true`) is
// the sane default for the vast majority of tests, which are not
// exercising `useWidgetVisibility`'s (task 086) off-screen behaviour. A
// test that IS — proving an off-screen widget issues no request — swaps
// this for a never-intersecting stub via `vi.stubGlobal`, the same
// per-test override mechanism `resetReducedMotion` documents for
// `matchMedia` above.
class IntersectionObserverStub implements IntersectionObserver {
  readonly root: Element | Document | null = null;
  readonly rootMargin: string = '';
  readonly scrollMargin: string = '';
  readonly thresholds: readonly number[] = [];

  constructor(private readonly callback: IntersectionObserverCallback) {}

  observe(target: Element): void {
    const entry = { target, isIntersecting: true } as IntersectionObserverEntry;
    this.callback([entry], this);
  }

  unobserve(): void {}
  disconnect(): void {}
  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }
}

if (typeof globalThis.IntersectionObserver === 'undefined') {
  globalThis.IntersectionObserver = IntersectionObserverStub;
}

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  // RTL's auto-cleanup only fires off a *global* afterEach (vitest.config.ts
  // sets `globals: false`), so it never ran on its own — call it explicitly.
  cleanup();
  server.resetHandlers();
  // `resetHandlers` restores handlers, not the module state they close
  // over. The config-generation map is the one piece of such state that
  // affects a later test's outcome: a generation left advanced by one
  // test makes the next test's first save look like a 409 conflict.
  resetConfigGenerations();
  // Task 132 made the dashboard fixture stateful, so it needs the same
  // treatment: a widget saved by one test would otherwise still be on the
  // board in the next, and a "Reset defaults" test would restore whatever
  // the previous test left behind.
  resetDashboardStore();
  resetConnectionStore();
  // Groups management made the RBAC fixture stateful for the same reason:
  // a group created, a member added or an assignment revoked by one test
  // would otherwise still be there in the next.
  resetRbacStore();
  // A test that calls setViewportWidth (task 106) must not leak a narrow
  // width into the next test's default-desktop assumptions.
  resetViewportWidth();
  // Same leak risk for setReducedMotion (task 072).
  resetReducedMotion();
});

afterAll(() => {
  server.close();
});
