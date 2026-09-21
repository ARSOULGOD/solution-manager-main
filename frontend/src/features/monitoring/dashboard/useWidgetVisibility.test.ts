import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useWidgetVisibility } from './useWidgetVisibility';

class NeverIntersectingObserver implements IntersectionObserver {
  readonly root: Element | Document | null = null;
  readonly rootMargin: string = '';
  readonly scrollMargin: string = '';
  readonly thresholds: readonly number[] = [];
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }
}

let capturedCallback: IntersectionObserverCallback | null = null;
let capturedOptions: IntersectionObserverInit | undefined;

class SpyObserver implements IntersectionObserver {
  readonly root: Element | Document | null = null;
  readonly rootMargin: string = '';
  readonly scrollMargin: string = '';
  readonly thresholds: readonly number[] = [];

  constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
    capturedCallback = callback;
    capturedOptions = options;
  }

  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }
}

afterEach(() => {
  vi.unstubAllGlobals();
  capturedCallback = null;
  capturedOptions = undefined;
});

describe('useWidgetVisibility', () => {
  it('is visible once the default (test-environment) observer fires intersecting', () => {
    const ref = { current: document.createElement('div') };
    const { result } = renderHook(() => useWidgetVisibility(ref));
    expect(result.current).toBe(true);
  });

  it('stays false when the element never intersects', () => {
    vi.stubGlobal('IntersectionObserver', NeverIntersectingObserver);
    const ref = { current: document.createElement('div') };
    const { result } = renderHook(() => useWidgetVisibility(ref));
    expect(result.current).toBe(false);
  });

  it('uses a 120px rootMargin, same as the visual reference', () => {
    vi.stubGlobal('IntersectionObserver', SpyObserver);
    const ref = { current: document.createElement('div') };
    renderHook(() => useWidgetVisibility(ref));
    expect(capturedOptions).toEqual({ rootMargin: '120px' });
  });

  it('never flips back to false once a later entry reports not-intersecting', () => {
    vi.stubGlobal('IntersectionObserver', SpyObserver);
    const ref = { current: document.createElement('div') };
    const { result } = renderHook(() => useWidgetVisibility(ref));

    act(() => {
      capturedCallback?.(
        [{ isIntersecting: true } as IntersectionObserverEntry],
        new SpyObserver(() => {}),
      );
    });
    expect(result.current).toBe(true);

    act(() => {
      capturedCallback?.(
        [{ isIntersecting: false } as IntersectionObserverEntry],
        new SpyObserver(() => {}),
      );
    });
    expect(result.current).toBe(true);
  });
});
