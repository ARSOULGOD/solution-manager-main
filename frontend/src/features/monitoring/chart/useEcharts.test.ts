import { renderHook, waitFor } from '@testing-library/react';
import { StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useEcharts } from './useEcharts';

/**
 * Captures the callback `useEcharts` registers so a test can simulate a
 * resize delivery — `setup.ts`'s global `ResizeObserver` stub is a
 * no-op that never calls back, so it can't exercise this.
 */
class MockResizeObserver {
  static instances: MockResizeObserver[] = [];
  callback: ResizeObserverCallback;
  observed: Element[] = [];
  disconnect = vi.fn();

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback;
    MockResizeObserver.instances.push(this);
  }

  observe(element: Element): void {
    this.observed.push(element);
  }

  unobserve(): void {}

  trigger(): void {
    this.callback([], this);
  }
}

function makeContainerRef(): React.RefObject<HTMLDivElement | null> {
  const container = document.createElement('div');
  document.body.appendChild(container);
  return { current: container };
}

beforeEach(() => {
  MockResizeObserver.instances = [];
  vi.stubGlobal('ResizeObserver', MockResizeObserver);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('useEcharts', () => {
  it('creates an instance via the factory, passing the mounted container', async () => {
    const containerRef = makeContainerRef();
    const instance = { resize: vi.fn(), dispose: vi.fn() };
    const createInstance = vi.fn().mockResolvedValue(instance);

    renderHook(() => useEcharts(containerRef, createInstance));

    await waitFor(() => expect(createInstance).toHaveBeenCalledWith(containerRef.current));
  });

  it('returns the created instance once creation resolves', async () => {
    const containerRef = makeContainerRef();
    const instance = { resize: vi.fn(), dispose: vi.fn() };
    const createInstance = vi.fn().mockResolvedValue(instance);

    const { result } = renderHook(() => useEcharts(containerRef, createInstance));

    expect(result.current).toBeNull();
    await waitFor(() => expect(result.current).toBe(instance));
  });

  it('disposes the instance on unmount', async () => {
    const containerRef = makeContainerRef();
    const instance = { resize: vi.fn(), dispose: vi.fn() };
    const createInstance = vi.fn().mockResolvedValue(instance);

    const { result, unmount } = renderHook(() => useEcharts(containerRef, createInstance));
    await waitFor(() => expect(result.current).toBe(instance));

    unmount();

    expect(instance.dispose).toHaveBeenCalledTimes(1);
  });

  it('disposes a resolved instance if unmounted before creation settles', async () => {
    const containerRef = makeContainerRef();
    const instance = { resize: vi.fn(), dispose: vi.fn() };
    let resolveCreate: (value: typeof instance) => void = () => {};
    const createInstance = vi.fn(
      () =>
        new Promise<typeof instance>((resolve) => {
          resolveCreate = resolve;
        }),
    );

    const { unmount } = renderHook(() => useEcharts(containerRef, createInstance));
    // Wait for creation to be genuinely in flight before unmounting. Since
    // task 126 serialised creation through a promise chain, the factory is
    // invoked on a later microtask than the effect itself — unmounting
    // before that turn takes the case below instead, where nothing is ever
    // created and so nothing needs disposing.
    await waitFor(() => expect(createInstance).toHaveBeenCalledTimes(1));

    unmount();
    resolveCreate(instance);

    await waitFor(() => expect(instance.dispose).toHaveBeenCalledTimes(1));
  });

  it('never calls the factory at all when cleanup runs before creation starts', async () => {
    const containerRef = makeContainerRef();
    const createInstance = vi.fn().mockResolvedValue({ resize: vi.fn(), dispose: vi.fn() });

    const { unmount } = renderHook(() => useEcharts(containerRef, createInstance));
    unmount();

    // Let the whole chain drain — the queued callback observes `cancelled`
    // and returns without constructing anything, which is why the case
    // above has to wait for the factory before it can unmount.
    await Promise.resolve();
    await Promise.resolve();
    expect(createInstance).not.toHaveBeenCalled();
  });

  it('calls instance.resize() when the observed container reports a resize', async () => {
    const containerRef = makeContainerRef();
    const instance = { resize: vi.fn(), dispose: vi.fn() };
    const createInstance = vi.fn().mockResolvedValue(instance);

    const { result } = renderHook(() => useEcharts(containerRef, createInstance));
    await waitFor(() => expect(result.current).toBe(instance));

    expect(MockResizeObserver.instances).toHaveLength(1);
    MockResizeObserver.instances[0]?.trigger();

    expect(instance.resize).toHaveBeenCalledTimes(1);
  });

  it('disconnects the resize observer on unmount', async () => {
    const containerRef = makeContainerRef();
    const instance = { resize: vi.fn(), dispose: vi.fn() };
    const createInstance = vi.fn().mockResolvedValue(instance);

    const { result, unmount } = renderHook(() => useEcharts(containerRef, createInstance));
    await waitFor(() => expect(result.current).toBe(instance));
    const observer = MockResizeObserver.instances[0];

    unmount();

    expect(observer?.disconnect).toHaveBeenCalledTimes(1);
  });

  it('recreates the instance when the factory identity changes, disposing the old one first', async () => {
    const containerRef = makeContainerRef();
    const first = { resize: vi.fn(), dispose: vi.fn() };
    const second = { resize: vi.fn(), dispose: vi.fn() };
    const createFirst = vi.fn().mockResolvedValue(first);
    const createSecond = vi.fn().mockResolvedValue(second);

    const { result, rerender } = renderHook(
      ({ createInstance }) => useEcharts(containerRef, createInstance),
      { initialProps: { createInstance: createFirst } },
    );
    await waitFor(() => expect(result.current).toBe(first));

    rerender({ createInstance: createSecond });

    await waitFor(() => expect(result.current).toBe(second));
    expect(first.dispose).toHaveBeenCalledTimes(1);
  });

  it('does not recreate the instance on a rerender with the same factory identity', async () => {
    const containerRef = makeContainerRef();
    const instance = { resize: vi.fn(), dispose: vi.fn() };
    const createInstance = vi.fn().mockResolvedValue(instance);

    const { result, rerender } = renderHook(() => useEcharts(containerRef, createInstance));
    await waitFor(() => expect(result.current).toBe(instance));

    rerender();

    expect(createInstance).toHaveBeenCalledTimes(1);
    expect(instance.dispose).not.toHaveBeenCalled();
  });

  /**
   * `main.tsx` renders the app inside `<StrictMode>`, so every effect is
   * invoked → cleaned up → invoked again. `echarts.init` on a node that
   * already carries an instance returns the *existing* instance rather
   * than a second one, which is why both passes are modelled here as one
   * shared object: if the two passes are allowed to overlap, pass 1's
   * cleanup disposes the very instance pass 2 goes on to publish, and
   * every later `setOption` is a silent no-op against a dead chart.
   */
  it('does not publish a disposed instance when StrictMode double-invokes the effect', async () => {
    const containerRef = makeContainerRef();
    const instance = { resize: vi.fn(), dispose: vi.fn() };
    const createInstance = vi.fn().mockResolvedValue(instance);

    const { result } = renderHook(() => useEcharts(containerRef, createInstance), {
      wrapper: StrictMode,
    });

    await waitFor(() => expect(result.current).toBe(instance));
    expect(instance.dispose).not.toHaveBeenCalled();
  });

  it('creates exactly one instance across a StrictMode double-invoke', async () => {
    const containerRef = makeContainerRef();
    const instance = { resize: vi.fn(), dispose: vi.fn() };
    const createInstance = vi.fn().mockResolvedValue(instance);

    const { result } = renderHook(() => useEcharts(containerRef, createInstance), {
      wrapper: StrictMode,
    });

    await waitFor(() => expect(result.current).toBe(instance));
    expect(createInstance).toHaveBeenCalledTimes(1);
  });

  it('still disposes exactly once on unmount under StrictMode', async () => {
    const containerRef = makeContainerRef();
    const instance = { resize: vi.fn(), dispose: vi.fn() };
    const createInstance = vi.fn().mockResolvedValue(instance);

    const { result, unmount } = renderHook(() => useEcharts(containerRef, createInstance), {
      wrapper: StrictMode,
    });
    await waitFor(() => expect(result.current).toBe(instance));

    unmount();

    await waitFor(() => expect(instance.dispose).toHaveBeenCalledTimes(1));
  });
});
