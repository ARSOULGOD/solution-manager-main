/**
 * UI version and a session id to quote on a support ticket (`FE-D13`,
 * task 036). This id is generated client-side, once per app session, purely
 * to stitch a support conversation together — it is distinct from the
 * per-request `X-Correlation-Id` (`shared/api/correlation.ts`, task 026),
 * whose *response* value is the authoritative one for audit (`FE-D13`).
 * Reuses that same generator so both ids share one format/entropy source.
 *
 * `SupportMenuContent` is exported separately (task 106) so the collapsed
 * app bar (`< md`) can fold this same content into `AccountMenu` instead
 * of nesting one popover menu inside another.
 */
import { Box, Stack, Typography } from '@mui/material';
import { useMemo, useState } from 'react';

import { generateCorrelationId } from '@shared/api';
import { t } from '@shared/i18n';
import { Button, IconButton, Menu } from '@shared/ui';

import { getBuildInfo } from '../boot/buildInfo';

import { CopyIcon, HelpIcon } from './icons';

export function SupportMenuContent() {
  const sessionId = useMemo(() => generateCorrelationId(), []);
  const buildInfo = useMemo(() => getBuildInfo(), []);
  const [copied, setCopied] = useState(false);

  async function copyId() {
    try {
      await navigator.clipboard.writeText(sessionId);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <Stack spacing={1} sx={{ minWidth: 220 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 2 }}>
        <Typography variant="body2" color="text.secondary">
          {t('uiVersion')}
        </Typography>
        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
          {buildInfo.version}
        </Typography>
      </Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 2 }}>
        <Typography variant="body2" color="text.secondary">
          {t('correlationIdLabel')}
        </Typography>
        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
          {sessionId}
        </Typography>
      </Box>
      <Typography variant="caption" color="text.secondary">
        {t('supportCorrelationNote')}
      </Typography>
      <Button
        size="sm"
        variant="outlined"
        icon={<CopyIcon fontSize="small" />}
        block
        onClick={() => void copyId()}
      >
        {copied ? t('idCopied') : t('copyId')}
      </Button>
    </Stack>
  );
}

export function SupportMenu() {
  return (
    <Menu
      trigger={
        <IconButton aria-label={t('supportAndVersion')}>
          <HelpIcon fontSize="small" />
        </IconButton>
      }
      entries={[
        { kind: 'caption', label: t('supportCaption') },
        { kind: 'content', content: <SupportMenuContent /> },
      ]}
    />
  );
}
