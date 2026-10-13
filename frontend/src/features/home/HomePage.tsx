/**
 * Home (task 038, `REQ-001`, `UI-D8`, UI §A.4) — "what needs my attention",
 * not an aggregate dashboard. `solutionsManaged === 0` is this task's own
 * rule for the first-run branch (§16 Q10 flags the whole endpoint TBD, so
 * there is no server-carried "first run" flag to read instead).
 *
 * Loading is a single skeleton for both queries together, matching the
 * reference's one demo state per screen — Home has no widget that makes
 * sense half-loaded (the stat strip and the feed answer the same
 * question).
 */
import { Box, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { useAuth } from '@shared/auth/AuthProvider';
import { t } from '@shared/i18n';
import { Alert, PageHeader, Skeleton, ToneIcon } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Grid, PageWidth, Stack } from '@shared/ui/layout';

import { AttentionFeed } from './AttentionFeed';
import { FirstRun } from './FirstRun';
import type { HomeRepository } from './homeRepository';
import { StatStrip } from './StatStrip';

export interface HomePageProps {
  repository: HomeRepository;
}

function describeQueryError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return t('errorGeneric');
}

function HomeLoadingSkeleton() {
  return (
    <PageWidth>
      <Stack gap={3}>
        <Box>
          <Skeleton width={150} height={32} />
          <Box sx={{ mt: 1 }}>
            <Skeleton width={420} height={16} />
          </Box>
        </Box>
        <Grid columns={{ xs: 1, sm: 2, lg: 4 }}>
          {[0, 1, 2, 3].map((i) => (
            <Box key={i} sx={{ p: 2, border: '1px solid', borderColor: 'divider', borderRadius: 1.5 }}>
              <Skeleton width={54} height={30} />
              <Box sx={{ mt: 2 }}>
                <Skeleton width={120} height={13} />
              </Box>
            </Box>
          ))}
        </Grid>
        <Box sx={{ p: 2, border: '1px solid', borderColor: 'divider', borderRadius: 1.5 }}>
          {[0, 1, 2, 3].map((i) => (
            <Box key={i} sx={{ py: 1.75 }}>
              <Skeleton width="42%" height={15} />
              <Box sx={{ mt: 1 }}>
                <Skeleton width="72%" height={12} />
              </Box>
            </Box>
          ))}
        </Box>
      </Stack>
    </PageWidth>
  );
}

export function HomePage({ repository }: HomePageProps) {
  const { identity } = useAuth();

  const summaryQuery = useQuery({
    queryKey: queryKeys.home.summary(),
    queryFn: ({ signal }) => repository.summary(signal),
  });
  const attentionQuery = useQuery({
    queryKey: queryKeys.home.attention(),
    queryFn: ({ signal }) => repository.attention(signal),
  });

  if (summaryQuery.isPending || attentionQuery.isPending) {
    return <HomeLoadingSkeleton />;
  }

  if (summaryQuery.isError || attentionQuery.isError) {
    return (
      <PageWidth>
        <Stack gap={3}>
          <PageHeader title={t('homeTitle')} sub={t('homeSubtitle')} />
          <ErrorPanel
            title={t('homeErrorTitle')}
            detail={describeQueryError(summaryQuery.error ?? attentionQuery.error)}
            onRetry={() => {
              void summaryQuery.refetch();
              void attentionQuery.refetch();
            }}
          />
        </Stack>
      </PageWidth>
    );
  }

  const summary = summaryQuery.data;
  const attention = attentionQuery.data;

  if (summary.solutionsManaged === 0) {
    return <FirstRun superAdmin={identity?.superAdmin ?? false} />;
  }

  return (
    <PageWidth>
      <Stack gap={3}>
        <PageHeader title={t('homeTitle')} sub={t('homeSubtitle')} />
        {summary.partial != null && (
          <Alert severity="warn" title={t('homePartialTitle')} fullWidth>
            {t('homePartialBody', { host: summary.partial.unreportingHost })}
          </Alert>
        )}
        <StatStrip summary={summary} />
        <AttentionFeed items={attention} />
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, color: 'text.secondary' }}>
          <ToneIcon tone="info" sx={{ fontSize: 15, color: 'inherit', flex: 'none' }} />
          <Typography variant="caption">{t('homeFooterNoAlerting')}</Typography>
        </Box>
      </Stack>
    </PageWidth>
  );
}
