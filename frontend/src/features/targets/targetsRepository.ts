/**
 * `TargetsRepository` (task 033, §6.0.5, UI §A.12.6). READ-ONLY — no write
 * method exists anywhere in this seam. Credential entry is intentionally
 * absent by design: the `dp-remote` apply protocol it would depend on is
 * an open blocker (`DECISIONS.md` open item 1). Nothing here writes to a
 * target.
 */
import type { PlaneClient } from '@shared/api';
import type { TargetRow } from '@shared/testdoubles/data/targets';

export interface TargetsRepository {
  list(signal?: AbortSignal): Promise<TargetRow[]>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createTargetsRepository(cpClient: PlaneClient): TargetsRepository {
  return {
    list(signal) {
      return cpClient.get<TargetRow[]>('/targets', opts(signal));
    },
  };
}
