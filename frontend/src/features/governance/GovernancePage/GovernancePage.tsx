/**
 * `GovernancePage` (task 064/065/066, `REQ-009`, `UI-D9`, `SM-D22`,
 * `UI-D10`) — the query lifecycle around `HealthHero`, `ReplicaCards`,
 * `ConfigStepper` and `LogViewer`: skeleton · error-with-retry · hero
 * (+ replicas when declared) + the configuration-state ladder. Health and
 * the config ladder are gated together, matching the reference screen's
 * own single `demo` state governing the whole page rather than
 * per-section loading. **Logs is gated separately** — it degrades on its
 * own (`I1`) rather than hiding health/config-state behind a failure to
 * load the package just for the log viewer's component list. Nothing
 * else lives here; projection of each snapshot into copy/tone lives in
 * the child components (and, for the ladder, in `deriveReachedRung`).
 */
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router';

import { deriveComponentSelection } from '@features/designer';
import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import { Alert, ConfigStepper, PageHeader, Paper, SectionHead, Skeleton } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Stack } from '@shared/ui/layout';

import { deriveReachedRung } from '../configLadderCursor';
import { HealthHero } from '../HealthHero';
import { LogViewer } from '../LogViewer';
import { ReplicaCards } from '../ReplicaCards';

import type { GovernancePageProps } from './GovernancePage.types';

function describeQueryError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return t('errorGeneric');
}

// Feature code may not import `@app/routes` (module boundaries) — the same
// hand-written-template pattern `InstanceHeader`'s own `categoryHref`
// already uses for this exact three-segment shape.
function monitoringHref(solutionId: string, instanceId: string): string {
  return `/solutions/${solutionId}/instances/${instanceId}/monitoring`;
}

export function GovernancePage({
  solutionId,
  instanceId,
  repository,
  configRepository,
}: GovernancePageProps) {
  const navigate = useNavigate();
  const healthQuery = useQuery({
    queryKey: queryKeys.governance.health(instanceId),
    queryFn: ({ signal }) => repository.health(instanceId, signal),
  });
  const configLadderQuery = useQuery({
    queryKey: queryKeys.governance.configLadder(instanceId),
    queryFn: ({ signal }) => repository.configLadder(instanceId, signal),
  });
  // Same query key `DesignerPage` uses — a Designer visit for this
  // instance means this one is served from cache, not refetched.
  const designerQuery = useQuery({
    queryKey: queryKeys.config.designer(instanceId),
    queryFn: ({ signal }) => configRepository.loadDesigner(instanceId, signal),
  });

  if (healthQuery.isPending || configLadderQuery.isPending) {
    return (
      <Stack gap={3}>
        <Skeleton width={200} height={32} />
        <Skeleton width="100%" height={100} />
        <Stack gap={2}>
          {[0, 1, 2].map((row) => (
            <Skeleton key={row} width="100%" height={90} />
          ))}
        </Stack>
        <Skeleton width="100%" height={72} />
      </Stack>
    );
  }

  if (healthQuery.isError || configLadderQuery.isError) {
    const error = healthQuery.error ?? configLadderQuery.error;
    return (
      <Stack gap={3}>
        <PageHeader title={t('governanceTitle')} sub={t('governanceSub')} />
        <ErrorPanel
          title={t('governanceErrorTitle')}
          detail={describeQueryError(error)}
          onRetry={() => {
            void healthQuery.refetch();
            void configLadderQuery.refetch();
          }}
        />
      </Stack>
    );
  }

  const snapshot = healthQuery.data;
  const reachedRung = deriveReachedRung(configLadderQuery.data.steps);

  return (
    <Stack gap={3}>
      <PageHeader title={t('governanceTitle')} sub={t('governanceSub')} />
      <HealthHero
        snapshot={snapshot}
        onOpenMonitoring={() => void navigate(monitoringHref(solutionId, instanceId))}
      />
      {/* No synthetic health score, no composite gauge (`dynamic-ui` §5.1,
          `HealthHero`'s own header comment) — this banner explains why the
          screen stays this simple instead of "improving" it with one. */}
      <Alert severity="neutral" title={t('governanceNonGoalTitle')}>
        {t('governanceNonGoalBody')}
      </Alert>
      {snapshot.availability === 'declared' && (
        <Stack gap={2}>
          <SectionHead
            title={t('governanceReplicasSectionTitle')}
            caption={t('governanceReplicasCaption', { count: snapshot.replicas.length })}
          />
          <ReplicaCards replicas={snapshot.replicas} />
        </Stack>
      )}
      <Stack gap={2}>
        <SectionHead
          title={t('governanceConfigStateSectionTitle')}
          caption={t('governanceConfigStateCaption')}
        />
        <Paper>
          <ConfigStepper reached={reachedRung} />
        </Paper>
      </Stack>
      <Stack gap={2}>
        <SectionHead title={t('governanceLogsSectionTitle')} />
        {designerQuery.isPending ? (
          <Skeleton width="100%" height={220} />
        ) : designerQuery.isError ? (
          <ErrorPanel
            title={t('governanceLogsPackageErrorTitle')}
            detail={describeQueryError(designerQuery.error)}
            onRetry={() => void designerQuery.refetch()}
          />
        ) : (
          <LogViewer
            instanceId={instanceId}
            components={deriveComponentSelection(
              designerQuery.data.package,
              designerQuery.data.packageVersion,
            ).map((c) => c.type)}
            repository={repository}
          />
        )}
      </Stack>
    </Stack>
  );
}
