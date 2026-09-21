/**
 * Answers one question — is the endpoint up? — and nothing else (task 064,
 * `REQ-009`, `UI-D9`, `dynamic-ui` §5.1). **No synthetic health score, no
 * composite gauge** is an explicit non-goal: the cross-check this screen
 * exists for only works because a `RED` rollup routes straight to
 * Monitoring, where a gap renders as a gap — a computed score here would
 * just be a second, less trustworthy opinion. Do not add one.
 */
import { Box, Typography } from '@mui/material';

import { t } from '@shared/i18n';
import { Button, Chip, Paper, ToneIcon } from '@shared/ui';
import { healthVocab } from '@shared/vocab';

import type { HealthRollupState } from '../governanceRepository';
import { ROLLUP_TO_HEALTH_KEY } from '../healthRollup';

import type { HealthHeroProps } from './HealthHero.types';

function paragraphFor(rollup: HealthRollupState): string {
  switch (rollup) {
    case 'GREEN':
      return t('governanceParagraphGreen');
    case 'RED':
      return t('governanceParagraphRed');
    case 'RESTART_EXPECTED':
      return t('governanceParagraphRestartExpected');
    case 'INACTIVE_REPLICA':
      return t('governanceParagraphInactiveReplica');
    case 'NOT_APPLICABLE':
      return t('governanceParagraphNotApplicable');
  }
}

export function HealthHero({ snapshot, onOpenMonitoring }: HealthHeroProps) {
  const entry = healthVocab[ROLLUP_TO_HEALTH_KEY[snapshot.rollup]];
  // `RED` is the one rollup whose CTA is primary — the workflow is
  // "red badge → open the graphs → no data confirms it" (`UI-D9`).
  const isRed = snapshot.rollup === 'RED';

  return (
    <Paper>
      <Box sx={{ display: 'flex', gap: 2, alignItems: 'flex-start', flexWrap: 'wrap' }}>
        <Box
          sx={(theme) => ({
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 56,
            height: 56,
            flex: 'none',
            borderRadius: '50%',
            backgroundColor: theme.palette.tone[entry.tone].bg,
            color: theme.palette.tone[entry.tone].main,
          })}
        >
          <ToneIcon tone={entry.tone} sx={{ fontSize: 30 }} />
        </Box>
        <Box sx={{ flex: '1 1 auto', minWidth: 0 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
            <Typography variant="h4" component="p" sx={{ overflowWrap: 'anywhere' }}>
              {entry.label}
            </Typography>
            <Chip tone={entry.tone} dot label={t('governanceRollupBadge')} />
          </Box>
          <Typography variant="body1" color="text.secondary" sx={{ mt: 0.5, maxWidth: 620 }}>
            {paragraphFor(snapshot.rollup)}
          </Typography>
        </Box>
        <Button variant={isRed ? 'contained' : 'outlined'} onClick={onOpenMonitoring}>
          {t('governanceOpenMonitoring')}
        </Button>
      </Box>
    </Paper>
  );
}
