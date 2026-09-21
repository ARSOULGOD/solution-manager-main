/**
 * `table` control (task 047, `UI-D22`) — an editable-rows grid whose
 * columns are declared by the package, each a scalar `FieldDeclaration`
 * (`string`/`int`/`bool`/`enum`; anything else falls back to a plain text
 * cell). Reuses the same per-scalar-type rendering the registry's other
 * entries use — `TextField`/`NumberField`/`Switch`/`Select` — rather than
 * a second, table-specific control set.
 *
 * FE-046's shared fixtures don't yet include a `table`-typed field, so
 * `columns` can be empty or absent; that renders the "declared by the
 * package" placeholder rather than a blank or broken grid.
 */
import { Box, Typography } from '@mui/material';

import type { FieldDeclaration, FieldValue } from '@contracts/view-model/types';
import { t } from '@shared/i18n';
import { Alert, Button, IconButton, NumberField, Select, Switch, TextField } from '@shared/ui';

import type { TableControlProps, TableRow } from './TableControl.types';

function renderCell(
  column: FieldDeclaration,
  row: TableRow,
  rowIndex: number,
  disabled: boolean,
  onCellChange: (key: string, value: FieldValue) => void,
) {
  const cellLabel = t('controlTableCellLabel', { column: column.key, index: rowIndex + 1 });
  const raw = row[column.key];

  switch (column.type) {
    case 'int': {
      const value = typeof raw === 'number' ? raw : '';
      return (
        <NumberField
          label={cellLabel}
          value={value}
          onChange={(next) => onCellChange(column.key, next === '' ? null : next)}
          disabled={disabled}
        />
      );
    }
    case 'bool': {
      const checked = raw === true;
      return (
        <Switch
          label={cellLabel}
          checked={checked}
          onChange={(next) => onCellChange(column.key, next)}
          disabled={disabled}
        />
      );
    }
    case 'enum': {
      const value = typeof raw === 'string' ? raw : '';
      const options = (column.options ?? []).map((option) => ({ value: option, label: option }));
      return (
        <Select
          label={cellLabel}
          value={value}
          onChange={(next) => onCellChange(column.key, next)}
          options={options}
          disabled={disabled}
        />
      );
    }
    default: {
      const value =
        typeof raw === 'string'
          ? raw
          : typeof raw === 'number' || typeof raw === 'boolean'
            ? String(raw)
            : '';
      return (
        <TextField
          label={cellLabel}
          value={value}
          onChange={(event) => onCellChange(column.key, event.target.value)}
          disabled={disabled}
        />
      );
    }
  }
}

export function TableControl({
  label,
  columns,
  rows,
  onChange,
  disabled = false,
  helperText,
  error,
}: TableControlProps) {
  const text = error ?? helperText;

  function updateCell(rowIndex: number, key: string, value: FieldValue): void {
    onChange(rows.map((row, i) => (i === rowIndex ? { ...row, [key]: value } : row)));
  }

  function removeRow(index: number): void {
    onChange(rows.filter((_, i) => i !== index));
  }

  function addRow(cols: readonly FieldDeclaration[]): void {
    const blank: Record<string, FieldValue> = {};
    for (const column of cols) {
      blank[column.key] = column.type === 'bool' ? false : null;
    }
    onChange([...rows, blank]);
  }

  return (
    <Box
      component="fieldset"
      sx={{ border: 'none', p: 0, m: 0, display: 'flex', flexDirection: 'column', gap: 1.5 }}
    >
      <Typography component="legend" variant="subtitle2">
        {label}
      </Typography>
      {text !== undefined && (
        <Typography variant="caption" color={error !== undefined ? 'error' : 'text.secondary'}>
          {text}
        </Typography>
      )}
      {columns === undefined || columns.length === 0 ? (
        <Alert severity="neutral">{t('controlTableNoColumns')}</Alert>
      ) : (
        <>
          {rows.map((row, rowIndex) => (
            <Box
              key={rowIndex}
              sx={{ display: 'flex', gap: 1, alignItems: 'flex-start', flexWrap: 'wrap' }}
            >
              {columns.map((column) => (
                <Box key={column.key}>
                  {renderCell(column, row, rowIndex, disabled, (key, value) =>
                    updateCell(rowIndex, key, value),
                  )}
                </Box>
              ))}
              <IconButton
                aria-label={t('controlTableRemoveRow', { index: rowIndex + 1 })}
                onClick={() => removeRow(rowIndex)}
                disabled={disabled}
              >
                <span aria-hidden>×</span>
              </IconButton>
            </Box>
          ))}
          <Button size="sm" variant="outlined" onClick={() => addRow(columns)} disabled={disabled}>
            {t('controlTableAddRow')}
          </Button>
        </>
      )}
    </Box>
  );
}
