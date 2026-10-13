import type { FieldDeclaration, FieldValue } from '@contracts/view-model/types';

/** `fieldKey -> value`, for one table row. */
export type TableRow = Readonly<Record<string, FieldValue>>;

export interface TableControlProps {
  label: string;
  /** Column declarations from the package (`UI-D22`) — each a scalar `FieldDeclaration`. */
  columns?: readonly FieldDeclaration[] | undefined;
  rows: readonly TableRow[];
  onChange: (rows: readonly TableRow[]) => void;
  disabled?: boolean;
  helperText?: string;
  error?: string;
}
