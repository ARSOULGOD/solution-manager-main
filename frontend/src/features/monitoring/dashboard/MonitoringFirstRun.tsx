/**
 * `<MonitoringFirstRun>` (task 089, Dec `DM-D9`, `dynamic-metrics` §11.1,
 * UI §A.11.6 — mirrors the visual reference's own `FirstRunMonitoring` in
 * `docs/design/frontend/reference/app/dashboards.jsx`). What `DashboardView`
 * renders in place of the widget grid when the dashboard has **zero
 * widgets, full stop** — not to be confused with a layer filter that
 * happens to hide everything on a dashboard that does have widgets
 * (`DashboardView`'s own `visibleWidgets` branch keeps the plain
 * `EmptyState` for that case; see its header for why the two must not be
 * conflated). This is a first-install concern: once a package ships
 * `product-default` widgets, or an operator saves one from Explore, this
 * component never renders again for that instance.
 *
 * Per entity type, three tiles state what there IS to explore — instance
 * count, replica count (`Σ replicaCount`, same field task 078's series-cap
 * projection already reads), metric count — so the single primary action
 * into Explore isn't a leap into nothing. Each tile's own query is
 * independent and unblocking: one entity type's slow catalog fetch never
 * holds up another's tiles, and a query still pending renders a skeleton
 * at the number's own place (task 019) rather than a flash of `0`.
 */
import { Box } from '@mui/material';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router';

import { t } from '@shared/i18n';
import { Button, EmptyState, Paper, Skeleton } from '@shared/ui';
import { Grid } from '@shared/ui/layout';

import type { EntityType } from '../metricsRepository';
import { useEntityTypes, useMetricCatalog, useMetricInstances } from '../useCatalog';

import { ActivityIcon, BoltIcon, ChartIcon, SearchIcon, ServerIcon } from './icons';

export interface MonitoringFirstRunProps {
  solutionId: string;
  instanceId: string;
}

// Feature code may not import `@app/routes` (module boundaries) — same
// hand-written-template pattern `DashboardView.tsx`'s own `exploreHref`
// already uses for this exact shape.
function exploreHref(solutionId: string, instanceId: string): string {
  return `/solutions/${solutionId}/instances/${instanceId}/monitoring/explore`;
}

interface SummaryTileProps {
  icon: ReactNode;
  label: string;
  count: number | undefined;
}

function SummaryTile({ icon, label, count }: SummaryTileProps) {
  return (
    <Paper surface="flat">
      <Box sx={{ p: 2, display: 'flex', flexDirection: 'column', gap: 1 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          {icon}
          {count === undefined ? <Skeleton width={24} height={24} /> : <strong>{count}</strong>}
        </Box>
        <span>{label}</span>
      </Box>
    </Paper>
  );
}

function EntityTypeTiles({ entityType }: { entityType: EntityType }) {
  const instancesQuery = useMetricInstances(entityType.id);
  const catalogQuery = useMetricCatalog({ entityType: entityType.id });

  const instanceCount = instancesQuery.data?.total;
  const replicaCount = instancesQuery.data?.items.reduce((sum, instance) => sum + instance.replicaCount, 0);
  const metricCount = catalogQuery.data?.length;

  return (
    <>
      <SummaryTile icon={<BoltIcon fontSize="small" />} label={entityType.label} count={instanceCount} />
      <SummaryTile
        icon={<ServerIcon fontSize="small" />}
        label={t('monitoringFirstRunReplicasLabel')}
        count={replicaCount}
      />
      <SummaryTile
        icon={<ChartIcon fontSize="small" />}
        label={t('monitoringFirstRunMetricsLabel')}
        count={metricCount}
      />
    </>
  );
}

export function MonitoringFirstRun({ solutionId, instanceId }: MonitoringFirstRunProps) {
  const navigate = useNavigate();
  const entityTypesQuery = useEntityTypes();

  return (
    <EmptyState
      icon={<ActivityIcon fontSize="medium" />}
      title={t('monitoringFirstRunTitle')}
      body={t('monitoringFirstRunBody')}
      action={
        <Box sx={{ width: '100%', maxWidth: 620, display: 'flex', flexDirection: 'column', gap: 3, alignItems: 'center' }}>
          {entityTypesQuery.data !== undefined && entityTypesQuery.data.length > 0 && (
            <Box sx={{ width: '100%' }}>
              <Grid columns={{ xs: 1, sm: 3 }} gap={2}>
                {entityTypesQuery.data.map((entityType) => (
                  <EntityTypeTiles key={entityType.id} entityType={entityType} />
                ))}
              </Grid>
            </Box>
          )}
          <Button icon={<SearchIcon fontSize="small" />} onClick={() => void navigate(exploreHref(solutionId, instanceId))}>
            {t('monitoringFirstRunAction')}
          </Button>
        </Box>
      }
    />
  );
}
