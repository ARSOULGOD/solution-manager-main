/**
 * Colour mode and the desktop nav-collapse preference, both persisted
 * (design §8.1; task 111 for `navCollapsed`). The app-bar toggle (task 036)
 * reads and writes `mode`; `AppShell`'s desktop drawer toggle (task 111)
 * reads and writes `navCollapsed` so the operator's choice survives
 * navigation and reload, the same way the colour mode does.
 */
import { create } from 'zustand';
import { persist } from 'zustand/middleware';

import type { SmThemeMode } from '../theme/createSmTheme';

interface UiState {
  mode: SmThemeMode;
  setMode: (mode: SmThemeMode) => void;
  toggleMode: () => void;
  navCollapsed: boolean;
  setNavCollapsed: (navCollapsed: boolean) => void;
  toggleNavCollapsed: () => void;
}

export const useUiStore = create<UiState>()(
  persist(
    (set, get) => ({
      mode: 'light',
      setMode: (mode) => {
        set({ mode });
      },
      toggleMode: () => {
        set({ mode: get().mode === 'light' ? 'dark' : 'light' });
      },
      navCollapsed: false,
      setNavCollapsed: (navCollapsed) => {
        set({ navCollapsed });
      },
      toggleNavCollapsed: () => {
        set({ navCollapsed: !get().navCollapsed });
      },
    }),
    { name: 'sm-ui-store' },
  ),
);
