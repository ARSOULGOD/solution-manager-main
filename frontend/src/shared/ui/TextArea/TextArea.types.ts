import type { ChangeEventHandler } from 'react';

import type { FieldProps } from '../internal/FieldProps';

export interface TextAreaProps extends FieldProps {
  value: string;
  onChange: ChangeEventHandler<HTMLTextAreaElement>;
  minRows?: number;
  maxRows?: number;
  fullWidth?: boolean;
  name?: string;
}
