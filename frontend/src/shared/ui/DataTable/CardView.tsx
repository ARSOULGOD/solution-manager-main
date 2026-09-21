/**
 * Table-to-card transform (task 107, `FE-D29`, `FE-D11`, `UI-D9`). Below
 * `md` a 9-column grid stops being a conformance exception a human can use
 * (WCAG 2.2 SC 1.4.10 exempts data tables, not readability), so `DataTable`
 * swaps its `DataGrid` for this component instead.
 *
 * **This is a presentation of the same row model, nothing more.** Every
 * cell comes from the SAME `ColumnDef.renderCell`/`valueGetter` the grid
 * uses — a card never re-derives a value or re-implements a lookup, so a
 * state cannot drift between the two views (`REQ-005`, `REQ-008`). The
 * only branch here is on column *metadata* (`cardEmphasis`), never on a
 * column's *value*.
 *
 * Selection, expansion and focus are lifted state owned by `DataTable` —
 * this component is a pure function of `rows`/`expanded`/`selection` plus
 * the callbacks to change them, so switching views mid-session (a resize)
 * never loses any of the three.
 */
import { Box, Checkbox, Divider, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { useEffect, useRef } from 'react';

import { t } from '@shared/i18n';

import { IconButton } from '../Button';

import type { ColumnDef } from './DataTable.types';

export interface CardViewProps<R> {
  rows: readonly R[];
  columns: readonly ColumnDef<R>[];
  getRowId: (row: R) => string;
  selection?: readonly string[] | undefined;
  onToggleRow?: ((id: string) => void) | undefined;
  detailPanel?: ((row: R) => ReactNode) | undefined;
  expanded: ReadonlySet<string>;
  onToggleExpand: (id: string) => void;
  focusRowId?: string | undefined;
  empty?: ReactNode;
}

function cellValue<R>(column: ColumnDef<R>, row: R): ReactNode {
  if (column.renderCell !== undefined) {
    return column.renderCell(row);
  }
  if (column.valueGetter !== undefined) {
    return column.valueGetter(row);
  }
  const raw = (row as Record<string, unknown>)[column.field];
  return typeof raw === 'string' || typeof raw === 'number' ? raw : null;
}

function primaryLabelText<R>(column: ColumnDef<R>, row: R, fallback: string): string {
  const fromGetter = column.valueGetter?.(row);
  if (fromGetter !== undefined) {
    return fromGetter;
  }
  const raw = (row as Record<string, unknown>)[column.field];
  return typeof raw === 'string' ? raw : fallback;
}

export function CardView<R>({
  rows,
  columns,
  getRowId,
  selection,
  onToggleRow,
  detailPanel,
  expanded,
  onToggleExpand,
  focusRowId,
  empty,
}: CardViewProps<R>) {
  const primaryColumn = columns.find((column) => column.cardEmphasis === 'primary') ?? columns[0];
  const chipColumns = columns.filter(
    (column) => column !== primaryColumn && column.cardEmphasis === 'chip',
  );
  const detailColumns = columns.filter(
    (column) => column !== primaryColumn && column.cardEmphasis !== 'chip',
  );

  const itemRefs = useRef(new Map<string, HTMLLIElement>());
  useEffect(() => {
    if (focusRowId === undefined) {
      return;
    }
    itemRefs.current.get(focusRowId)?.focus();
  }, [focusRowId]);

  if (rows.length === 0) {
    return <>{empty ?? null}</>;
  }

  return (
    <Box
      component="ul"
      role="list"
      aria-label={t('tableCardListLabel')}
      data-card-view
      sx={{ listStyle: 'none', m: 0, p: 0, display: 'flex', flexDirection: 'column', gap: 1.5 }}
    >
      {rows.map((row) => {
        const id = getRowId(row);
        const isSelected = selection?.includes(id) ?? false;
        const isExpanded = expanded.has(id);
        const label = primaryColumn !== undefined ? primaryLabelText(primaryColumn, row, id) : id;

        return (
          <Box
            key={id}
            component="li"
            role="listitem"
            data-card-row={id}
            tabIndex={-1}
            ref={(el: HTMLLIElement | null) => {
              if (el) {
                itemRefs.current.set(id, el);
              } else {
                itemRefs.current.delete(id);
              }
            }}
            sx={{
              minWidth: 0,
              border: '1px solid',
              borderColor: id === focusRowId ? 'primary.main' : 'divider',
              borderRadius: 1.5,
              p: 1.5,
              outline: id === focusRowId ? '2px solid' : 'none',
              outlineColor: 'primary.main',
              outlineOffset: 2,
            }}
          >
            <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1 }}>
              {onToggleRow !== undefined && (
                <Checkbox
                  size="small"
                  checked={isSelected}
                  onChange={() => onToggleRow(id)}
                  slotProps={{ input: { 'aria-label': t('tableSelectRow', { label }) } }}
                  sx={{ mt: -0.5 }}
                />
              )}
              <Box sx={{ minWidth: 0, flex: 1 }}>
                {primaryColumn !== undefined && cellValue(primaryColumn, row)}
              </Box>
              {detailPanel !== undefined && (
                <IconButton
                  aria-label={isExpanded ? t('collapseRow') : t('expandRow')}
                  aria-expanded={isExpanded}
                  size="sm"
                  onClick={() => onToggleExpand(id)}
                >
                  <span aria-hidden>{isExpanded ? '−' : '+'}</span>
                </IconButton>
              )}
            </Box>

            {chipColumns.length > 0 && (
              <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mt: 1 }}>
                {chipColumns.map((column) => (
                  <Box key={column.field} sx={{ minWidth: 0 }}>
                    {cellValue(column, row)}
                  </Box>
                ))}
              </Box>
            )}

            {detailColumns.length > 0 && (
              <Box
                sx={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))',
                  gap: 1,
                  mt: 1.5,
                }}
              >
                {detailColumns.map((column) => (
                  <Box key={column.field} sx={{ minWidth: 0 }}>
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                      {column.headerName}
                    </Typography>
                    <Box sx={{ typography: 'body2' }}>{cellValue(column, row)}</Box>
                  </Box>
                ))}
              </Box>
            )}

            {detailPanel !== undefined && isExpanded && (
              <>
                <Divider sx={{ my: 1.5 }} />
                <Box role="region" aria-label={t('expandRow')} data-detail-panel>
                  {detailPanel(row)}
                </Box>
              </>
            )}
          </Box>
        );
      })}
    </Box>
  );
}
