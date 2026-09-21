import type { FieldValue, ViewField } from '@contracts/view-model/types';

export interface FieldRowProps {
  field: ViewField;
  /**
   * The currently-edited value — distinct from `field.value`, which is
   * the value as of the last `project()` call (`FE-D17`: `project()` only
   * re-runs on structural changes, e.g. a discriminator, never on every
   * keystroke). Comparing the two is how `Modified` is detected; comparing
   * `field.value`'s own `source` is how `Default` is detected. Neither can
   * be derived from the other alone.
   */
  value: FieldValue;
  onChange: (value: FieldValue) => void;
  disabled?: boolean;
  /** UI §A.8.4: shown only for single-component solutions. */
  showRenderedPath?: boolean;
}
