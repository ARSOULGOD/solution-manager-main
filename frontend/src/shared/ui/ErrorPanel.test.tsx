import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ErrorPanel } from './ErrorPanel';

// React 18+ requires this flag for `act` to work outside a test-library
// wrapper. No test harness exists yet (task 008); set it locally here,
// same as src/app/router.test.tsx.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

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

function renderPanel(el: React.ReactElement): HTMLDivElement {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  act(() => {
    root?.render(el);
  });
  return container;
}

describe('ErrorPanel', () => {
  it('renders as an alert with the title and detail', () => {
    const el = renderPanel(<ErrorPanel title="Could not load" detail="The server timed out." />);

    expect(el.querySelector('[role="alert"]')).not.toBeNull();
    expect(el.textContent).toContain('Could not load');
    expect(el.textContent).toContain('The server timed out.');
  });

  it('omits code and correlation id when not supplied', () => {
    const el = renderPanel(<ErrorPanel title="Could not load" detail="The server timed out." />);

    expect(el.textContent).not.toContain('Error code');
    expect(el.textContent).not.toContain('Correlation id');
  });

  it('shows the code and the server-returned correlation id when supplied', () => {
    const el = renderPanel(
      <ErrorPanel
        title="Could not load"
        detail="The server timed out."
        code="SERIES_CAP_EXCEEDED"
        correlationId="corr-123"
      />,
    );

    expect(el.textContent).toContain('SERIES_CAP_EXCEEDED');
    expect(el.textContent).toContain('corr-123');
  });

  it('invokes onRetry when the retry action is used', () => {
    const onRetry = vi.fn();
    const el = renderPanel(
      <ErrorPanel title="Could not load" detail="The server timed out." onRetry={onRetry} />,
    );

    const button = el.querySelector('button');
    expect(button).not.toBeNull();
    act(() => {
      button?.click();
    });

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('renders no retry action when onRetry is not supplied', () => {
    const el = renderPanel(<ErrorPanel title="Could not load" detail="The server timed out." />);

    expect(el.querySelector('button')).toBeNull();
  });
});
