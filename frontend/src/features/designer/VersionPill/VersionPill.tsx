/**
 * Version reconciliation pill (task 059, Dec `UI-D12`, Design §10/§15, UI
 * §A.8 version pill; reference `designer.jsx`'s own `VersionPill`). Reuses
 * `versionVocab` (task 013) rather than authoring new state copy — the
 * same tone/icon/label a status chip anywhere else in the console would
 * use for this `VersionState`.
 *
 * **Deliberate correction of the reference.** Its own `map` has no
 * `VERSION_UNKNOWN` entry and silently falls back to `map.OK` — an
 * `unknown` instance would render as a green "Version OK" chip, exactly
 * the fault-hiding bug `UI-D12` exists to prevent. `versionVocab` already
 * has a correct, distinct `unknown` entry (tone `warn`), so every state
 * renders as itself here.
 */
import { Box, Typography } from '@mui/material';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { StateChip } from '@shared/ui';
import { versionVocab } from '@shared/vocab';

import type { VersionPillProps } from './VersionPill.types';

export function VersionPill({ status }: VersionPillProps): ReactElement {
  const { versionState, declaredVersion, observedVersion } = status;

  return (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75 }}>
      <StateChip entry={versionVocab[versionState]} />
      {declaredVersion !== undefined && (
        <Typography
          variant="caption"
          color="text.secondary"
          sx={{ fontFamily: 'JetBrains Mono, ui-monospace, monospace' }}
        >
          {observedVersion !== undefined
            ? t('versionPillDeclaredAndObserved', {
                declared: declaredVersion,
                observed: observedVersion,
              })
            : t('versionPillDeclaredOnly', { declared: declaredVersion })}
        </Typography>
      )}
    </Box>
  );
}
