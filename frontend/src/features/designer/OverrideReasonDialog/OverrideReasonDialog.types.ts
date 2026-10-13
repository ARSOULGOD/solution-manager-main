import type { ValidationEntry } from '../ValidationSummary/collectValidationEntries';

export interface OverrideReasonDialogProps {
  open: boolean;
  /** `collectValidationEntries(sections).warns` — the semantic-tier fields being overridden. Never empty when `open` (SaveBar only opens this dialog when there is at least one). */
  warnings: readonly ValidationEntry[];
  onCancel: () => void;
  /** The typed reason, already validated (`UI-D16`: recorded in configuration history alongside the change). */
  onConfirm: (reason: string) => void;
}
