/**
 * Per-placement health (task 064, `UI-D9`; re-wired to the per-placement
 * `Health.placements[]` contract shape by integration plan 010 — `OR-39`/`OR-55`).
 * A standby (`INACTIVE_REPLICA`) card is visually distinct from every other card,
 * not just its chip's colour — colour is never the only channel (design §18) — and
 * its note says explicitly that it's excluded from availability math and is not a
 * healthy zero. `unreachable` gets its own distinct treatment too (`OR-39`'s
 * "`UNKNOWN`, not `DOWN`") rather than reading the same as a confirmed failure.
 */
import { Box, Typography } from '@mui/material';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { StateChip } from '@shared/ui';
import { Grid } from '@shared/ui/layout';

import type { HealthState } from '../governanceRepository';
import { HEALTH_STATE_VOCAB } from '../healthRollup';

import type { ReplicaCardsProps } from './ReplicaCards.types';

function noteFor(state: HealthState): string {
  switch (state) {
    case 'GREEN':
      return t('governanceReplicaNoteHealthy');
    case 'RED':
      return t('governanceReplicaNoteNotResponding');
    case 'RESTART_EXPECTED':
      return t('governanceReplicaNoteRestartExpected');
    case 'INACTIVE_REPLICA':
      return t('governanceReplicaNoteInactiveReplica');
    case 'NOT_APPLICABLE':
      return t('governanceReplicaNoteNotApplicable');
    case 'unreachable':
      return t('governanceReplicaNoteUnreachable');
    case 'not_exposed':
      return t('governanceReplicaNoteNotExposed');
    case 'missing':
      return t('governanceReplicaNoteMissing');
    case 'bootstrap':
      return t('governanceReplicaNoteBootstrap');
  }
}

export function ReplicaCards({ placements }: ReplicaCardsProps): ReactElement | null {
  if (placements.length === 0) {
    return null;
  }

  return (
    <Grid columns={{ xs: 1, sm: 2, lg: 3 }} gap={2}>
      {placements.map((placement) => {
        const entry = HEALTH_STATE_VOCAB[placement.state];
        const isStandby = placement.state === 'INACTIVE_REPLICA';
        return (
          <Box
            key={placement.placementId}
            data-standby={isStandby || undefined}
            sx={(theme) => ({
              minWidth: 0,
              p: 2,
              borderRadius: 1,
              border: `1px ${isStandby ? 'dashed' : 'solid'} ${
                isStandby ? theme.palette.tone.idle.main : theme.palette.divider
              }`,
              backgroundColor: isStandby ? theme.palette.tone.idle.bg : 'transparent',
            })}
          >
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 1,
              }}
            >
              <Typography
                variant="body2"
                sx={{ fontFamily: 'monospace', fontWeight: 600, overflowWrap: 'anywhere' }}
              >
                {placement.placementId}
              </Typography>
              <StateChip entry={entry} />
            </Box>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
              {noteFor(placement.state)}
            </Typography>
          </Box>
        );
      })}
    </Grid>
  );
}
