/**
 * The applicable/advanced/block/warn arithmetic every section-level view
 * shares (task 049's `SectionCard`, task 050's `SectionNav`) — pulled out
 * so the two never compute it differently. "Applicable" means
 * `field.visible`; a hidden branch (`when:` false) counts toward neither
 * the field count nor the block/warn tally, even though its value is
 * still retained in the draft (`UI-D4`).
 */
import type { ViewField, ViewSection } from '@contracts/view-model/types';

export interface SectionFieldGroups {
  applicable: readonly ViewField[];
  /** At least one applicable field, and every applicable field is `advanced` (`ViewSection` itself declares no such flag — this is derived, not package-authored). */
  allAdvanced: boolean;
  normal: readonly ViewField[];
  advanced: readonly ViewField[];
  /** Applicable, enabled fields carrying a `bind`-tier validation. */
  blockCount: number;
  /** Applicable, enabled fields carrying a `semantic`-tier validation and no `bind` one. */
  warnCount: number;
}

function hasTier(field: ViewField, tier: 'bind' | 'semantic'): boolean {
  return field.validations.some((validation) => validation.tier === tier);
}

export function groupSectionFields(section: ViewSection): SectionFieldGroups {
  const applicable = section.fields.filter((field) => field.visible);
  const allAdvanced = applicable.length > 0 && applicable.every((field) => field.advanced);
  const normal = allAdvanced ? applicable : applicable.filter((field) => !field.advanced);
  const advanced = allAdvanced ? [] : applicable.filter((field) => field.advanced);

  let blockCount = 0;
  let warnCount = 0;
  for (const field of applicable) {
    if (!field.enabled) {
      continue;
    }
    if (hasTier(field, 'bind')) {
      blockCount += 1;
    } else if (hasTier(field, 'semantic')) {
      warnCount += 1;
    }
  }

  return { applicable, allAdvanced, normal, advanced, blockCount, warnCount };
}
