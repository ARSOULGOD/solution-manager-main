import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';

import { RouteErrorBoundary } from './RouteErrorBoundary';

// React 18+ requires this flag for `act` to work outside a test-library
// wrapper. No test harness exists yet (task 008); set it locally here,
// same as src/app/router.test.tsx.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

class CorrelatedError extends Error {
  correlationId = 'corr-route-1';
}

function Boom(): never {
  throw new CorrelatedError('route failed to render');
}

let container: HTMLDivElement | null = null;
let root: Root | null = null;

afterEach(() => {
  if (root) {
    act(() => {
      root?.unmount();
    });
  }
  container?.remove();
  container = null;
  root = null;
});

function render(el: React.ReactElement): HTMLDivElement {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  act(() => {
    root?.render(el);
  });
  return container;
}

describe('RouteErrorBoundary', () => {
  it('a throwing route renders an ErrorPanel and leaves the surrounding shell mounted', () => {
    const el = render(
      <div>
        <nav>Shell navigation</nav>
        <header>Shell header</header>
        <RouteErrorBoundary>
          <Boom />
        </RouteErrorBoundary>
      </div>,
    );

    expect(el.textContent).toContain('Shell navigation');
    expect(el.textContent).toContain('Shell header');
    expect(el.querySelector('[role="alert"]')).not.toBeNull();
    expect(el.textContent).toContain('route failed to render');
    expect(el.textContent).toContain('corr-route-1');
  });

  it('renders no error panel when the route does not throw', () => {
    const el = render(
      <div>
        <nav>Shell navigation</nav>
        <RouteErrorBoundary>
          <span>Route content</span>
        </RouteErrorBoundary>
      </div>,
    );

    expect(el.querySelector('[role="alert"]')).toBeNull();
    expect(el.textContent).toContain('Route content');
  });
});
