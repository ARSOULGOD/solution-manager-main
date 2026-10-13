/**
 * `map` control (task 047, `UI-D14`) — repeating key/value rows with
 * add/remove. Same fieldset/legend shape as `ListControl`; each row's key
 * and value inputs are labelled separately ("Key 1"/"Value 1", …) so a
 * screen-reader user can tell which half of the pair they're editing
 * (`G17`).
 *
 * **Below `sm`** (task 108, `FE-D29`): the key/value pair plus the remove
 * button stack instead of sitting side by side — three width-100%-
 * of-parent-ish elements in an unwrapped row overflow well before 320 px.
 */
import { Box, Typography, useMediaQuery, useTheme } from '@mui/material';

import { t } from '@shared/i18n';
import { Button, IconButton, TextField } from '@shared/ui';

import type { MapControlProps, MapEntry } from './MapControl.types';

export function MapControl({
  label,
  entries,
  onChange,
  disabled = false,
  helperText,
  error,
}: MapControlProps) {
  const theme = useTheme();
  const stackRows = useMediaQuery(theme.breakpoints.down('sm'));
  const text = error ?? helperText;

  function updateAt(index: number, next: Partial<MapEntry>): void {
    onChange(entries.map((entry, i) => (i === index ? { ...entry, ...next } : entry)));
  }

  function removeAt(index: number): void {
    onChange(entries.filter((_, i) => i !== index));
  }

  function add(): void {
    onChange([...entries, { key: '', value: '' }]);
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
      {entries.map((entry, index) => (
        <Box
          key={index}
          data-map-row
          sx={{ display: 'flex', flexDirection: stackRows ? 'column' : 'row', gap: 1, alignItems: 'flex-start' }}
        >
          <TextField
            label={t('controlMapKeyLabel', { index: index + 1 })}
            value={entry.key}
            onChange={(event) => updateAt(index, { key: event.target.value })}
            disabled={disabled}
          />
          <TextField
            label={t('controlMapValueLabel', { index: index + 1 })}
            value={entry.value}
            onChange={(event) => updateAt(index, { value: event.target.value })}
            disabled={disabled}
          />
          <IconButton
            aria-label={t('controlMapRemove', { index: index + 1 })}
            onClick={() => removeAt(index)}
            disabled={disabled}
          >
            <span aria-hidden>×</span>
          </IconButton>
        </Box>
      ))}
      <Button size="sm" variant="outlined" onClick={add} disabled={disabled}>
        {t('controlMapAdd')}
      </Button>
    </Box>
  );
}
