/**
 * The version-drift/unknown blocked banner (task 059, Dec `UI-D12`, Design
 * §10/§15, UI §A.8 blocked banner; `architecture.md` §7.2's correction
 * paths). Renders only for `drift`/`unknown` — `unverified` never blocks
 * and must not reach this component styled as a fault (`UI-D12`).
 *
 * **Read-only, not unavailable**: this is a banner ABOVE the config, never
 * a replacement for it — the caller keeps rendering `SectionCard`/etc.
 * alongside this exactly as it always does; nothing here can hide them.
 *
 * **Deliberate correction of the reference.** Its own `BlockedBanner`
 * hardcodes the "version drift" title regardless of the actual state
 * passed, so an `unknown` instance would show a drift-titled banner. This
 * implementation titles and reasons each state correctly, per
 * `dynamic-ui/design.md` §6.1's own state table.
 */
import { Box, Typography } from '@mui/material';
import type { ReactElement } from 'react';

import type { VersionState } from '@contracts/view-model/types';
import { t } from '@shared/i18n';
import { Alert, Button } from '@shared/ui';

import type { BlockedBannerProps } from './BlockedBanner.types';

type BlockingVersionState = 'drift' | 'unknown';

function isBlocking(versionState: VersionState): versionState is BlockingVersionState {
  return versionState === 'drift' || versionState === 'unknown';
}

const TITLE_KEY: Record<
  BlockingVersionState,
  'blockedBannerTitleDrift' | 'blockedBannerTitleUnknown'
> = {
  drift: 'blockedBannerTitleDrift',
  unknown: 'blockedBannerTitleUnknown',
};

export function BlockedBanner({
  status,
  onAccept,
  onRestore,
}: BlockedBannerProps): ReactElement | null {
  if (!isBlocking(status.versionState)) {
    return null;
  }

  const declared = status.declaredVersion ?? '?';
  const reason =
    status.observedVersion !== undefined
      ? t('blockedBannerReasonWithObserved', { declared, observed: status.observedVersion })
      : t('blockedBannerReasonNoObserved', { declared });

  return (
    <Alert severity="err" title={t(TITLE_KEY[status.versionState])}>
      <Typography variant="body2">{reason}</Typography>
      <Typography variant="body2" sx={{ mt: 0.5 }}>
        {t('blockedBannerLegibilityNote')}
      </Typography>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mt: 1.5 }}>
        <Button size="sm" onClick={onAccept}>
          {t('blockedBannerAcceptAction')}
        </Button>
        <Button size="sm" variant="outlined" onClick={onRestore}>
          {t('blockedBannerRestoreAction')}
        </Button>
      </Box>
    </Alert>
  );
}
