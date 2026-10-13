/**
 * `GovernancePage` (task 064/065/066, `REQ-009`, `UI-D9`, `SM-D22`,
 * `UI-D10`) — the query lifecycle around `HealthHero`, `ReplicaCards`,
 * `ConfigStepper` and `LogViewer`: skeleton · error-with-retry · hero
 * (+ placements when any are live) + the configuration-state ladder. Health and
 * the config ladder are gated together, matching the reference screen's
 * own single `demo` state governing the whole page rather than
 * per-section loading. **Logs is gated separately** — it degrades on its
 * own (`I1`) rather than hiding health/config-state behind a failure to
 * load the package just for the log viewer's component list. Nothing
 * else lives here; projection of each snapshot into copy/tone lives in
 * the child components (and, for the ladder, in `clampReached`).
 *
 * Re-wired to the production contract (integration plan 010): `Health` carries no
 * top-level rollup (`OR-39`/`OR-55`) — the Replicas section now gates on
 * `placements.length > 0`, not a removed `availability` field. `ConfigLadder` is
 * per-placement (ruling `OR-37`): one `ConfigStepper` renders per live placement,
 * each labelled with its id when there is more than one, rather than a single
 * ladder for the whole instance.
 */
import { Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router';

import { deriveComponentSelection } from '@features/designer';
import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import { Alert, ConfigStepper, PageHeader, Paper, SectionHead, Skeleton } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Stack } from '@shared/ui/layout';

import { clampReached } from '../configLadderCursor';
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
  const ladder = configLadderQuery.data;
  // `OR-37`: five rungs everywhere, per placement — never one ladder for the whole
  // instance when more than one placement is live.
  const ladderPlacements = ladder.placements.length > 0
    ? ladder.placements
    : [{ placementId: instanceId, reached: ladder.reached }];

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
      {snapshot.placements.length > 0 && (
        <Stack gap={2}>
          <SectionHead
            title={t('governanceReplicasSectionTitle')}
            caption={t('governanceReplicasCaption', { count: snapshot.placements.length })}
          />
          <ReplicaCards placements={snapshot.placements} />
        </Stack>
      )}
      <Stack gap={2}>
        <SectionHead
          title={t('governanceConfigStateSectionTitle')}
          caption={t('governanceConfigStateCaption')}
        />
        <Stack gap={1.5}>
          {ladderPlacements.map((p) => (
            <Paper key={p.placementId}>
              {ladderPlacements.length > 1 && (
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
                  {p.placementId}
                </Typography>
              )}
              <ConfigStepper reached={clampReached(p.reached)} />
            </Paper>
          ))}
        </Stack>
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
              designerQuery.data.componentTypes,
            ).map((c) => c.type)}
            placementIds={snapshot.placements.map((p) => p.placementId)}
            repository={repository}
          />
        )}
      </Stack>
    </Stack>
  );
}
