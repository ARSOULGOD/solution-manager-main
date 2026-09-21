/**
 * `list` control (task 047, `UI-D14`) — repeating single-value rows with
 * add/remove. Rendered as a native `fieldset`/`legend` pair so the group
 * has a real accessible name distinct from any one row's own label
 * (`G17`); each row gets its own numbered label ("Item 1", "Item 2", …)
 * rather than a bare unlabelled input, so a screen-reader user can tell
 * rows apart.
 *
 * **Below `sm`** (task 108, `FE-D29`): each row's `fullWidth` `TextField`
 * plus its remove button stack instead of sitting side by side — two
 * width-100%-of-parent elements in a row with no `flexWrap` is exactly
 * the kind of overflow SC 1.4.10 forbids at 320 px.
 */
import { Box, Typography, useMediaQuery, useTheme } from '@mui/material';

import { t } from '@shared/i18n';
import { Button, IconButton, TextField } from '@shared/ui';

import type { ListControlProps } from './ListControl.types';

export function ListControl({
  label,
  values,
  onChange,
  disabled = false,
  helperText,
  error,
}: ListControlProps) {
  const theme = useTheme();
  const stackRows = useMediaQuery(theme.breakpoints.down('sm'));
  const text = error ?? helperText;

  function updateAt(index: number, next: string): void {
    onChange(values.map((value, i) => (i === index ? next : value)));
  }

  function removeAt(index: number): void {
    onChange(values.filter((_, i) => i !== index));
  }

  function add(): void {
    onChange([...values, '']);
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
      {values.map((value, index) => (
        <Box
          key={index}
          data-list-row
          sx={{ display: 'flex', flexDirection: stackRows ? 'column' : 'row', gap: 1, alignItems: 'flex-start' }}
        >
          <TextField
            label={t('controlListItemLabel', { index: index + 1 })}
            value={value}
            onChange={(event) => updateAt(index, event.target.value)}
            disabled={disabled}
          />
          <IconButton
            aria-label={t('controlListRemove', { index: index + 1 })}
            onClick={() => removeAt(index)}
            disabled={disabled}
          >
            <span aria-hidden>×</span>
          </IconButton>
        </Box>
      ))}
      <Button size="sm" variant="outlined" onClick={add} disabled={disabled}>
        {t('controlListAdd')}
      </Button>
    </Box>
  );
}
