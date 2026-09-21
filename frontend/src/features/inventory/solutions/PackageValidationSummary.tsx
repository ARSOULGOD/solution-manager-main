import { Box, Typography } from '@mui/material';

import type { PackageValidationResult } from '@contracts/solution-package';
import { t } from '@shared/i18n';
import { Alert, Chip, ToneIcon } from '@shared/ui';
import { Grid, Stack } from '@shared/ui/layout';

export interface PackageValidationSummaryProps {
  result: PackageValidationResult;
}

function CountTile({ label, value }: { label: string; value: number }) {
  return (
    <Box sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1.5, p: 1.5 }}>
      <Typography variant="h6" component="div">
        {value.toLocaleString()}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
    </Box>
  );
}

export function PackageValidationSummary({ result }: PackageValidationSummaryProps) {
  if (!result.valid) {
    return (
      <Stack gap={1.5}>
        <Alert severity="err" title={t('solutionsLoadInvalidTitle')}>
          {result.issues.length === 1
            ? t('solutionsLoadInvalidDetailOne')
            : t('solutionsLoadInvalidDetail', { count: result.issues.length })}
        </Alert>
        <Box
          component="ol"
          aria-label={t('solutionsLoadIssueListLabel')}
          sx={{ m: 0, pl: 3, maxHeight: 240, overflowY: 'auto' }}
        >
          {result.issues.map((validationIssue, index) => (
            <Box
              component="li"
              key={`${validationIssue.code}-${validationIssue.path}-${String(index)}`}
              sx={{ mb: 1 }}
            >
              <Typography
                variant="caption"
                component="div"
                sx={{ fontFamily: 'monospace', fontWeight: 600 }}
              >
                {validationIssue.path}
              </Typography>
              <Typography variant="body2">
                {validationIssue.message}{' '}
                <Typography component="span" variant="caption" color="text.secondary">
                  {t('solutionsLoadGate', { gate: String(validationIssue.gate) })}
                </Typography>
              </Typography>
            </Box>
          ))}
        </Box>
      </Stack>
    );
  }

  const { summary } = result;
  return (
    <Stack gap={2}>
      <Alert severity="ok" title={t('solutionsLoadValidTitle')}>
        {t('solutionsLoadValidDetail')}
      </Alert>

      <Box>
        <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
            {summary.solution}
          </Typography>
          <Chip
            tone="neutral"
            mono
            label={t('solutionsLoadPackageVersion', {
              version: summary.packageVersion.map(String).join(', '),
            })}
          />
        </Box>
        <Typography variant="caption" color="text.secondary">
          {t('solutionsLoadAppliesTo', { range: summary.appliesTo })}
        </Typography>
      </Box>

      <Grid columns={{ xs: 2, sm: 4 }} gap={1}>
        <CountTile label={t('solutionsLoadComponents')} value={summary.componentCount} />
        <CountTile label={t('solutionsLoadFields')} value={summary.fieldCount} />
        <CountTile label={t('solutionsLoadActions')} value={summary.actionCount} />
        <CountTile label={t('solutionsLoadMetrics')} value={summary.metricCount} />
      </Grid>

      <Stack gap={1}>
        {summary.components.map((component) => (
          <Box
            key={component.type}
            sx={{
              display: 'flex',
              flexWrap: 'wrap',
              justifyContent: 'space-between',
              alignItems: 'center',
              gap: 1,
              border: '1px solid',
              borderColor: 'divider',
              borderRadius: 1.5,
              px: 1.5,
              py: 1,
            }}
          >
            <Box>
              <Typography variant="body2" sx={{ fontWeight: 600, fontFamily: 'monospace' }}>
                {component.type}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {component.cardinality}
              </Typography>
            </Box>
            <Typography variant="caption" color="text.secondary">
              {t('solutionsLoadComponentCounts', {
                fields: component.fieldCount,
                actions: component.actionCount,
                metrics: component.metricCount,
              })}
            </Typography>
          </Box>
        ))}
      </Stack>

      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        <ToneIcon tone="ok" fontSize="small" />
        <Typography variant="caption">{t('solutionsLoadMetadataValidated')}</Typography>
      </Box>
    </Stack>
  );
}
