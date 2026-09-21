/**
 * Solutions catalog (task 039/040, `REQ-001`, `REQ-002`, UI §A.5). The
 * "Load a package" trigger is super-admin-only — **omitted**, not
 * disabled, for everyone else (`FE-D9`) — same `identity.superAdmin`
 * gate `features/home/FirstRun.tsx` established, not the RBAC `Can`
 * gate: `package.load` sits in the permission catalog as `absent`
 * (never grantable, `C12`), so gating on it via `Can` would omit the
 * control for a super-admin too.
 */
import { Box } from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { useAuth } from '@shared/auth/AuthProvider';
import { t } from '@shared/i18n';
import {
  Alert,
  Button,
  EmptyState,
  PackageGlyphIcon,
  PageHeader,
  Skeleton,
  pushToast,
} from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Grid, PageWidth, Stack } from '@shared/ui/layout';

import { LockIcon, UploadIcon } from './icons';
import { LoadPackageDialog } from './LoadPackageDialog';
import { SolutionCard } from './SolutionCard';
import type { PackageLoadResult, SolutionsRepository } from './solutionsRepository';

// A feature module may not import the app-layer `routes` helper
// (boundaries) — `StatStrip.tsx` documents the same constraint for
// itself, hardcoding the instances path template rather than reaching
// into `@app/*`.
function instancesHref(solutionId: string): string {
  return `/solutions/${solutionId}/instances`;
}

export interface SolutionsPageProps {
  repository: SolutionsRepository;
}

function describeQueryError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return t('errorGeneric');
}

function SolutionsLoadingSkeleton() {
  return (
    <PageWidth size="wide">
      <Stack gap={3}>
        <Box>
          <Skeleton width={150} height={32} />
          <Box sx={{ mt: 1 }}>
            <Skeleton width={480} height={16} />
          </Box>
        </Box>
        <Grid columns={{ xs: 1, sm: 2, lg: 3 }}>
          {[0, 1, 2].map((i) => (
            <Box
              key={i}
              sx={{ p: 2.5, border: '1px solid', borderColor: 'divider', borderRadius: 1.5 }}
            >
              <Box sx={{ display: 'flex', gap: 1.5 }}>
                <Skeleton width={44} height={44} />
                <Box sx={{ flex: '1 1 auto' }}>
                  <Skeleton width="60%" height={20} />
                  <Box sx={{ mt: 1 }}>
                    <Skeleton width="40%" height={14} />
                  </Box>
                </Box>
              </Box>
              <Box sx={{ mt: 3 }}>
                <Skeleton width="100%" height={12} />
                <Box sx={{ mt: 1 }}>
                  <Skeleton width="90%" height={12} />
                </Box>
                <Box sx={{ mt: 2 }}>
                  <Skeleton width="100%" height={6} />
                </Box>
              </Box>
            </Box>
          ))}
        </Grid>
      </Stack>
    </PageWidth>
  );
}

export function SolutionsPage({ repository }: SolutionsPageProps) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { identity } = useAuth();
  const superAdmin = identity?.superAdmin ?? false;
  const [loadOpen, setLoadOpen] = useState(false);

  const solutionsQuery = useQuery({
    queryKey: queryKeys.solutions.list(),
    queryFn: ({ signal }) => repository.list(signal),
  });

  function handleLoaded(result: PackageLoadResult) {
    setLoadOpen(false);
    void queryClient.invalidateQueries({ queryKey: queryKeys.solutions.list() });
    pushToast({
      title: t('solutionsLoadSuccessTitle'),
      detail: t('solutionsLoadSuccessDetail', { message: result.message }),
      correlationId: result.correlationId,
      severity: 'ok',
    });
  }

  const loadPackageButton = (
    <Button icon={<UploadIcon />} onClick={() => setLoadOpen(true)}>
      {t('solutionsLoadAction')}
    </Button>
  );
  const loadPackageDialog = (
    <LoadPackageDialog
      open={loadOpen}
      onClose={() => setLoadOpen(false)}
      onLoaded={handleLoaded}
      repository={repository}
      existingPackages={solutionsQuery.data ?? []}
    />
  );

  if (solutionsQuery.isPending) {
    return <SolutionsLoadingSkeleton />;
  }

  if (solutionsQuery.isError) {
    return (
      <PageWidth size="wide">
        <Stack gap={3}>
          <PageHeader
            title={t('solutionsTitle')}
            sub={t('solutionsSubtitle')}
            actions={superAdmin ? loadPackageButton : undefined}
          />
          <ErrorPanel
            title={t('solutionsErrorTitle')}
            detail={describeQueryError(solutionsQuery.error)}
            onRetry={() => void solutionsQuery.refetch()}
          />
        </Stack>
        {loadPackageDialog}
      </PageWidth>
    );
  }

  const solutions = solutionsQuery.data;

  return (
    <PageWidth size="wide">
      <Stack gap={3}>
        <PageHeader
          title={t('solutionsTitle')}
          sub={t('solutionsSubtitle')}
          actions={superAdmin ? loadPackageButton : undefined}
        />
        {solutions.length === 0 ? (
          <EmptyState
            icon={<PackageGlyphIcon fontSize="medium" />}
            title={t('solutionsEmptyTitle')}
            body={t('solutionsEmptyBody')}
            action={
              superAdmin ? (
                loadPackageButton
              ) : (
                <Alert severity="neutral" icon={<LockIcon />}>
                  {t('solutionsLoadAdminOnlyAlert')}
                </Alert>
              )
            }
          />
        ) : (
          <Grid columns={{ xs: 1, sm: 2, lg: 3 }}>
            {solutions.map((solution) => (
              <SolutionCard
                key={solution.id}
                solution={solution}
                onOpen={(solutionId) => void navigate(instancesHref(solutionId))}
              />
            ))}
          </Grid>
        )}
      </Stack>
      {loadPackageDialog}
    </PageWidth>
  );
}
