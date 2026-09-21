/**
 * Four callers, one component: draft-vs-deployed, 409 mine-vs-theirs,
 * rollback current-vs-target, accept-runtime re-render (FE-022).
 *
 * Secret rows render masked on both sides and carry a `secret · masked`
 * tag. The props type makes `old`/`new` for a secret row a literal mask
 * token, not a string — a real value is a type error (I6).
 *
 * **Stacked mode** (task 108, `FE-D29`): below `sm` the two-column table
 * becomes a list of cards, one per row, each value labelled by its own
 * side (`labels[0]`/`labels[1]`) rather than relying on column position —
 * `cellValue()` is the one function both layouts call, so a secret row
 * renders masked (`I6`) identically in either.
 */
import { Box, Table, TableBody, TableCell, TableHead, TableRow, Typography, useMediaQuery, useTheme } from '@mui/material';

import { t } from '@shared/i18n';

import { Chip } from '../Chip';

import { type DiffRow, type DiffViewProps } from './DiffView.types';

const MASK_DISPLAY = '••••••••';

function cellValue(row: DiffRow, side: 'old' | 'new'): string {
  if (row.secret === true) {
    return MASK_DISPLAY;
  }
  const value = row[side];
  return value ?? '—';
}

function RowMeta({ row }: { row: DiffRow }) {
  return (
    <>
      {row.kind === 'rename' && (
        <Typography variant="caption" color="warning.main" sx={{ display: 'block' }}>
          {t('diffRenameNeedsMapping')}
        </Typography>
      )}
      {row.secret === true && (
        <Box sx={{ mt: 0.5 }}>
          <Chip label={t('secretMaskedTag')} tone="neutral" />
        </Box>
      )}
    </>
  );
}

function StackedDiffView({ rows, labels }: { rows: readonly DiffRow[]; labels: readonly [string, string] }) {
  const [left, right] = labels;

  return (
    <Box data-diff-view data-diff-stacked sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      {rows.map((row) => (
        <Box
          key={`${row.kind}:${row.path}`}
          data-diff-kind={row.kind}
          sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1.5 }}
        >
          <Typography variant="body2" sx={{ fontFamily: 'JetBrains Mono, ui-monospace, monospace', wordBreak: 'break-all' }}>
            {row.path}
          </Typography>
          <RowMeta row={row} />

          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.75, mt: 1 }}>
            {([
              [left, cellValue(row, 'old')],
              [right, cellValue(row, 'new')],
            ] as const).map(([sideLabel, sideValue], index) => (
              <Box key={sideLabel} sx={{ display: 'flex', justifyContent: 'space-between', gap: 1 }}>
                <Typography variant="caption" color="text.secondary">
                  {sideLabel}
                </Typography>
                <Typography
                  variant="body2"
                  data-diff-side={index === 0 ? 'old' : 'new'}
                  sx={{ textAlign: 'right', wordBreak: 'break-all' }}
                >
                  {sideValue}
                </Typography>
              </Box>
            ))}
          </Box>
        </Box>
      ))}
    </Box>
  );
}

export function DiffView({ rows, labels = ['Old', 'New'] }: DiffViewProps) {
  const [left, right] = labels;
  const theme = useTheme();
  const stacked = useMediaQuery(theme.breakpoints.down('sm'));

  if (stacked) {
    return <StackedDiffView rows={rows} labels={labels} />;
  }

  return (
    <Table size="small" data-diff-view>
      <TableHead>
        <TableRow>
          <TableCell>Path</TableCell>
          <TableCell>{left}</TableCell>
          <TableCell>{right}</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={`${row.kind}:${row.path}`} data-diff-kind={row.kind}>
            <TableCell>
              <Typography
                variant="body2"
                sx={{ fontFamily: 'JetBrains Mono, ui-monospace, monospace' }}
              >
                {row.path}
              </Typography>
              <RowMeta row={row} />
            </TableCell>
            <TableCell data-diff-side="old">{cellValue(row, 'old')}</TableCell>
            <TableCell data-diff-side="new">{cellValue(row, 'new')}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
