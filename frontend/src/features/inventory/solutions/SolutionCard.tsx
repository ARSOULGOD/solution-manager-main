/**
 * One package card (task 039, `UI-D11`, UI §A.5; integration plan 005
 * Task 2). Renders only the fields `console.yaml`'s `Solution` schema
 * declares — `solutionId`, `solution`, `name`, `packageVersions`,
 * `latestPackageVersion`, `signature`. The pre-005 card additionally
 * rendered provenance (signed vs. unsigned dev-mode), an authoring
 * review-coverage meter, entitlement, instance count, telemetry mode and
 * a component list — none of those fields exist on `Solution`, and
 * plan 005 Task 2 repeats plan 002's prohibition on synthesizing them.
 * That loss of visual richness is a flagged, open product/design
 * decision (`docs/plans/integration/reports/005-task-report.md`).
 *
 * This pass restyles the honest field set rather than reintroducing any
 * of the removed ones — `hoverable` surface, a tone-tinted icon tile and
 * a leading icon on the signature chip, all driven by the theme's tone
 * palette (`FE-D5`: no literal colour here), not by new data.
 */
import { alpha, Box, Typography, useTheme } from '@mui/material';

import { t } from '@shared/i18n';
import { Button, Chip, PackageGlyphIcon, Paper, ToneIcon } from '@shared/ui';
import { Cluster } from '@shared/ui/layout';

import { ArrowRightIcon } from './icons';
import type { SolutionCatalogItem } from './solutionsRepository';

export interface SolutionCardProps {
  solution: SolutionCatalogItem;
  onOpen: (solutionId: string) => void;
}

export function SolutionCard({ solution, onOpen }: SolutionCardProps) {
  const theme = useTheme();

  return (
    <Paper surface="hoverable" data-solution-card>
      <Box sx={{ display: 'flex', gap: 1.5 }}>
        <Box
          aria-hidden
          sx={{
            width: 48,
            height: 48,
            flex: 'none',
            borderRadius: 1.5,
            display: 'grid',
            placeItems: 'center',
            backgroundColor: alpha(theme.palette.primary.main, 0.12),
            color: theme.palette.primary.main,
          }}
        >
          <PackageGlyphIcon fontSize="medium" />
        </Box>
        <Box sx={{ minWidth: 0, flex: '1 1 auto' }}>
          <Typography
            variant="h6"
            component="h2"
            sx={{ overflowWrap: 'anywhere', fontWeight: 600 }}
          >
            {solution.name}
          </Typography>
          <Typography
            variant="body2"
            color="text.secondary"
            sx={{ mt: 0.25, fontFamily: 'monospace' }}
          >
            {solution.solution}
          </Typography>
        </Box>
      </Box>

      {/* v1 loads no signed packages (`console.yaml` `Solution.signature`) — every row
          is `unsigned`, so this is informational, not a trust signal (see file header). */}
      <Box sx={{ mt: 1.5 }}>
        <Chip
          tone="neutral"
          icon={<ToneIcon tone="neutral" fontSize="small" />}
          label={t('solutionsSignatureUnsigned')}
        />
      </Box>

      <Box
        component="dl"
        sx={{
          m: 0,
          mt: 2,
          pt: 1.5,
          borderTop: '1px solid',
          borderColor: 'divider',
        }}
      >
        <Typography component="dt" variant="body2" color="text.secondary">
          {t('solutionsPackageVersion')}
        </Typography>
        {/* Every version this one package file serves (FE-118) as its own chip —
            not joined text — with the latest one tone-highlighted and labelled,
            never colour alone, so the distinction survives without colour vision. */}
        <Box component="dd" sx={{ m: 0, mt: 0.75 }}>
          <Cluster gap={0.5}>
            {solution.packageVersions.map((version) =>
              version === solution.latestPackageVersion ? (
                <Chip
                  key={version}
                  tone="ok"
                  dot
                  mono
                  label={t('solutionsLatestPackageVersionChip', { version: String(version) })}
                />
              ) : (
                <Chip key={version} tone="neutral" mono label={String(version)} />
              ),
            )}
          </Cluster>
        </Box>
      </Box>

      <Box
        sx={{
          mt: 2,
          pt: 1.5,
          borderTop: '1px solid',
          borderColor: 'divider',
          display: 'flex',
          alignItems: 'center',
          gap: 2,
        }}
      >
        <Box sx={{ flex: '1 1 auto' }} />
        <Cluster gap={0.5}>
          <Button
            size="sm"
            variant="outlined"
            iconRight={<ArrowRightIcon fontSize="small" />}
            onClick={() => onOpen(solution.solutionId)}
          >
            {t('solutionsOpen')}
          </Button>
        </Cluster>
      </Box>
    </Paper>
  );
}
