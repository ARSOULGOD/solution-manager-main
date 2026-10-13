import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  clearDraft,
  hasDraft,
  loadDraft,
  saveDraft,
  useDraftAutosave,
  useDraftRestore,
  useIdleTimer,
} from './useIdleTimer';

beforeEach(() => {
  sessionStorage.clear();
});

describe('useIdleTimer', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('calls onIdle after the timeout with no activity', () => {
    const onIdle = vi.fn();
    renderHook(() => useIdleTimer(30, onIdle));

    act(() => {
      vi.advanceTimersByTime(30 * 60_000 - 1);
    });
    expect(onIdle).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(onIdle).toHaveBeenCalledTimes(1);
  });

  it('activity resets the timer — no idle fire while active', () => {
    const onIdle = vi.fn();
    renderHook(() => useIdleTimer(30, onIdle));

    act(() => {
      vi.advanceTimersByTime(29 * 60_000);
      window.dispatchEvent(new Event('mousemove'));
      vi.advanceTimersByTime(29 * 60_000);
    });

    expect(onIdle).not.toHaveBeenCalled();
  });

  it('is disabled entirely when timeoutMinutes <= 0', () => {
    const onIdle = vi.fn();
    renderHook(() => useIdleTimer(0, onIdle));

    act(() => {
      vi.advanceTimersByTime(60 * 60_000);
    });

    expect(onIdle).not.toHaveBeenCalled();
  });

  it('stops firing after unmount', () => {
    const onIdle = vi.fn();
    const { unmount } = renderHook(() => useIdleTimer(30, onIdle));
    unmount();

    act(() => {
      vi.advanceTimersByTime(60 * 60_000);
    });

    expect(onIdle).not.toHaveBeenCalled();
  });
});

describe('saveDraft / loadDraft / clearDraft / hasDraft', () => {
  it('round-trips values under the smc.draft.<instanceId> key', () => {
    saveDraft('cache-prod-01', { namespace: 'ledger', maxSize: 5000 });

    expect(sessionStorage.getItem('smc.draft.cache-prod-01')).not.toBeNull();
    const draft = loadDraft('cache-prod-01');
    expect(draft?.instanceId).toBe('cache-prod-01');
    expect(draft?.values).toEqual({ namespace: 'ledger', maxSize: 5000 });
  });

  it('never persists a value whose key is listed as secret (G7)', () => {
    saveDraft(
      'cache-prod-01',
      { namespace: 'ledger', 'solace.password': 'super-secret-value' },
      ['solace.password'],
    );

    const raw = sessionStorage.getItem('smc.draft.cache-prod-01') ?? '';
    expect(raw).not.toContain('super-secret-value');
    expect(loadDraft('cache-prod-01')?.values).toEqual({ namespace: 'ledger' });
  });

  it('uses sessionStorage, never localStorage', () => {
    saveDraft('cache-prod-01', { namespace: 'ledger' });

    expect(localStorage.getItem('smc.draft.cache-prod-01')).toBeNull();
    expect(sessionStorage.getItem('smc.draft.cache-prod-01')).not.toBeNull();
  });

  it('loadDraft returns null when nothing is stored', () => {
    expect(loadDraft('no-such-instance')).toBeNull();
  });

  it('loadDraft returns null (not a throw) on corrupt JSON', () => {
    sessionStorage.setItem('smc.draft.broken', 'not json{{{');
    expect(loadDraft('broken')).toBeNull();
  });

  it('clearDraft removes the entry; hasDraft reflects it', () => {
    saveDraft('cache-prod-01', { namespace: 'ledger' });
    expect(hasDraft('cache-prod-01')).toBe(true);

    clearDraft('cache-prod-01');

    expect(hasDraft('cache-prod-01')).toBe(false);
    expect(loadDraft('cache-prod-01')).toBeNull();
  });

  it('keys are scoped per instance — one instance never sees another\'s draft', () => {
    saveDraft('inst-a', { x: 1 });
    saveDraft('inst-b', { x: 2 });

    expect(loadDraft('inst-a')?.values).toEqual({ x: 1 });
    expect(loadDraft('inst-b')?.values).toEqual({ x: 2 });
  });
});

describe('useDraftAutosave', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('writes after the debounce, not immediately', () => {
    renderHook(() => useDraftAutosave('cache-prod-01', { namespace: 'ledger' }, [], 800));

    expect(loadDraft('cache-prod-01')).toBeNull();

    act(() => {
      vi.advanceTimersByTime(800);
    });

    expect(loadDraft('cache-prod-01')?.values).toEqual({ namespace: 'ledger' });
  });
});

describe('useDraftRestore — the round trip: write → lock → sign in → restore', () => {
  it('picks up a draft written before the "lock" the next time the screen mounts', () => {
    // "Written on a debounced change" before the idle lock fired.
    saveDraft('cache-prod-01', { namespace: 'ledger-v2', maxSize: 5000000 });

    // "Sign in" — a fresh mount of whatever screen calls useDraftRestore,
    // simulating the operator landing back on Designer after re-auth.
    const { result } = renderHook(() => useDraftRestore('cache-prod-01'));

    expect(result.current.draft?.values).toEqual({ namespace: 'ledger-v2', maxSize: 5000000 });
  });

  it('discard() clears both the returned state and storage', () => {
    saveDraft('cache-prod-01', { namespace: 'ledger-v2' });
    const { result } = renderHook(() => useDraftRestore('cache-prod-01'));
    expect(result.current.draft).not.toBeNull();

    act(() => {
      result.current.discard();
    });

    expect(result.current.draft).toBeNull();
    expect(hasDraft('cache-prod-01')).toBe(false);
  });

  it('a fresh instance with no prior draft restores nothing', () => {
    const { result } = renderHook(() => useDraftRestore('never-had-a-draft'));
    expect(result.current.draft).toBeNull();
  });
});
