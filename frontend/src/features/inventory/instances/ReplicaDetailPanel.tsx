/**
 * Replica detail panel (task 041, UI §A.6) — `DataTable`'s `detailPanel`
 * for an expanded instance row. Per-replica health cards, plus version
 * and configuration explanations, read from the same `shared/vocab` the
 * row's own cells use — never a second copy of the wording.
 */
import { Box, Typography } from '@mui/material';

import { t } from '@shared/i18n';
import type { InstanceRow } from '@shared/testdoubles/data/instances';
import { Chip } from '@shared/ui';
import { configStateVocab, healthVocab, versionVocab } from '@shared/vocab';

import { VERSION_EDITABILITY } from './columns';

export interface ReplicaDetailPanelProps {
  row: InstanceRow;
}

function versionExplanation(row: InstanceRow): string {
  const entry = versionVocab[row.version.state];
  return [entry.tip, VERSION_EDITABILITY[row.version.state]].filter(Boolean).join(' ');
}

function configExplanation(row: InstanceRow): string {
  const entry = configStateVocab[row.configState];
  // `in_sync` carries its explanation as the effective-unverified
  // qualifier (`SM-D22`) rather than a vocab `tip` — the qualifier chip's
  // own tooltip text, so this panel states the identical reason.
  return row.configState === 'in_sync' ? t('instancesConfigEffectiveUnverifiedTip') : (entry.tip ?? entry.label);
}

export function ReplicaDetailPanel({ row }: ReplicaDetailPanelProps) {
  return (
    <Box data-replica-detail-panel>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))',
          gap: 1.5,
        }}
      >
        {row.replicas.map((replica) => {
          const entry = healthVocab[replica.health];
          return (
            <Box
              key={replica.name}
              sx={{ p: 1.5, border: '1px solid', borderColor: 'divider', borderRadius: 1.5 }}
            >
              <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 1 }}>
                <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
                  {replica.name}
                </Typography>
                <Chip tone={entry.tone} icon={entry.icon} label={entry.label} />
              </Box>
              {entry.tip !== undefined && (
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>
                  {entry.tip}
                </Typography>
              )}
            </Box>
          );
        })}
      </Box>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
          gap: 2,
          mt: 2,
          pt: 2,
          borderTop: '1px solid',
          borderColor: 'divider',
        }}
      >
        <Box>
          <Typography variant="overline" color="text.secondary">
            {t('instancesDetailVersionHeading')}
          </Typography>
          <Typography variant="body2">{versionExplanation(row)}</Typography>
        </Box>
        <Box>
          <Typography variant="overline" color="text.secondary">
            {t('instancesDetailConfigHeading')}
          </Typography>
          <Typography variant="body2">{configExplanation(row)}</Typography>
        </Box>
      </Box>
    </Box>
  );
}
