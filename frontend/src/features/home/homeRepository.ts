/**
 * `HomeRepository` (task 033, §6.0.5, §9.2, UI §A.4). CONTRACT: TBD — the
 * whole endpoint is unspecified (§16 Q10). Routes below are this module's
 * own naming, confined here so a real contract replaces only this file.
 * Backed today by `shared/testdoubles/handlers` under MSW.
 */
import type { PlaneClient } from '@shared/api';
import type { AttentionItem } from '@shared/testdoubles/data/instances';

// A count is `null` when unknown — the UI renders "—", NEVER a fabricated
// `0` (G10, §A.4's "an unknown value renders — , never 0").
export interface HomeSummary {
  solutionsManaged: number | null;
  instancesDeclared: number | null;
  needAttentionNow: number | null;
  configStagedNotApplied: number | null;
  /**
   * INVENTED (task 038, §16 Q10 — no schema field carries this either).
   * Present only when part of the fleet hasn't reported, so a count may
   * be an undercount. `instancesDeclared` renders with a "+1?" suffix and
   * the page shows a banner naming this host (§A.4 "partial state").
   */
  partial?: { unreportingHost: string } | null;
}

export interface HomeRepository {
  summary(signal?: AbortSignal): Promise<HomeSummary>;
  attention(signal?: AbortSignal): Promise<AttentionItem[]>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createHomeRepository(cpClient: PlaneClient): HomeRepository {
  return {
    summary(signal) {
      return cpClient.get<HomeSummary>('/home/summary', opts(signal));
    },
    attention(signal) {
      return cpClient.get<AttentionItem[]>('/home/attention', opts(signal));
    },
  };
}
