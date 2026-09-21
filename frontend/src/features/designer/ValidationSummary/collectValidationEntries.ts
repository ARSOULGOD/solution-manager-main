/**
 * The cross-section field list `ValidationSummary` (task 054) renders as
 * chips. Reuses `groupSectionFields`'s own `applicable` (visible) filter
 * and its enabled/tier rule verbatim (task 049) so this summary's counts
 * can never drift from `SectionCard`'s and `SectionNav`'s own block/warn
 * chips and dots — the same guarantee `sectionFieldGroups.ts` already makes
 * across those two.
 */
import type { ViewSection } from '@contracts/view-model/types';

import { groupSectionFields } from '../SectionCard/sectionFieldGroups';

export interface ValidationEntry {
  sectionIndex: number;
  fieldKey: string;
  label: string;
  /** The firing validation's own message — the block/warn reason, for `OverrideReasonDialog` (task 057) to show. */
  message: string;
}

export interface ValidationSummaryEntries {
  blocks: readonly ValidationEntry[];
  warns: readonly ValidationEntry[];
}

export function collectValidationEntries(
  sections: readonly ViewSection[],
): ValidationSummaryEntries {
  const blocks: ValidationEntry[] = [];
  const warns: ValidationEntry[] = [];

  sections.forEach((section, sectionIndex) => {
    const { applicable } = groupSectionFields(section);
    for (const field of applicable) {
      if (!field.enabled) {
        continue;
      }
      const bind = field.validations.find((validation) => validation.tier === 'bind');
      const semantic = field.validations.find((validation) => validation.tier === 'semantic');
      if (bind !== undefined) {
        blocks.push({
          sectionIndex,
          fieldKey: field.key,
          label: field.label,
          message: bind.message,
        });
      } else if (semantic !== undefined) {
        warns.push({
          sectionIndex,
          fieldKey: field.key,
          label: field.label,
          message: semantic.message,
        });
      }
    }
  });

  return { blocks, warns };
}
