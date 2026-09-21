/**
 * One package card (task 039, `UI-D11`, `UI-D17`, `UI-D19`, UI §A.5). The
 * two things that carry trust weight: provenance (signed vs unsigned
 * dev-mode) and authoring review coverage (`ReviewMeter`). An unsigned
 * card always gets a conspicuous top bar; its Open action is disabled
 * UNLESS this console is itself running in development mode
 * (`import.meta.env.DEV`) — the same admission rule
 * `LoadPackageDialog.tsx`'s `isAdmitted()` already applies to loading the
 * package in the first place (`UI-D19`). Gating Open on `devMode` alone,
 * with no console-mode check, made the unsigned path — the one dev-mode
 * admission exists for — catalog-only and never actually openable
 * (task-114-report.md's Follow-ups, "Decide whether an unsigned
 * dev-loaded package should be openable"); this closes that gap rather
 * than reinterpreting it. A production build never sets `DEV`, so an
 * unsigned package there stays exactly as locked out as before.
 */
import { Box, Typography, useTheme } from '@mui/material';

import { t } from '@shared/i18n';
import { Button, Chip, InfoTip, Paper, SolutionGlyph, ToneIcon, Tooltip } from '@shared/ui';
import { Cluster } from '@shared/ui/layout';

import { ActivityIcon, ArrowRightIcon, ServerIcon } from './icons';
import { ReviewMeter } from './ReviewMeter';
import type { SolutionPackageSummary } from './solutionsRepository';

export interface SolutionCardProps {
  solution: SolutionPackageSummary;
  onOpen: (solutionId: string) => void;
}

export function SolutionCard({ solution, onOpen }: SolutionCardProps) {
  const theme = useTheme();
  const dev = solution.devMode === true;
  // Blocked only outside a dev console — see the header comment.
  const openBlocked = dev && !import.meta.env.DEV;

  const openButton = (
    <Button
      size="sm"
      variant="outlined"
      iconRight={<ArrowRightIcon fontSize="small" />}
      disabled={openBlocked}
      onClick={() => {
        if (!openBlocked) {
          onOpen(solution.id);
        }
      }}
    >
      {t('solutionsOpen')}
    </Button>
  );

  return (
    <Paper surface="padded" data-solution-card data-dev-mode={dev}>
      {dev && (
        <Box
          data-dev-banner
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            mx: -2.5,
            mt: -2.5,
            mb: 2,
            px: 2.5,
            py: 1,
            backgroundColor: theme.palette.tone.err.bg,
            color: theme.palette.tone.err.main,
            borderTopLeftRadius: 'inherit',
            borderTopRightRadius: 'inherit',
          }}
        >
          <ToneIcon tone="err" fontSize="small" />
          <Typography variant="caption" sx={{ fontWeight: 600 }}>
            {t('solutionsDevBanner')}
          </Typography>
        </Box>
      )}

      <Box sx={{ display: 'flex', gap: 1.5 }}>
        <Box
          aria-hidden
          sx={{
            width: 44,
            height: 44,
            flex: 'none',
            borderRadius: 1.5,
            display: 'grid',
            placeItems: 'center',
            backgroundColor: solution.color,
            color: theme.palette.getContrastText(solution.color),
          }}
        >
          <SolutionGlyph name={solution.icon} fontSize="small" />
        </Box>
        <Box sx={{ minWidth: 0, flex: '1 1 auto' }}>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 1 }}>
            <Typography variant="h6" component="h2" sx={{ overflowWrap: 'anywhere' }}>
              {solution.name}
            </Typography>
            {solution.entitled ? (
              <Chip tone="ok" dot label={t('solutionsEntitled')} />
            ) : (
              <Chip tone="neutral" label={t('solutionsNotEntitled')} />
            )}
          </Box>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>
            {solution.kind}
          </Typography>
          <Box sx={{ mt: 1.25 }}>
            {dev ? (
              <Cluster gap={0.5}>
                <Chip tone="err" icon={<ToneIcon tone="err" fontSize="small" />} label={t('solutionsUnsigned')} />
                <InfoTip tip={t('solutionsUnsignedTip')} />
              </Cluster>
            ) : (
              <Cluster gap={0.5}>
                <Chip tone="ok" icon={<ToneIcon tone="ok" fontSize="small" />} label={t('solutionsSigned')} />
                <InfoTip
                  tip={t('solutionsSignedTip', { fingerprint: solution.provenance.signature ?? '' })}
                />
              </Cluster>
            )}
          </Box>
        </Box>
      </Box>

      <Box
        component="dl"
        sx={{ display: 'grid', gridTemplateColumns: 'auto 1fr', columnGap: 1.5, rowGap: 0.75, m: 0, mt: 2 }}
      >
        <Typography component="dt" variant="body2" color="text.secondary">
          {t('solutionsPackageVersion')}
        </Typography>
        {/* Every version this one package file serves (FE-118), not a
            single number — an instance is configured against exactly one
            of them, chosen on the declare form. */}
        <Typography component="dd" variant="body2" sx={{ m: 0, fontFamily: 'monospace' }}>
          {solution.packageVersion.join(' · ')}
        </Typography>

        <Typography component="dt" variant="body2" color="text.secondary">
          {t('solutionsAppliesTo')}
        </Typography>
        <Box component="dd" sx={{ m: 0, display: 'flex', alignItems: 'center', gap: 0.5 }}>
          <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
            {solution.appliesTo}
          </Typography>
          <InfoTip tip={t('solutionsAppliesToTip')} />
        </Box>

        <Typography component="dt" variant="body2" color="text.secondary">
          {t('solutionsSource')}
        </Typography>
        <Typography component="dd" variant="body2" sx={{ m: 0, overflowWrap: 'anywhere' }}>
          {solution.authoring.source}
        </Typography>
      </Box>

      <Box sx={{ mt: 2 }}>
        <ReviewMeter authoring={solution.authoring} />
      </Box>

      <Box sx={{ mt: 1.75 }}>
        <Cluster gap={0.5}>
          {solution.components.map((component) => (
            <Box
              key={component}
              component="span"
              sx={{
                fontFamily: 'monospace',
                fontSize: 12,
                px: 1,
                py: 0.25,
                borderRadius: 999,
                backgroundColor: 'action.hover',
              }}
            >
              {component}
            </Box>
          ))}
        </Cluster>
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
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, color: 'text.secondary' }}>
          <ServerIcon fontSize="small" />
          <Typography variant="body2">{t('instanceCount', { count: solution.instanceCount })}</Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, color: 'text.secondary' }}>
          <ActivityIcon fontSize="small" />
          <Typography variant="body2">{solution.telemetryMode}</Typography>
        </Box>
        <Box sx={{ flex: '1 1 auto' }} />
        {openBlocked ? <Tooltip title={t('solutionsOpenDisabledTip')}>{openButton}</Tooltip> : openButton}
      </Box>
    </Paper>
  );
}
