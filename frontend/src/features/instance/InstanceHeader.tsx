/**
 * Instance context: shared header + Designer/Governance/Monitoring tabs
 * (task 044, Design §9/§14, UI §A.7). A mutation outcome persists on this
 * header (`useOutcome`, FE-021/FE-044) — a toast is acknowledgement, this
 * badge is the record, and it survives a tab change because the store
 * behind it is module state, not component state.
 *
 * A blocked version state (`drift`/`unknown`, `UI-D12`) does not read as a
 * dead instance: Governance and Monitoring stay reachable, and the header
 * says so explicitly, rather than leaving the reader to infer it from a
 * disabled Designer tab alone.
 */
import { Box, Typography } from '@mui/material';
import type { ReactNode } from 'react';

import { t } from '@shared/i18n';
import type { InstanceRow } from '@shared/testdoubles/data/instances';
import { Chip, EnvChip, InfoTip, OutcomeBadge, RouteTabs, StateChip, useOutcome } from '@shared/ui';
import type { RouteTab } from '@shared/ui';
import { healthVocab, notFoundVocab, versionVocab, type VersionKey } from '@shared/vocab';

export type InstanceCategory = 'designer' | 'governance' | 'monitoring';

const CATEGORY_ORDER: readonly InstanceCategory[] = ['designer', 'governance', 'monitoring'];

const CATEGORY_LABELS: Record<InstanceCategory, string> = {
  designer: t('instanceTabDesigner'),
  governance: t('instanceTabGovernance'),
  monitoring: t('instanceTabMonitoring'),
};

// `UI-D12`: `drift`/`unknown` block Designer editing with a stated reason;
// `unverified` (the permanent-normal state for an MI with no
// version-reporting mechanism) and `ok` do not.
const BLOCKING_VERSION_STATES: ReadonlySet<VersionKey> = new Set(['drift', 'unknown']);

function categoryHref(solutionId: string, instanceId: string, category: InstanceCategory): string {
  return `/solutions/${solutionId}/instances/${instanceId}/${category}`;
}

function healthEntry(instance: InstanceRow) {
  if (instance.healthRollup === 'notfound') {
    return instance.notFoundReason === undefined ? undefined : notFoundVocab[instance.notFoundReason];
  }
  return healthVocab[instance.healthRollup];
}

export interface InstanceHeaderProps {
  solutionId: string;
  instance: InstanceRow;
  children: ReactNode;
}

export function InstanceHeader({ solutionId, instance, children }: InstanceHeaderProps) {
  const outcome = useOutcome(instance.id);
  const blocked = BLOCKING_VERSION_STATES.has(instance.version.state);
  const health = healthEntry(instance);
  const versionEntryTip = versionVocab[instance.version.state].tip;

  const tabs: RouteTab[] = CATEGORY_ORDER.map((key) => ({
    to: categoryHref(solutionId, instance.id, key),
    label: CATEGORY_LABELS[key],
  }));

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
        <Typography variant="h5" component="h1">
          {instance.name}
        </Typography>
        <EnvChip env={instance.env} />
        {health !== undefined && <StateChip entry={health} />}
        {blocked && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
            <Chip
              tone="warn"
              icon={versionVocab[instance.version.state].icon}
              label={t('instanceVersionBlockedBadge')}
            />
            {versionEntryTip !== undefined && <InfoTip tip={versionEntryTip} />}
          </Box>
        )}
        <Box sx={{ flex: 1 }} />
        {outcome !== undefined && (
          <OutcomeBadge
            title={outcome.title}
            correlationId={outcome.correlationId}
            severity={outcome.severity}
          />
        )}
      </Box>
      {blocked && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
          {t('instanceVersionBlockedNote')}
        </Typography>
      )}
      <Box sx={{ mt: 2 }}>
        <RouteTabs tabs={tabs}>{children}</RouteTabs>
      </Box>
    </Box>
  );
}
