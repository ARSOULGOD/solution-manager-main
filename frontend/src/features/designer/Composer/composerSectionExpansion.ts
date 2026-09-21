import type { ViewSection } from '@contracts/view-model/types';

import { groupSectionFields } from '../SectionCard/sectionFieldGroups';

/**
 * Sections start expanded, except an all-advanced one — the same default
 * the reference's own `InspectorForm` uses (`designer.jsx`'s
 * `new Set(comp.sections.filter(s => !s.allAdvanced)...)`), so a
 * multi-component instance's fields are visible without an extra click
 * per section, same as the flat single-component form.
 */
export function defaultExpandedSections(sections: readonly ViewSection[]): ReadonlySet<number> {
  const expanded = new Set<number>();
  sections.forEach((section, index) => {
    if (!groupSectionFields(section).allAdvanced) {
      expanded.add(index);
    }
  });
  return expanded;
}
