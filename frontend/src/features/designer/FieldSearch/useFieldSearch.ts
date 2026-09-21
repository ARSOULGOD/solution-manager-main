/**
 * The filtering behind `FieldSearch` (task 051, design §10/§17, UI §A.8.8):
 * "matches label, key, rendered path and section across all components."
 * Split from the presentational component the same way `useSectionJump`
 * (task 050) is split from `SectionNav` — `query` is owned here so a future
 * page that assembles search alongside the normal section list (in place
 * of it, per the reference `designer.jsx`'s `{search ? <SearchResults/> :
 * ...}` toggle) can read `isActive` without reaching into `FieldSearch`'s
 * internals.
 *
 * No debounce lives here: `FieldSearch` commits `query` through
 * `SearchField` (shared/ui, `FE-025`, design §17), which already debounces
 * free text. Debouncing twice would just add latency.
 */
import { useMemo, useState } from 'react';

import type { ViewField, ViewSection } from '@contracts/view-model/types';

export interface FieldSearchHit {
  field: ViewField;
  sectionTitle: string;
}

export interface UseFieldSearchResult {
  query: string;
  setQuery: (query: string) => void;
  /** Every declared field across every section — the reference's own `Search N fields…` count, not the applicable-only one (`sectionFieldGroups`'s count is a different, narrower thing). */
  totalFieldCount: number;
  /** `false` for an empty query — the reference renders the normal section list instead of a results panel in that state, never an empty results panel. */
  isActive: boolean;
  /** Applicable fields only (`field.visible`) — a hidden branch is never a hit, even if its label or key matches (`UI-D4`). */
  hits: readonly FieldSearchHit[];
}

export function useFieldSearch(sections: readonly ViewSection[]): UseFieldSearchResult {
  const [query, setQuery] = useState('');
  const isActive = query !== '';

  const totalFieldCount = useMemo(
    () => sections.reduce((total, section) => total + section.fields.length, 0),
    [sections],
  );

  const hits = useMemo(() => {
    if (!isActive) {
      return [];
    }
    const needle = query.toLowerCase();
    const matches: FieldSearchHit[] = [];
    for (const section of sections) {
      for (const field of section.fields) {
        if (!field.visible) {
          continue;
        }
        const haystack = `${field.label} ${field.key} ${field.renderedPath ?? ''} ${section.title}`.toLowerCase();
        if (haystack.includes(needle)) {
          matches.push({ field, sectionTitle: section.title });
        }
      }
    }
    return matches;
  }, [sections, query, isActive]);

  return { query, setQuery, totalFieldCount, isActive, hits };
}
