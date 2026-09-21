/**
 * `CONFIG_DIVERGED` remediation banner (task 062, Dec `B/D12` `SM-D22`,
 * Design §10.1 (R2 finding 5), UI §A.8 diverged banner; reference
 * `designer_panels.jsx`'s own `DivergedBanner`). Exactly three
 * remediations, no fourth — in particular **no "force healthy"**: a
 * halted rollout has left replicas on different generations, and nothing
 * here can paper over that by declaring the instance healthy anyway.
 *
 * Bare callbacks, no repository wiring — same split `BlockedBanner` (task
 * 059) already uses. `onRestage` fires directly (re-staging is the
 * low-consequence "stage and wait" step, no restart, no confirm needed);
 * `onRestart`/`onRollback` are expected to open `RestartConfirm` (task
 * 061) and `RollbackDialog` respectively, which own their own confirm
 * flow — this banner does not duplicate it.
 *
 * **Not embedded here**: the per-replica table. The reference renders
 * `RolloutCard`/`ReplicaTable` as a sibling, always, regardless of
 * scenario (`designer.jsx:272`) — this banner sits above it, the same
 * "banner above the config, never a replacement for it" relationship
 * `BlockedBanner` already establishes.
 */
import { Box, Typography } from '@mui/material';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert, Button } from '@shared/ui';

import type { DivergedBannerProps } from './DivergedBanner.types';

export function DivergedBanner({
  onRestage,
  onRestart,
  onRollback,
}: DivergedBannerProps): ReactElement {
  return (
    <Alert severity="err" title={t('divergedBannerTitle')} fullWidth>
      <Typography variant="body2">{t('divergedBannerBody')}</Typography>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mt: 1.5 }}>
        <Button size="sm" variant="outlined" onClick={onRestage}>
          {t('divergedBannerRestageAction')}
        </Button>
        <Button size="sm" variant="outlined" onClick={onRestart}>
          {t('divergedBannerRestartAction')}
        </Button>
        <Button size="sm" variant="outlined" onClick={onRollback}>
          {t('divergedBannerRollbackAction')}
        </Button>
      </Box>
    </Alert>
  );
}
