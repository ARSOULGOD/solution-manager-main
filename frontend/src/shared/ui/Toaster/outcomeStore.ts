/**
 * Per-resource persistent outcome (FE-021 + FE-044). `toastStore` is
 * acknowledgement — it auto-dismisses; this store is the record that
 * survives a route change within the same resource, which
 * `InstanceHeader`'s `OutcomeBadge` reads. `pushOutcome` is the one call a
 * mutation makes for both: it fires the transient toast AND records the
 * persistent outcome, so a caller cannot produce one without the other.
 */
import { create } from 'zustand';

import type { OutcomeBadgeProps, ToastInput } from './Toaster.types';
import { pushToast } from './toastStore';

interface OutcomeState {
  byResource: Record<string, OutcomeBadgeProps>;
  set: (resourceId: string, outcome: OutcomeBadgeProps) => void;
}

export const useOutcomeStore = create<OutcomeState>((set) => ({
  byResource: {},
  set: (resourceId, outcome) =>
    set((state) => ({ byResource: { ...state.byResource, [resourceId]: outcome } })),
}));

export function useOutcome(resourceId: string): OutcomeBadgeProps | undefined {
  return useOutcomeStore((state) => state.byResource[resourceId]);
}

export function pushOutcome(resourceId: string, input: ToastInput): void {
  useOutcomeStore.getState().set(resourceId, {
    title: input.title,
    correlationId: input.correlationId,
    severity: input.severity ?? 'ok',
  });
  pushToast(input);
}
