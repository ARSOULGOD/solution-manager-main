/**
 * Instance context: shared header + Designer/Governance/Monitoring tabs
 * (task 044, Design §9/§14, UI §A.7). A mutation outcome persists on this
 * header (`useOutcome`, FE-021/FE-044) — a toast is acknowledgement, this
 * badge is the record, and it survives a tab change because the store
 * behind it is module state, not component state.
 *
 * Rewritten for integration plan 007 (2026-10-09, consolidated design
 * session item 1): the health chip and the blocked-version badge both read
 * `InstanceRow` fields (`healthRollup`, `version.state`) the real
 * `Instance` contract doesn't carry, and both are redundant here — the
 * Governance tab this header sits directly above already shows the real
 * health (plan 010's `getHealth`), and the Designer tab's own
 * `DivergedBanner` already surfaces a blocked/diverged config state. This
 * header now shows identity only: name and environment.
 */
import { Box, Typography } from '@mui/material';
import type { ReactNode } from 'react';

import { t } from '@shared/i18n';
import { EnvChip, OutcomeBadge, RouteTabs, useOutcome } from '@shared/ui';
import type { RouteTab } from '@shared/ui';

export type InstanceCategory = 'designer' | 'governance' | 'monitoring';

const CATEGORY_ORDER: readonly InstanceCategory[] = ['designer', 'governance', 'monitoring'];

const CATEGORY_LABELS: Record<InstanceCategory, string> = {
  designer: t('instanceTabDesigner'),
  governance: t('instanceTabGovernance'),
  monitoring: t('instanceTabMonitoring'),
};

function categoryHref(solutionId: string, instanceId: string, category: InstanceCategory): string {
  return `/solutions/${solutionId}/instances/${instanceId}/${category}`;
}

export interface InstanceHeaderProps {
  solutionId: string;
  instanceId: string;
  name: string;
  /** Resolved separately (the real `Instance` carries `environmentId`, not a name) — absent while resolving. */
  environmentName?: string;
  children: ReactNode;
}

export function InstanceHeader({
  solutionId,
  instanceId,
  name,
  environmentName,
  children,
}: InstanceHeaderProps) {
  const outcome = useOutcome(instanceId);

  const tabs: RouteTab[] = CATEGORY_ORDER.map((key) => ({
    to: categoryHref(solutionId, instanceId, key),
    label: CATEGORY_LABELS[key],
  }));

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
        <Typography variant="h5" component="h1">
          {name}
        </Typography>
        {environmentName !== undefined && <EnvChip env={environmentName} />}
        <Box sx={{ flex: 1 }} />
        {outcome !== undefined && (
          <OutcomeBadge
            title={outcome.title}
            correlationId={outcome.correlationId}
            severity={outcome.severity}
          />
        )}
      </Box>
      <Box sx={{ mt: 2 }}>
        <RouteTabs tabs={tabs}>{children}</RouteTabs>
      </Box>
    </Box>
  );
}
