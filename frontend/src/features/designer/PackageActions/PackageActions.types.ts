import type { ViewSection } from '@contracts/view-model/types';

import type { ActionsRepository } from '../actionsRepository';

export interface PackageActionsProps {
  instanceId: string;
  /** `ViewModel.sections`, verbatim — same shape `ValidationSummary`/`SectionNav` take. Actions are bucketed per-section by `project()` (design.md §3.5 leaves the bucketing unspecified); this component flattens across all of them rather than asking the caller to. */
  sections: readonly ViewSection[];
  repository: ActionsRepository;
  /**
   * The editing-blocked gate (`VERSION_DRIFT`/`VERSION_UNKNOWN`, task 059)
   * — every action disables alongside the rest of the form. Independent of
   * `action.enabled`, which is this one action's own `when` gate. Defaults
   * `false` since no Designer page assembles this component yet (the same
   * gap `SaveBar`/`ConflictDialog` leave open).
   */
  disabled?: boolean;
}
