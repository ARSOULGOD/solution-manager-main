export type SecretFieldState = { kind: 'unset' } | { kind: 'set'; ref: string };

export interface SecretFieldProps {
  state: SecretFieldState;
  /**
   * Emits a set-secret intent. The value is write-only — callers must not
   * persist it into draft display, diffs, or history.
   * API CONTRACT — TBD (FE-023 Ready: FIXTURE).
   */
  onSet: (value: string) => void;
  onClear: () => void;
  disabled?: boolean;
}
