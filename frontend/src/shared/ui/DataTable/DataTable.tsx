/**
 * Inventory/replicas/assignments table on MUI X Data Grid **Community**
 * (FE-020, FE-D11). Sorting, quick filter, column visibility, density,
 * checkbox selection, CSV, virtualization. No pinning, no row grouping,
 * no Excel export.
 *
 * **A declared `ColumnDef.width` is a FLOOR, not a fixed width** (FE-119).
 * It maps to `minWidth` plus a proportional `flex` share — see the
 * `gridColumns` memo for why setting `width` and `flex` together silently
 * discarded every declared width. At enough columns the floors exceed the
 * viewport and the grid scrolls horizontally; that is the intended
 * outcome, not a regression — the alternative is nine columns at identical
 * width with their contents truncated.
 *
 * Expandable detail is the Community pattern: a real expander button
 * (`aria-expanded`) plus a custom `slots.row` panel. Master-detail
 * `getDetailPanelContent` is Pro-only (verified against
 * https://mui.com/x/react-data-grid/master-detail/ 2026-08-20).
 */
import {
  Box,
  Button as MuiButton,
  Checkbox,
  ListItemText,
  ListSubheader,
  Menu as MuiMenu,
  MenuItem,
  TextField as MuiTextField,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import {
  COMPACT_DENSITY_FACTOR,
  DataGrid,
  useGridApiRef,
  type GridColDef,
  type GridRenderCellParams,
  type GridRowId,
  type GridRowSelectionModel,
  type GridSortModel,
} from '@mui/x-data-grid';
import { useEffect, useMemo, useState } from 'react';

import { t } from '@shared/i18n';

import { IconButton } from '../Button';
import { InfoTip } from '../InfoTip';

import { CardView } from './CardView';
import type { ColumnDef, DataTableProps, TableDensity } from './DataTable.types';

function sortKey(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
}

function compareCellValues(a: unknown, b: unknown): number {
  if (typeof a === 'number' && typeof b === 'number') {
    return a - b;
  }
  return sortKey(a).localeCompare(sortKey(b));
}

// The grid sorts its own rows internally (`sortingMode="client"`, the
// Community default) regardless of who owns `sortModel` — this mirrors
// that ordering for the card view, which has no grid to do it for. Not
// required to tie-break identically; only to keep the two views showing
// the same order (`FE-D29`: "sort... survive[s] the transform").
function applySort<R>(
  rows: readonly R[],
  sortModel: GridSortModel,
  columns: readonly ColumnDef<R>[],
): R[] {
  const [primary] = sortModel;
  if (primary?.sort == null) {
    return [...rows];
  }
  const column = columns.find((candidate) => candidate.field === primary.field);
  const getValue = (row: R): unknown =>
    column?.valueGetter?.(row) ?? (row as Record<string, unknown>)[primary.field];
  const sorted = [...rows].sort((a, b) => compareCellValues(getValue(a), getValue(b)));
  return primary.sort === 'desc' ? sorted.reverse() : sorted;
}

/**
 * Set membership across the id types the grid mixes. `GridRowId` is
 * `string | number`, and a grid whose `getRowId` returns numbers holds
 * numeric ids while this component works in strings throughout — so
 * `Set.has()` alone would miss every numeric id.
 */
function hasId(ids: Set<GridRowId> | undefined, id: string): boolean {
  if (ids === undefined) {
    return false;
  }
  for (const candidate of ids) {
    if (String(candidate) === id) {
      return true;
    }
  }
  return false;
}

export function DataTable<R>({
  rows,
  columns,
  getRowId,
  selection,
  onSelectionChange,
  detailPanel,
  focusRowId,
  csvFileName = 'export.csv',
  empty,
  density = 'standard',
  toolbarExtras,
  bulkActions,
}: DataTableProps<R>) {
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(
    () => new Set(focusRowId !== undefined ? [focusRowId] : []),
  );
  const [query, setQuery] = useState('');
  const [densityState, setDensityState] = useState<TableDensity>(density);
  const [hiddenFields, setHiddenFields] = useState<ReadonlySet<string>>(new Set());
  const [columnsMenuAnchor, setColumnsMenuAnchor] = useState<HTMLElement | null>(null);
  const [sortModel, setSortModel] = useState<GridSortModel>([]);
  const apiRef = useGridApiRef();
  const theme = useTheme();
  // Below `md` the 9-column grid stops being a conformance exception a
  // human can use — SC 1.4.10 exempts data tables, not readability
  // (task 107, `FE-D29`). The same row model renders as cards instead.
  const isCardView = useMediaQuery(theme.breakpoints.down('md'));

  function toggleExpanded(id: string): void {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  function toggleRowSelection(id: string): void {
    if (onSelectionChange === undefined) {
      return;
    }
    const current = selection ?? [];
    onSelectionChange(
      current.includes(id) ? current.filter((candidate) => candidate !== id) : [...current, id],
    );
  }

  // A `?focus=` deep link (task 042) must land on the row, not merely
  // highlight it — expand its detail panel on arrival. Adjusted during
  // render (react.dev's "adjusting state when a prop changes" pattern),
  // not in an effect, so it stays real, user-collapsible state rather
  // than a value re-derived every render.
  const [prevFocusRowId, setPrevFocusRowId] = useState(focusRowId);
  if (focusRowId !== prevFocusRowId) {
    setPrevFocusRowId(focusRowId);
    if (focusRowId !== undefined && !expanded.has(focusRowId)) {
      setExpanded(new Set(expanded).add(focusRowId));
    }
  }

  // DOM focus is a genuine imperative side effect on the grid's own API —
  // it also scrolls the cell into view. `setCellFocus` throws for a
  // row/column no longer present (a stale deep link); that degrades to
  // "no focus move," never a crash.
  const firstField = columns[0]?.field;
  useEffect(() => {
    if (focusRowId === undefined || firstField === undefined) {
      return;
    }
    try {
      apiRef.current?.setCellFocus(focusRowId, firstField);
    } catch {
      // Stale deep link — row/column no longer exists.
    }
  }, [focusRowId, firstField, apiRef]);

  const filtered = useMemo(() => {
    if (query.trim() === '') {
      return [...rows];
    }
    const needle = query.trim().toLowerCase();
    return rows.filter((row) => JSON.stringify(row).toLowerCase().includes(needle));
  }, [rows, query]);

  /**
   * The RENDERED row height, per density (FE-119). Three cells in the
   * instance grid draw two lines — `InstanceCell`, `healthCell`,
   * `versionCell` — and clip at MUI's compact default of 36px.
   *
   * ⚠️ `density` SCALES `rowHeight`; it does not replace it. The docs say
   * so obliquely ("Density is calculated based on the `rowHeight` and/or
   * `columnHeaderHeight` props, when present" —
   * https://mui.com/x/react-data-grid/row-height/, 2026-08-26) without
   * publishing the factor, and the library applies
   * `Math.floor(rowHeight * factor)`. Found in a browser: passing a flat
   * `44` rendered **30px** compact rows, worse than the default it was
   * meant to fix.
   *
   * So the target is divided by the library's OWN exported factor rather
   * than by a number copied out of a measurement — `COMPACT_DENSITY_FACTOR`
   * is public API, so a future change to it moves this with it instead of
   * silently re-breaking the clipping.
   */
  const TARGET_ROW_HEIGHT: Readonly<Record<TableDensity, number>> = {
    standard: 64,
    // 52, not the 44 first tried: the instance grid's Config cell wraps
    // two chips onto a second row and measured 50px in a browser, so 44
    // clipped it. Sized to the tallest cell content the grid actually
    // draws, measured — not to a round number.
    compact: 52,
  };
  const rowHeight =
    densityState === 'compact'
      ? Math.ceil(TARGET_ROW_HEIGHT.compact / COMPACT_DENSITY_FACTOR)
      : TARGET_ROW_HEIGHT.standard;

  const gridColumns: GridColDef[] = useMemo(() => {
    const expandCol: GridColDef | undefined =
      detailPanel !== undefined
        ? {
            field: '__detail__',
            headerName: '',
            width: 56,
            sortable: false,
            filterable: false,
            disableColumnMenu: true,
            // An empty header cell fails axe's `empty-table-header` (no
            // text a screen reader can announce for this column) — found
            // by the first consumer to run axe against a populated,
            // `detailPanel`-bearing table (task 041). Visually hidden,
            // not `headerName`, so the 56px expander column stays icon-
            // only for a sighted user.
            renderHeader: () => (
              <Box
                component="span"
                sx={{
                  position: 'absolute',
                  width: 1,
                  height: 1,
                  overflow: 'hidden',
                  clip: 'rect(0 0 0 0)',
                  whiteSpace: 'nowrap',
                }}
              >
                {t('expandRow')}
              </Box>
            ),
            renderCell: (params: GridRenderCellParams) => {
              const id = String(params.id);
              const isOpen = expanded.has(id);
              return (
                <IconButton
                  aria-label={isOpen ? t('collapseRow') : t('expandRow')}
                  aria-expanded={isOpen}
                  size="sm"
                  onClick={(event) => {
                    event.stopPropagation();
                    toggleExpanded(id);
                  }}
                >
                  <span aria-hidden>{isOpen ? '−' : '+'}</span>
                </IconButton>
              );
            },
          }
        : undefined;

    const dataCols = columns.map((column) => {
      // ⚠️ `width` and `flex` DO NOT COMPOSE. MUI X's own column-dimensions
      // page states it outright: "flex doesn't work together with width. If
      // you set both flex and width in GridColDef, flex will override
      // width." (verified against
      // https://mui.com/x/react-data-grid/column-dimensions/ 2026-08-26).
      //
      // This component used to set BOTH, so every declared width — the
      // 90/170/220px in `columns.tsx` — was silently discarded and `flex: 1`
      // rendered nine columns at identical width. That is the whole reason
      // the instance grid looked congested (FE-119).
      //
      // The fix expresses the declared width TWICE, in the two properties
      // that actually compose: `minWidth` (a floor — the same doc's
      // "minWidth and maxWidth" for flex columns; the default floor is
      // 50px) and `flex` as a proportional SHARE of leftover space. `/100`
      // is a scale, not a magic number: it turns "220px" into "2.2 shares"
      // so a wide column stays proportionally wide when there is room to
      // spare, and never narrower than declared when there is not.
      const base = column.width ?? 160;
      const def: GridColDef = {
        field: column.field,
        headerName: column.headerName,
        minWidth: base,
        flex: base / 100,
        renderHeader: () => (
          <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
            {column.headerName}
            {column.info !== undefined && <InfoTip tip={column.info} />}
          </Box>
        ),
      };
      if (column.valueGetter !== undefined) {
        const getter = column.valueGetter;
        def.valueGetter = (_value, row) => getter(row as R);
      }
      if (column.renderCell !== undefined) {
        const renderer = column.renderCell;
        def.renderCell = (params: GridRenderCellParams) => renderer(params.row as R);
      }
      return def;
    });

    return expandCol !== undefined ? [expandCol, ...dataCols] : dataCols;
  }, [columns, detailPanel, expanded]);

  const gridRows = filtered.map((row) => ({ ...row, id: getRowId(row) }));

  // The card view has no column headers to hide behind, so `hiddenFields`
  // (the Columns menu, task 020) applies directly to which fields render.
  const visibleColumns = columns.filter((column) => !hiddenFields.has(column.field));
  // The grid sorts `gridRows` itself once `sortModel` changes; this
  // mirrors that order for the card view (see `applySort`'s header).
  const cardRows = applySort(filtered, sortModel, columns);

  const hasBulkActions = bulkActions !== undefined && bulkActions !== false && bulkActions !== null;

  function exportCsv(): void {
    const header = columns.map((column) => column.headerName).join(',');
    const body = filtered
      .map((row) =>
        columns
          .map((column) => {
            const raw = column.valueGetter?.(row) ?? (row as Record<string, unknown>)[column.field];
            const value =
              typeof raw === 'string' || typeof raw === 'number' || typeof raw === 'boolean'
                ? String(raw)
                : '';
            return `"${value.replaceAll('"', '""')}"`;
          })
          .join(','),
      )
      .join('\n');
    const blob = new Blob([`${header}\n${body}`], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = csvFileName;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  return (
    <Box data-data-table sx={{ minWidth: 0, width: '100%' }}>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mb: 1, alignItems: 'center' }}>
        <MuiTextField
          size="small"
          label={t('tableSearch')}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        {toolbarExtras}
        {!isCardView && hasBulkActions && bulkActions}
        <Box sx={{ flex: 1 }} />
        <MuiButton onClick={(event) => setColumnsMenuAnchor(event.currentTarget)}>
          {t('tableColumns')}
        </MuiButton>
        <MuiMenu
          anchorEl={columnsMenuAnchor}
          open={columnsMenuAnchor !== null}
          onClose={() => setColumnsMenuAnchor(null)}
        >
          <ListSubheader disableSticky>{t('columnVisibility')}</ListSubheader>
          {columns
            .filter((column) => column.hideable !== false)
            .map((column) => {
              const checked = !hiddenFields.has(column.field);
              return (
                <MenuItem
                  key={column.field}
                  role="menuitemcheckbox"
                  aria-checked={checked}
                  onClick={() =>
                    setHiddenFields((prev) => {
                      const next = new Set(prev);
                      if (checked) {
                        next.add(column.field);
                      } else {
                        next.delete(column.field);
                      }
                      return next;
                    })
                  }
                >
                  <Checkbox checked={checked} size="small" />
                  <ListItemText primary={column.headerName} />
                </MenuItem>
              );
            })}
        </MuiMenu>
        {!isCardView && (
          <MuiTextField
            select
            size="small"
            label={t('density')}
            value={densityState}
            onChange={(event) => setDensityState(event.target.value as TableDensity)}
            sx={{ minWidth: 140 }}
          >
            <MenuItem value="standard">{t('densityStandard')}</MenuItem>
            <MenuItem value="compact">{t('densityCompact')}</MenuItem>
          </MuiTextField>
        )}
        <MuiButton onClick={exportCsv}>{t('csvExport')}</MuiButton>
        <Box sx={{ ml: 'auto' }} data-table-footer>
          {selection !== undefined && t('tableSelectedCount', { count: selection.length })}
          {' · '}
          {t('tableRowCount', { shown: filtered.length, total: rows.length })}
        </Box>
      </Box>
      {isCardView ? (
        <CardView
          rows={cardRows}
          columns={visibleColumns}
          getRowId={getRowId}
          selection={selection}
          onToggleRow={onSelectionChange !== undefined ? toggleRowSelection : undefined}
          detailPanel={detailPanel}
          expanded={expanded}
          onToggleExpand={toggleExpanded}
          focusRowId={focusRowId}
          empty={empty}
        />
      ) : (
        <>
          {/* FE-110/`FE-D29`: a floor-exceeding column set scrolling
              horizontally inside the grid, rather than the page, is this
              file's own declared intent (see the header comment). The
              attribute is the CI reflow gate's allowlist entry for it —
              without it a real scroller here fails the build as
              undeclared. */}
          <Box data-reflow-exception="data-table">
            <DataGrid
              apiRef={apiRef}
              rows={gridRows}
              columns={gridColumns}
              checkboxSelection={onSelectionChange !== undefined}
              disableRowSelectionOnClick
              density={densityState}
              rowHeight={rowHeight}
              columnVisibilityModel={Object.fromEntries(
                [...hiddenFields].map((field) => [field, false]),
              )}
              sortModel={sortModel}
              onSortModelChange={setSortModel}
              disableVirtualization={import.meta.env.MODE === 'test'}
              autoHeight
              hideFooterSelectedRowCount
              {...(empty !== undefined ? { slots: { noRowsOverlay: () => <>{empty}</> } } : {})}
              {...(selection !== undefined
                ? {
                    rowSelectionModel: { type: 'include' as const, ids: new Set(selection) },
                  }
                : {})}
              {...(onSelectionChange !== undefined
                ? {
                    onRowSelectionModelChange: (model: GridRowSelectionModel | GridRowId[]) => {
                      const visibleIds = new Set(gridRows.map((row) => String(row.id)));

                      // `type` is NOT decoration. Select-all emits
                      // `{ type: 'exclude', ids }` — "every row EXCEPT these" —
                      // where a row click emits `{ type: 'include', ids }`
                      // (`GridRowSelectionModel`, @mui/x-data-grid 9.11.0).
                      // Reading `ids` alone makes select-all arrive as an empty
                      // set, i.e. "select nothing", which is precisely how this
                      // silently cleared the selection instead of filling it.
                      const incoming = Array.isArray(model)
                        ? model.map(String)
                        : model.type === 'exclude'
                          ? [...visibleIds].filter((id) => !hasId(model.ids, id))
                          : Array.from(model.ids ?? [], String);

                      // The grid prunes a selected id the moment its row leaves
                      // `rows` — including when the *caller* narrowed `rows`
                      // (e.g. an env filter), not just a real deselection. An
                      // id for a row currently outside `gridRows` is restored
                      // here rather than silently lost; unchecking a row that
                      // IS visible still removes it normally, since `incoming`
                      // reflects that directly. This is also why `exclude`
                      // expands over the VISIBLE rows only: select-all means
                      // "all of what I can see", never "all of what was
                      // filtered away too".
                      const preserved = (selection ?? []).filter((id) => !visibleIds.has(id));
                      onSelectionChange([...new Set([...incoming, ...preserved])]);
                    },
                  }
                : {})}
              getRowClassName={(params) => (params.id === focusRowId ? 'sm-row-focus' : '')}
              sx={{
                '& .sm-row-focus': { outline: '2px solid', outlineColor: 'primary.main' },
                // ⚠️ THE ACTUAL CAUSE of two-line cells clipping (FE-119), and
                // it is not row height. MUI centres single-line cell text by
                // setting `line-height` on the cell equal to its content
                // height, so an inline first child generates a line box as
                // tall as the whole row and pushes any second line out of an
                // `overflow: hidden` cell. Measured in a browser:
                // `InstanceCell`'s two 16px lines occupied 79px inside a 64px
                // cell, with "Acme Financial / Payments" starting exactly at the
                // cell's bottom edge. Raising `rowHeight` cannot fix it —
                // the line-height scales with it.
                //
                // `flex` + `align-items: center` centres on the BOX rather
                // than on a line box, which is what lets `line-height` go
                // back to normal and each `Typography` use its own.
                '& .MuiDataGrid-cell': {
                  display: 'flex',
                  alignItems: 'center',
                  lineHeight: 'normal',
                },
              }}
            />
          </Box>
          {detailPanel !== undefined &&
            [...expanded].map((id) => {
              const row = rows.find((candidate) => getRowId(candidate) === id);
              if (row === undefined) {
                return null;
              }
              return (
                <Box
                  key={id}
                  role="region"
                  aria-label={t('expandRow')}
                  data-detail-panel
                  sx={{ px: 3, py: 2, borderBottom: 1, borderColor: 'divider' }}
                >
                  {detailPanel(row)}
                </Box>
              );
            })}
        </>
      )}
      {isCardView && hasBulkActions && (
        <Box
          data-bulk-actions-bar
          sx={{
            position: 'sticky',
            bottom: 0,
            mt: 1.5,
            py: 1,
            px: 1.5,
            display: 'flex',
            justifyContent: 'flex-end',
            backgroundColor: 'background.paper',
            borderTop: '1px solid',
            borderColor: 'divider',
          }}
        >
          {bulkActions}
        </Box>
      )}
    </Box>
  );
}
