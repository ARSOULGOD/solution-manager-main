/**
 * `Switch`/`Checkbox` sit inside `FormControlLabel`, which (unlike
 * `TextField`) does not itself link a helper/error line to the control
 * with `aria-describedby` — that linkage has to be built by hand here so
 * neither wrapper can get it wrong (task 012's objective).
 */
import { useId } from 'react';

export interface DescribedByField {
  helperTextId: string | undefined;
  /** Spread onto the interactive control (`Checkbox`/`Switch`), not the label. */
  controlProps: {
    'aria-describedby': string | undefined;
    'aria-invalid': boolean | undefined;
  };
}

export function useDescribedByField(text: string | undefined, isError: boolean): DescribedByField {
  const id = useId();
  const helperTextId = text !== undefined ? `${id}-helper-text` : undefined;

  return {
    helperTextId,
    controlProps: {
      'aria-describedby': helperTextId,
      'aria-invalid': isError ? true : undefined,
    },
  };
}
