/**
 * Four stat tiles (task 038, UI-D8, UI §A.4). Three link into a filtered
 * view; "Need attention now" does not — the feed directly below already
 * names the specific thing, so a fourth destination would just repeat it
 * (ported from the reference's own `home.jsx`: only three `Stat`s carry
 * an `onClick`).
 *
 * The "Config staged" link targets one specific fixture instance
 * (`cache-stage-01`) because there is no URL-addressable "staged" filter
 * in this app's design (FE-042: filters are component state, only
 * selection/focus are URL state) — ported verbatim from the reference for
 * the same reason, not invented here.
 */
import { Box, ButtonBase, Typography, useTheme } from '@mui/material';
import { Link } from 'react-router';

import { t } from '@shared/i18n';
import { Grid } from '@shared/ui/layout';

import type { HomeSummary } from './homeRepository';

const SOLUTIONS_HREF = '/solutions';
const INSTANCES_HREF = '/solutions/cache/instances';
const CONFIG_STAGED_HREF = '/solutions/cache/instances?focus=cache-stage-01';

function formatCount(value: number | null): string {
  return value === null ? '—' : String(value);
}

interface TileProps {
  value: string;
  label: string;
  tone?: 'err' | 'warn';
  href?: string;
}

function Tile({ value, label, tone, href }: TileProps) {
  const theme = useTheme();
  const valueColor = tone !== undefined ? theme.palette.tone[tone].main : theme.palette.text.primary;

  const content = (
    <>
      <Typography sx={{ fontSize: 30, fontWeight: 600, letterSpacing: '-0.01em', lineHeight: 1, color: valueColor }}>
        {value}
      </Typography>
      <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
        {label}
      </Typography>
    </>
  );

  const cardSx = {
    display: 'block',
    width: '100%',
    textAlign: 'left' as const,
    p: 2,
    borderRadius: 1.5,
    borderWidth: 1,
    borderStyle: 'solid',
    borderColor: 'divider',
    backgroundColor: 'background.paper',
  };

  if (href === undefined) {
    return (
      <Box data-stat-tile sx={cardSx}>
        {content}
      </Box>
    );
  }

  return (
    <ButtonBase
      component={Link}
      to={href}
      data-stat-tile
      sx={(t2) => ({
        ...cardSx,
        transition: t2.transitions.create(['box-shadow', 'border-color']),
        '&:hover': { boxShadow: t2.shadows[8] },
      })}
    >
      {content}
    </ButtonBase>
  );
}

export interface StatStripProps {
  summary: HomeSummary;
}

export function StatStrip({ summary }: StatStripProps) {
  const instancesValue =
    summary.partial != null && summary.instancesDeclared !== null
      ? `${String(summary.instancesDeclared)} +1?`
      : formatCount(summary.instancesDeclared);

  return (
    <Grid data-stat-strip columns={{ xs: 1, sm: 2, lg: 4 }}>
      <Tile value={formatCount(summary.solutionsManaged)} label={t('homeStatSolutions')} href={SOLUTIONS_HREF} />
      <Tile value={instancesValue} label={t('homeStatInstances')} href={INSTANCES_HREF} />
      <Tile value={formatCount(summary.needAttentionNow)} label={t('homeStatAttention')} tone="err" />
      <Tile
        value={formatCount(summary.configStagedNotApplied)}
        label={t('homeStatConfigStaged')}
        tone="warn"
        href={CONFIG_STAGED_HREF}
      />
    </Grid>
  );
}
