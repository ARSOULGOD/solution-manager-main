/**
 * The label/required/helper/error contract every form control wrapper
 * shares (task 012, design §18 "Forms"). `label` is required — never a
 * placeholder as the only label — and `error`, when present, is what
 * renders (swapping out `helperText`), so a caller cannot show both at
 * once by mistake.
 */
export interface FieldProps {
  label: string;
  required?: boolean;
  helperText?: string;
  /** The error message. Its presence, not a separate boolean, is what marks the field invalid. */
  error?: string;
  disabled?: boolean;
}

/** `helperText` swapped for `error` when the field is invalid — computed once, not per control. */
export function resolveHelperText(error: string | undefined, helperText: string | undefined) {
  return {
    text: error ?? helperText,
    isError: error !== undefined,
  };
}
