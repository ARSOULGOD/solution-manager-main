/**
 * Targets preview (task 095, Req `REQ-005` `REQ-008`, Dec `SM-D23`
 * `SM-D11a`, Design §14, UI §A.12.6). A **read-only** model preview:
 * target id, location, driver, `control_locus`, ownership scope, plus the
 * Target/Binding field lists. Not in v1 nav — reachable by URL only.
 *
 * ⚠️ **Nothing on this screen writes to a target.** `TargetsRepository`
 * has no write method and there is no credential entry: the `dp-remote`
 * apply protocol it would depend on is an open blocker (`DECISIONS.md`
 * open items 1, 2). The blocked-work note says so on the screen.
 */
import { Box, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import type { ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import type { TargetRow } from '@shared/testdoubles/data/targets';
import { Alert, Chip, InfoTip, Paper, Skeleton } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { PageWidth, Stack } from '@shared/ui/layout';
import { driverVocab, locusVocab } from '@shared/vocab';

import type { TargetsPreviewProps } from './TargetsPreview.types';

function describeError(error: unknown): string {
  return error instanceof ApiError ? error.message : t('errorGeneric');
}

function ModelPaper({ label, fields }: { label: string; fields: string }): ReactElement {
  return (
    <Paper>
      <Typography variant="overline" component="p">
        {label}
      </Typography>
      <Typography
        variant="body2"
        component="p"
        sx={{ mt: 1, fontFamily: 'monospace', whiteSpace: 'pre-line', lineHeight: 1.9 }}
      >
        {fields}
      </Typography>
    </Paper>
  );
}

export function TargetsPreview({ repository }: TargetsPreviewProps): ReactElement {
  const targetsQuery = useQuery({
    queryKey: queryKeys.targets.list(),
    queryFn: ({ signal }) => repository.list(signal),
  });

  return (
    <PageWidth size="wide">
      <Stack gap={3}>
        <Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', mb: 1 }}>
            <Chip tone="neutral" label={t('targetsPreviewBadgeLaterIncrement')} />
            <Chip tone="idle" label={t('targetsPreviewBadgeNotInNav')} />
          </Box>
          <Typography variant="h4" component="h1">
            {t('targetsPreviewTitle')}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            {t('targetsPreviewSub')}
          </Typography>
        </Box>

        <Alert severity="info" title={t('targetsPreviewLocusBannerTitle')} fullWidth>
          {t('targetsPreviewLocusBannerBody')}
        </Alert>

        {targetsQuery.isPending ? (
          <Skeleton width="100%" height={200} />
        ) : targetsQuery.isError ? (
          <ErrorPanel
            title={t('targetsPreviewErrorTitle')}
            detail={describeError(targetsQuery.error)}
            onRetry={() => void targetsQuery.refetch()}
          />
        ) : (
          // The table is the one thing here that legitimately scrolls
          // sideways at 320 px — four columns of identifiers do not reflow
          // into a phone. `data-reflow-exception` is what declares that to
          // FE-110's gate (`e2e/reflow/reflowCheck.ts`); without it the
          // scroller reads as an accident, and the focusables inside it
          // read as clipped with no way to reach them.
          <Box sx={{ overflowX: 'auto' }} data-reflow-exception="data-table">
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('targetsPreviewColTarget')}</TableCell>
                  <TableCell>{t('targetsPreviewColDriver')}</TableCell>
                  <TableCell>{t('targetsPreviewColLocus')}</TableCell>
                  <TableCell>{t('targetsPreviewColScope')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {targetsQuery.data.map((target: TargetRow) => {
                  const driver = driverVocab[target.driver];
                  const locus = locusVocab[target.locus];
                  return (
                    <TableRow key={target.id}>
                      <TableCell>
                        <Box sx={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
                          <Typography component="span" variant="body2" sx={{ fontWeight: 600 }}>
                            {target.id}
                          </Typography>
                          <Typography
                            component="span"
                            variant="caption"
                            color="text.secondary"
                            sx={{ fontFamily: 'monospace' }}
                          >
                            {target.location}
                          </Typography>
                        </Box>
                      </TableCell>
                      <TableCell>{driver.label}</TableCell>
                      <TableCell>
                        <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
                          <Chip tone={locus.tone} label={locus.label} />
                          {locus.tip !== undefined && <InfoTip tip={locus.tip} />}
                        </Box>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {target.ownershipScope.join(' / ')}
                        </Typography>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </Box>
        )}

        <Box>
          <Typography variant="h6" component="h2" sx={{ mb: 1 }}>
            {t('targetsPreviewModelHeading')}
          </Typography>
          <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
            {/* `min(300px, 100%)`, not a bare 300: the intent is "two
                columns while there is room for them", and a hard floor
                makes that an overflow instead of a wrap once the content
                box is narrower than 300 px — which it is at the 320 px
                reflow floor (FE-D29, SC 1.4.10), by 4 px per column. */}
            <Box sx={{ flex: 1, minWidth: 'min(300px, 100%)' }}>
              <ModelPaper
                label={t('targetsPreviewTargetFieldsLabel')}
                fields={t('targetsPreviewTargetFields')}
              />
            </Box>
            <Box sx={{ flex: 1, minWidth: 'min(300px, 100%)' }}>
              <ModelPaper
                label={t('targetsPreviewBindingFieldsLabel')}
                fields={t('targetsPreviewBindingFields')}
              />
            </Box>
          </Box>
        </Box>

        <Alert severity="warn" title={t('targetsPreviewBlockedTitle')} fullWidth>
          {t('targetsPreviewBlockedBody')}
        </Alert>
      </Stack>
    </PageWidth>
  );
}
