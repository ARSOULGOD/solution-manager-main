/**
 * Per-replica health (task 064, `UI-D9`). A standby (`inactive_replica`)
 * card is visually distinct from every other card, not just its chip's
 * colour — colour is never the only channel (design §18) — and its note
 * says explicitly that it's excluded from availability math and is not a
 * healthy zero, so a reader never mistakes "up, idle" for "down".
 */
import { Box, Typography } from '@mui/material';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { StateChip } from '@shared/ui';
import { Grid } from '@shared/ui/layout';
import { healthVocab, type HealthKey } from '@shared/vocab';

import type { ReplicaCardsProps } from './ReplicaCards.types';

function noteFor(health: HealthKey): string {
  switch (health) {
    case 'healthy':
      return t('governanceReplicaNoteHealthy');
    case 'not_responding':
      return t('governanceReplicaNoteNotResponding');
    case 'restart_expected':
      return t('governanceReplicaNoteRestartExpected');
    case 'inactive_replica':
      return t('governanceReplicaNoteInactiveReplica');
    case 'not_applicable':
      return t('governanceReplicaNoteNotApplicable');
  }
}

export function ReplicaCards({ replicas }: ReplicaCardsProps): ReactElement | null {
  if (replicas.length === 0) {
    return null;
  }

  return (
    <Grid columns={{ xs: 1, sm: 2, lg: 3 }} gap={2}>
      {replicas.map((replica) => {
        const entry = healthVocab[replica.health];
        const isStandby = replica.health === 'inactive_replica';
        return (
          <Box
            key={replica.id}
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
                {replica.id}
              </Typography>
              <StateChip entry={entry} />
            </Box>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
              {noteFor(replica.health)}
            </Typography>
          </Box>
        );
      })}
    </Grid>
  );
}
