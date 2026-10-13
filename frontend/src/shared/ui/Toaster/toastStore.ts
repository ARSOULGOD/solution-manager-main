import { create } from 'zustand';

import type { ToastInput, ToastRecord } from './Toaster.types';

interface ToastState {
  toasts: ToastRecord[];
  push: (input: ToastInput) => string;
  dismiss: (id: string) => void;
}

export const useToastStore = create<ToastState>((set) => ({
  toasts: [],
  push: (input) => {
    const id = `toast-${String(Date.now())}-${Math.random().toString(36).slice(2, 8)}`;
    const record: ToastRecord = {
      id,
      title: input.title,
      correlationId: input.correlationId,
      severity: input.severity ?? 'ok',
      ...(input.detail !== undefined ? { detail: input.detail } : {}),
    };
    set((state) => ({ toasts: [...state.toasts, record] }));
    return id;
  },
  dismiss: (id) => {
    set((state) => ({ toasts: state.toasts.filter((toast) => toast.id !== id) }));
  },
}));

export function pushToast(input: ToastInput): string {
  return useToastStore.getState().push(input);
}
