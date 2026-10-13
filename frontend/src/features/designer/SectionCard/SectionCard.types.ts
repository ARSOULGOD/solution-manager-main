import type { FieldValue, ViewSection } from '@contracts/view-model/types';

export interface SectionCardProps {
  section: ViewSection;
  /** `fieldKey -> currently-edited value`, the same distinction `FieldRow.value` draws against `field.value` (`FE-D17`). */
  values: Readonly<Record<string, FieldValue>>;
  onFieldChange: (key: string, value: FieldValue) => void;
  disabled?: boolean;
  /** Card-level accordion state — owned by the caller so a future section-nav rail (task 050) can drive several cards at once. */
  expanded: boolean;
  onToggleExpanded: () => void;
  /** UI §A.8.4: shown only for single-component solutions. */
  showRenderedPath?: boolean;
  /** `fieldKey -> (option id -> display label)`, for select fields whose options are ids (connection selectors). */
  optionLabelsByField?: Readonly<Record<string, Readonly<Record<string, string>>>>;
}
