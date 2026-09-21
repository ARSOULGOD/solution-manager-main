/**
 * Authoring-review coverage (task 039, UI §A.5). `reviewedRows`/`totalRows`
 * are INVENTED — not in any package schema (`§16 Q10`); flagged at the
 * source in `solutionsRepository.ts`'s re-exported fixture type.
 *
 * Three renderings, deliberately not one continuous gradient: fully
 * reviewed (ok), partial (warn — copy states partial is *normal*, not a
 * quality problem), zero (err — "assembled by automated extraction,
 * unreviewed"). A fully-unreviewed package must not look like a lighter
 * shade of curated, so 0% gets its own tone and its own icon, not just an
 * empty bar.
 */
import { Box, Typography, useTheme } from '@mui/material';

import { t } from '@shared/i18n';
import { Chip, ToneIcon } from '@shared/ui';

export interface ReviewMeterAuthoring {
  reviewedRows: number;
  totalRows: number;
}

export interface ReviewMeterProps {
  authoring: ReviewMeterAuthoring;
}

export function ReviewMeter({ authoring }: ReviewMeterProps) {
  const theme = useTheme();
  const { reviewedRows, totalRows } = authoring;
  const pct = totalRows > 0 ? Math.round((reviewedRows / totalRows) * 100) : 0;

  const chip =
    pct === 100 ? (
      <Chip tone="ok" icon={<ToneIcon tone="ok" fontSize="small" />} label={t('solutionsReviewFully')} />
    ) : pct === 0 ? (
      <Chip tone="err" icon={<ToneIcon tone="err" fontSize="small" />} label={t('solutionsReviewNone')} />
    ) : (
      <Chip tone="warn" label={t('solutionsReviewPartial', { pct })} />
    );

  const caption =
    pct === 100
      ? t('solutionsReviewCaption', {
          reviewed: reviewedRows.toLocaleString(),
          total: totalRows.toLocaleString(),
        })
      : pct === 0
        ? t('solutionsReviewCaptionZero', {
            reviewed: reviewedRows.toLocaleString(),
            total: totalRows.toLocaleString(),
          })
        : t('solutionsReviewCaptionPartial', {
            reviewed: reviewedRows.toLocaleString(),
            total: totalRows.toLocaleString(),
          });

  return (
    <Box data-review-meter>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 1, mb: 0.75 }}>
        <Typography variant="caption" sx={{ fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.02em' }}>
          {t('solutionsReviewHeading')}
        </Typography>
        {chip}
      </Box>
      <Box
        aria-hidden
        sx={{ display: 'flex', height: 6, borderRadius: 999, overflow: 'hidden', bgcolor: theme.palette.tone.warn.bg }}
      >
        <Box
          sx={{
            width: `${String(pct)}%`,
            backgroundColor: pct === 0 ? theme.palette.tone.err.main : theme.palette.tone.ok.main,
          }}
        />
      </Box>
      <Typography variant="caption" color="text.secondary" sx={{ mt: 0.75, display: 'block' }}>
        {caption}
      </Typography>
    </Box>
  );
}
