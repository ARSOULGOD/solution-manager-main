import type { FieldValue } from '@contracts/view-model/types';

import type { FieldSearchHit } from './useFieldSearch';

export interface FieldSearchProps {
  /** Owned by `useFieldSearch` — this component is controlled, the same split `SectionNav`/`useSectionJump` use. */
  query: string;
  onQueryChange: (query: string) => void;
  totalFieldCount: number;
  hits: readonly FieldSearchHit[];
  /** `fieldKey -> currently-edited value`, the same shape `SectionCard.values` takes (`FE-D17`). */
  values: Readonly<Record<string, FieldValue>>;
  onFieldChange: (key: string, value: FieldValue) => void;
  disabled?: boolean;
}
