import type { ChangeEventHandler, FocusEventHandler } from 'react';

import type { FieldProps } from '../internal/FieldProps';

export interface TextFieldProps extends FieldProps {
  value: string;
  onChange: ChangeEventHandler<HTMLInputElement>;
  onBlur?: FocusEventHandler<HTMLInputElement>;
  placeholder?: string;
  /** Renders a show/hide toggle and masks the value as `password` until revealed. */
  reveal?: boolean;
  autoFocus?: boolean;
  fullWidth?: boolean;
  name?: string;
  /** Standard HTML autocomplete token (e.g. `username`, `current-password`) — WCAG 2.2 SC 1.3.5. */
  autoComplete?: string;
  /**
   * Renders the value uneditable while keeping the field focusable and in
   * the accessibility tree — unlike `disabled`, which removes it from tab
   * order and reads as "temporarily unavailable" rather than "fixed". For
   * a value that is genuinely immutable (an existing group's organization,
   * which may not change after creation).
   */
  readOnly?: boolean;
}
