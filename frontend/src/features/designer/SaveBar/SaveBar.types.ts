import type { DraftValues } from '@contracts/view-model/types';

import type { ConfigRepository, SaveConfigResult } from '../configRepository';
import type { ValidationEntry } from '../ValidationSummary/collectValidationEntries';

export interface SaveBarProps {
  instanceId: string;
  repository: ConfigRepository;
  /** `useDesignerDraft().payload` — the rendered set from `project()`, never `getValues()` (`FE-D17`). */
  payload: DraftValues;
  /** The last-seen config-of-record generation (`DesignerLoad.configGeneration`, or the previous `onSaved`'s). Sent on every save (`FE-D18` clause A). */
  generation: string;
  /** `useDesignerDraft().dirtyInstanceCount`. Save is disabled at 0; Apply is enabled only at 0 (current view matches the record). */
  dirtyCount: number;
  /** `collectValidationEntries(sections).blocks.length`. Save is disabled above 0. */
  blockingCount: number;
  /**
   * `collectValidationEntries(sections).warns`. Advisory only — never
   * disables Save (task 055). When non-empty, Save opens
   * `OverrideReasonDialog` (task 057, `UI-D16`) instead of saving directly;
   * the count shown in the recommendation chip is `warnings.length`, so
   * there is no separate count prop that could drift from this list.
   */
  warnings: readonly ValidationEntry[];
  /** Fired after a successful save so the caller can reset its RHF baseline and the generation it passes back in (`FE-D20`: no optimistic update — this is the only path that ever clears `dirtyCount`). */
  onSaved: (result: SaveConfigResult) => void;
  /** Fired on every failed save, in addition to this component's own inline `Alert`. The seam task 056 (`409` → refetch-and-diff, `Deps: 055`) hooks into rather than reaching into this component's mutation state. */
  onSaveError?: (error: unknown) => void;
  /** Task 061 (blocked) owns what Apply actually does; this task only owns when the button may be pressed. Omitted while nothing exists to call. */
  onApply?: () => void;
}
