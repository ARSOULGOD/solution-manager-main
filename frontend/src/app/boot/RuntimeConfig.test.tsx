import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { RuntimeConfigBoot, useRuntimeConfig } from './RuntimeConfig';

// React 18+ requires this flag for `act` to work outside a test-library
// wrapper. No test harness exists yet (task 008); set it locally here,
// same as src/app/router.test.tsx.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function jsonResponse(body: unknown, init: { ok?: boolean; status?: number } = {}) {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    statusText: 'status text',
    json: () => Promise.resolve(body),
  } as Response;
}

function Consumer() {
  const config = useRuntimeConfig();
  return <span>apiBasePath={config.apiBasePath}</span>;
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
  vi.unstubAllGlobals();
});

async function renderBoot(): Promise<HTMLDivElement> {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  act(() => {
    root?.render(
      <RuntimeConfigBoot>
        <Consumer />
      </RuntimeConfigBoot>,
    );
  });
  // The fetch resolves asynchronously; flush microtasks/effects until the
  // boot component leaves its loading (empty) render.
  for (let attempt = 0; attempt < 20 && container.textContent === ''; attempt += 1) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 5));
    });
  }
  return container;
}

describe('RuntimeConfigBoot', () => {
  it('renders children with the resolved config once the fetch succeeds', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ apiBasePath: '/api', channel: 'stable' })),
    );

    const el = await renderBoot();

    expect(el.textContent).toBe('apiBasePath=/api');
  });

  it('renders a visible boot error page, not the children, when the fetch fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({}, { ok: false, status: 500 })));

    const el = await renderBoot();

    expect(el.querySelector('[role="alert"]')).not.toBeNull();
    expect(el.textContent).not.toContain('apiBasePath=');
  });

  it('renders a visible boot error page when the config fails validation', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ channel: 'stable' })));

    const el = await renderBoot();

    expect(el.querySelector('[role="alert"]')).not.toBeNull();
  });
});
