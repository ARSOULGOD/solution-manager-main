import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { useUiStore } from './uiStore';

describe('useUiStore — colour mode', () => {
  beforeEach(() => {
    localStorage.clear();
    useUiStore.setState({ mode: 'light' });
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('defaults to light', () => {
    expect(useUiStore.getState().mode).toBe('light');
  });

  it('setMode sets an explicit mode', () => {
    useUiStore.getState().setMode('dark');

    expect(useUiStore.getState().mode).toBe('dark');
  });

  it('toggleMode flips light and dark', () => {
    useUiStore.getState().toggleMode();
    expect(useUiStore.getState().mode).toBe('dark');

    useUiStore.getState().toggleMode();
    expect(useUiStore.getState().mode).toBe('light');
  });

  it('persists the mode to localStorage', () => {
    useUiStore.getState().setMode('dark');

    const stored = localStorage.getItem('sm-ui-store');
    expect(stored).toBeTruthy();
    const parsed = JSON.parse(stored ?? '{}') as { state: { mode: string } };
    expect(parsed.state.mode).toBe('dark');
  });
});

describe('useUiStore — desktop nav collapse (task 111)', () => {
  beforeEach(() => {
    localStorage.clear();
    useUiStore.setState({ navCollapsed: false });
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('defaults to expanded', () => {
    expect(useUiStore.getState().navCollapsed).toBe(false);
  });

  it('setNavCollapsed sets an explicit value', () => {
    useUiStore.getState().setNavCollapsed(true);

    expect(useUiStore.getState().navCollapsed).toBe(true);
  });

  it('toggleNavCollapsed flips collapsed and expanded', () => {
    useUiStore.getState().toggleNavCollapsed();
    expect(useUiStore.getState().navCollapsed).toBe(true);

    useUiStore.getState().toggleNavCollapsed();
    expect(useUiStore.getState().navCollapsed).toBe(false);
  });

  it('persists navCollapsed to localStorage', () => {
    useUiStore.getState().setNavCollapsed(true);

    const stored = localStorage.getItem('sm-ui-store');
    expect(stored).toBeTruthy();
    const parsed = JSON.parse(stored ?? '{}') as { state: { navCollapsed: boolean } };
    expect(parsed.state.navCollapsed).toBe(true);
  });
});
