import type { ReactNode } from 'react';

export interface ColumnDef<R> {
  field: string;
  headerName: string;
  info?: string;
  width?: number;
  valueGetter?: (row: R) => string;
  /**
   * Custom cell visual (task 041's own need — a status cell is a `Chip`/
   * `StateChip`, never a plain string). Independent of `valueGetter`:
   * when both are given, `valueGetter` still drives sort order and the
   * CSV export cell, `renderCell` only the on-screen visual.
   */
  renderCell?: (row: R) => ReactNode;
  /** false hides this column from the Columns menu — it can never be turned off (default true). */
  hideable?: boolean;
  /**
   * Card-view placement below `md` (task 107, `FE-D29`). `'primary'` is the
   * card's headline (name/identity — expected on exactly one column);
   * `'chip'` renders in the wrapping status cluster beneath it. Omitted
   * columns fall through to the label/value list. Structural metadata
   * only — never a switch on the column's *value* (`REQ-005`, `REQ-008`).
   */
  cardEmphasis?: 'primary' | 'chip';
}

export type TableDensity = 'standard' | 'compact';

export interface DataTableProps<R> {
  rows: readonly R[];
  columns: readonly ColumnDef<R>[];
  getRowId: (row: R) => string;
  selection?: readonly string[];
  onSelectionChange?: (ids: string[]) => void;
  detailPanel?: (row: R) => ReactNode;
  focusRowId?: string | undefined;
  csvFileName?: string;
  empty?: ReactNode;
  density?: TableDensity;
  /** Extra toolbar control (e.g. an env filter) rendered next to the built-in search/density/columns controls. */
  toolbarExtras?: ReactNode;
  /**
   * Selection-dependent action(s) (e.g. bulk restart). Rendered inline in
   * the toolbar at `md` and up; below `md` it moves to a sticky bottom bar
   * (task 107) so a selection is never made invisible by scroll position.
   * Pass `undefined`/`false` when there is nothing to act on.
   */
  bulkActions?: ReactNode;
}
