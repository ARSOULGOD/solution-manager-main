import type { ViewSection } from '@contracts/view-model/types';

export interface ValidationSummaryProps {
  /** `ViewModel.sections`, verbatim and in order — same shape `SectionNav` takes (task 050). */
  sections: readonly ViewSection[];
  /**
   * Fired when a chip is clicked. `sectionIndex` is the field's index into
   * `sections`, `fieldKey` the field to jump to — the same pair
   * `useSectionJump`'s `jumpToField(index, fieldKey)` (task 050, extended
   * for this task) takes, so a caller can wire this straight through.
   */
  onJump: (sectionIndex: number, fieldKey: string) => void;
}
