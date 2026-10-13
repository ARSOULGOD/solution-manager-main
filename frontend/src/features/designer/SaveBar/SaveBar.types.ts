import type { ComponentSelection, DraftValues } from '@contracts/view-model/types';

import type { ConfigRepository, RenderedEntry, SaveConfigResult } from '../configRepository';
import type { ValidationEntry } from '../ValidationSummary/collectValidationEntries';

export interface SaveBarProps {
  instanceId: string;
  repository: ConfigRepository;
  /** `useDesignerDraft().payload` — the rendered set from `project()`, never `getValues()` (`FE-D17`). */
  payload: DraftValues;
  /** `DesignerLoad.stateVersion`, or the previous `onSaved`'s — `stageConfig`'s real optimistic-concurrency base (plan 008 Task 2; replaces the fixture-era `generation` string). */
  baseStateVersion: number;
  /** The same selection `DesignerForm` derived once (`deriveComponentSelection`) — builds `stageConfig`'s `componentSelection.instances[]` (`instanceKey`/`type`). No layout UI exists yet in this pass, so no instance ever carries a `layout` (`OR-96`'s per-instance presentation record is therefore cleared, not written, by a save through this component — flagged, not designed here). */
  componentSelection: ComponentSelection;
  /** `useDesignerDraft().rendered` — `stageConfig`'s `rendered[]`, the save/diff/hash payload (`FE-D17`), emitted by the engine's own scoped projection pass, never re-derived here. */
  rendered: readonly RenderedEntry[];
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
   *
   * **Contract gap, not fabricated (plan 008 Task 2):** `stageConfig`'s real request schema is
   * `additionalProperties: false` with no `overrideReason` field — the confirmed reason the
   * dialog collects has nowhere on the wire to go. The dialog still runs (the operator still
   * explicitly confirms the warning); `SaveBar.tsx`'s mutation accepts and discards the reason
   * rather than inventing a field the server would reject.
   */
  warnings: readonly ValidationEntry[];
  /** Fired after a successful save so the caller can reset its RHF baseline and the `stateVersion`/generation it passes back in (`FE-D20`: no optimistic update — this is the only path that ever clears `dirtyCount`). */
  onSaved: (result: SaveConfigResult) => void;
  /** Fired on every failed save, in addition to this component's own inline `Alert`. `409` (stale `stateVersion`) and `422` (server-side projection mismatch) both reach this, as DISTINCT statuses — the caller (`DesignerForm`) tells them apart by `ApiError.status`, never folding `422` into a generic save error (plan 008 Task 2). */
  onSaveError?: (error: unknown) => void;
  /** Task 061 (blocked) owns what Apply actually does; this task only owns when the button may be pressed. Omitted while nothing exists to call. */
  onApply?: () => void;
}
