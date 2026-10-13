/**
 * Fetches the instance and renders `InstanceHeader` around the
 * category body (task 044). Designer is `DesignerPage` (task 123);
 * Governance is `GovernancePage` (task 064). Monitoring renders the
 * package's default dashboard directly (task 085, `DashboardView`,
 * `landing: true`) — no dashboards index exists or is assumed
 * (`dynamic-metrics` §11.1, §16 Q15).
 */
import { Box } from '@mui/material';
import { useQuery } from '@tanstack/react-query';

import {
  DesignerPage,
  type ActionsRepository,
  type ApplyRepository,
  type ConfigRepository,
} from '@features/designer';
import { GovernancePage, type GovernanceRepository } from '@features/governance';
import type { InstancesRepository } from '@features/inventory';
import { DashboardView, MetricsProvider, defaultDashboardId } from '@features/monitoring';
import { ApiError, type PlaneClient } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import type { HierarchyRepository } from '@shared/hierarchy';
import { t } from '@shared/i18n';
import { PageHeader, Skeleton } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { PageWidth, Stack } from '@shared/ui/layout';

import { InstanceHeader, type InstanceCategory } from './InstanceHeader';

export interface InstanceShellProps {
  solutionId: string;
  instanceId: string;
  category: InstanceCategory;
  repository: InstancesRepository;
  configRepository: ConfigRepository;
  applyRepository: ApplyRepository;
  actionsRepository: ActionsRepository;
  governanceRepository: GovernanceRepository;
  /** Resolves `Instance.environmentId` to a display name for the header's `EnvChip`. */
  hierarchyRepository: HierarchyRepository;
  /** Monitoring's own plane (task 085) — only the `monitoring` category branch uses this. */
  consoleClient: PlaneClient;
}

function describeQueryError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return t('errorGeneric');
}

export function InstanceShell({
  solutionId,
  instanceId,
  category,
  repository,
  configRepository,
  applyRepository,
  actionsRepository,
  governanceRepository,
  hierarchyRepository,
  consoleClient,
}: InstanceShellProps) {
  const instanceQuery = useQuery({
    queryKey: queryKeys.instances.detail(instanceId),
    queryFn: ({ signal }) => repository.get(instanceId, signal),
  });

  // Resolved only once the instance is loaded (needs its environmentId).
  // Absent while pending or on failure — `InstanceHeader` renders no
  // EnvChip rather than a stale or fabricated one; the chip is a nicety,
  // never load-bearing for the tabs below it.
  const environmentId = instanceQuery.data?.environmentId;
  const environmentQuery = useQuery({
    queryKey: queryKeys.hierarchy.environmentPath(environmentId ?? ''),
    queryFn: ({ signal }) => hierarchyRepository.environmentPath(environmentId as string, signal),
    enabled: environmentId !== undefined,
  });

  if (instanceQuery.isPending) {
    return (
      <PageWidth size="wide">
        <Stack gap={3}>
          <Skeleton width={260} height={32} />
          <Skeleton width={420} height={40} />
        </Stack>
      </PageWidth>
    );
  }

  if (instanceQuery.isError) {
    return (
      <PageWidth size="wide">
        <Stack gap={3}>
          <PageHeader title={instanceId} />
          <ErrorPanel
            title={t('instanceErrorTitle')}
            detail={describeQueryError(instanceQuery.error)}
            onRetry={() => void instanceQuery.refetch()}
          />
        </Stack>
      </PageWidth>
    );
  }

  return (
    <PageWidth size={category === 'designer' ? 'designer' : 'instance'}>
      <InstanceHeader
        solutionId={solutionId}
        instanceId={instanceId}
        name={instanceQuery.data.name}
        {...(environmentQuery.data !== undefined ? { environmentName: environmentQuery.data.env } : {})}
      >
        {category === 'designer' ? (
          <Box sx={{ py: 3 }}>
            <DesignerPage
              instanceId={instanceId}
              // Accept/restore-runtime need a placement (`BE-D23`: v1 refuses instance-wide
              // apply). No placement selector exists yet — the first live placement is the
              // documented default until one is designed (plan 008's own flagged gap).
              placementId={instanceQuery.data.placements[0]?.placementId}
              configRepository={configRepository}
              applyRepository={applyRepository}
              actionsRepository={actionsRepository}
            />
          </Box>
        ) : category === 'governance' ? (
          <Box sx={{ py: 3 }}>
            <GovernancePage
              solutionId={solutionId}
              instanceId={instanceId}
              repository={governanceRepository}
              configRepository={configRepository}
            />
          </Box>
        ) : (
          <Box sx={{ py: 3 }}>
            <MetricsProvider consoleClient={consoleClient}>
              <DashboardView
                solutionId={solutionId}
                instanceId={instanceId}
                dashboardId={defaultDashboardId(instanceId)}
                landing
              />
            </MetricsProvider>
          </Box>
        )}
      </InstanceHeader>
    </PageWidth>
  );
}
