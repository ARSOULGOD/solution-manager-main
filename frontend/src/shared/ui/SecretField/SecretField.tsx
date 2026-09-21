/**
 * Write-only credential entry (FE-023, FE-D23, I6). The browser never
 * receives a resolved secret. Two states: unset and set (showing the
 * *reference*, never a value). There is no reveal affordance.
 */
import { Box, TextField as MuiTextField, Typography } from '@mui/material';
import { useState } from 'react';

import { t } from '@shared/i18n';

import { Button } from '../Button';

import type { SecretFieldProps } from './SecretField.types';

export function SecretField({ state, onSet, onClear, disabled = false }: SecretFieldProps) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');

  function cancel(): void {
    setDraft('');
    setEditing(false);
  }

  function save(): void {
    if (draft.length === 0) {
      return;
    }
    onSet(draft);
    setDraft('');
    setEditing(false);
  }

  return (
    <Box
      data-secret-field
      data-secret-kind={state.kind}
      sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}
    >
      {state.kind === 'unset' ? (
        <Typography variant="body2">{t('secretUnset')}</Typography>
      ) : (
        <>
          <Typography variant="body2">{t('secretSet')}</Typography>
          <Typography
            variant="caption"
            sx={{ fontFamily: 'JetBrains Mono, ui-monospace, monospace' }}
          >
            {state.ref}
          </Typography>
        </>
      )}

      {editing ? (
        <>
          <MuiTextField
            label={t('secretPasswordLabel')}
            type="password"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            disabled={disabled}
            autoComplete="new-password"
            fullWidth
          />
          <Typography variant="caption" color="text.secondary">
            {t('secretWriteOnlyNote')}
          </Typography>
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Button size="sm" onClick={save} disabled={disabled}>
              {t('save')}
            </Button>
            <Button size="sm" variant="text" onClick={cancel} disabled={disabled}>
              {t('cancel')}
            </Button>
          </Box>
        </>
      ) : (
        <Box sx={{ display: 'flex', gap: 1 }}>
          {state.kind === 'unset' ? (
            <Button size="sm" onClick={() => setEditing(true)} disabled={disabled}>
              {t('secretSetAction')}
            </Button>
          ) : (
            <>
              <Button
                size="sm"
                variant="outlined"
                onClick={() => setEditing(true)}
                disabled={disabled}
              >
                {t('secretReplace')}
              </Button>
              <Button size="sm" variant="danger" onClick={onClear} disabled={disabled}>
                {t('secretClear')}
              </Button>
            </>
          )}
        </Box>
      )}
    </Box>
  );
}
