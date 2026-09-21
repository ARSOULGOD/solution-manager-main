import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';

import { WidgetErrorBoundary } from './WidgetErrorBoundary';

// React 18+ requires this flag for `act` to work outside a test-library
// wrapper. No test harness exists yet (task 008); set it locally here,
// same as src/app/router.test.tsx.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

class CorrelatedError extends Error {
  code = 'SERIES_CAP_EXCEEDED';
  correlationId = 'corr-widget-1';
}

function Boom(): never {
  throw new CorrelatedError('widget query failed');
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

describe('WidgetErrorBoundary', () => {
  it('renders an ErrorPanel in place of a throwing widget, surfacing its code and correlation id', () => {
    const el = render(
      <WidgetErrorBoundary>
        <Boom />
      </WidgetErrorBoundary>,
    );

    expect(el.querySelector('[role="alert"]')).not.toBeNull();
    expect(el.textContent).toContain('widget query failed');
    expect(el.textContent).toContain('SERIES_CAP_EXCEEDED');
    expect(el.textContent).toContain('corr-widget-1');
  });

  it('a throwing widget leaves a sibling widget mounted', () => {
    const el = render(
      <div>
        <WidgetErrorBoundary>
          <Boom />
        </WidgetErrorBoundary>
        <WidgetErrorBoundary>
          <span>Sibling widget content</span>
        </WidgetErrorBoundary>
      </div>,
    );

    expect(el.textContent).toContain('Sibling widget content');
    expect(el.textContent).toContain('widget query failed');
  });

  it('renders no error panel when the child does not throw', () => {
    const el = render(
      <WidgetErrorBoundary>
        <span>Healthy widget content</span>
      </WidgetErrorBoundary>,
    );

    expect(el.querySelector('[role="alert"]')).toBeNull();
    expect(el.textContent).toBe('Healthy widget content');
  });
});
