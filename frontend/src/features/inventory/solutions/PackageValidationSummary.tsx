import { Box, SvgIcon, Typography } from '@mui/material';
import type { SvgIconProps } from '@mui/material';
import type { ReactNode } from 'react';
import { useState } from 'react';

import type { PackageComponent, PackageField, PackageValidationResult } from '@contracts/solution-package';
import { t } from '@shared/i18n';
import { Alert, Chip, ToneIcon } from '@shared/ui';
import { Grid, Stack } from '@shared/ui/layout';

export interface PackageValidationSummaryProps {
  result: PackageValidationResult;
}

// Same disclosure chevron `SectionCard`'s own `ExpanderIcon` uses — rotated
// via `expanded`, not swapped for a second icon.
function ExpanderIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M8 5l8 7-8 7V5z" />
    </SvgIcon>
  );
}

/**
 * One declared field's detail row — key, type, and the flags that matter
 * before an operator commits to loading this package. `default` is
 * withheld for a `secret`-flagged field (`I6`'s spirit: a value that reads
 * like a credential is never echoed back, even a package-authored one the
 * uploader already has in front of them).
 */
function FieldDetailRow({ field }: { field: PackageField }) {
  return (
    <Box
      data-package-field
      sx={{
        display: 'flex',
        flexWrap: 'wrap',
        justifyContent: 'space-between',
        alignItems: 'center',
        gap: 1,
        py: 0.75,
      }}
    >
      <Box>
        <Typography variant="body2" sx={{ fontWeight: 600, fontFamily: 'monospace' }}>
          {field.key}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {t('solutionsLoadFieldType', { type: field.type })}
        </Typography>
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
        {field.required === true && <Chip tone="neutral" label={t('solutionsLoadFieldRequired')} />}
        {field.secret === true && <Chip tone="neutral" label={t('solutionsLoadFieldSecret')} />}
        {field.secret !== true && field.default !== undefined && field.default !== null && (
          <Typography variant="caption" color="text.secondary">
            {t('solutionsLoadFieldDefaultValue', { value: String(field.default) })}
          </Typography>
        )}
      </Box>
    </Box>
  );
}

// A vertical list with a rule between items (never after the last one) —
// `@shared/ui/layout`'s own `Stack` takes neither `sx` nor a `divider`
// prop, so this is a plain `Box` doing the same job via a child selector.
function DividedList({ children }: { children: ReactNode }) {
  return (
    <Box
      sx={{
        '& > *:not(:last-child)': { borderBottom: '1px solid', borderColor: 'divider' },
      }}
    >
      {children}
    </Box>
  );
}

function ComponentDetail({ component }: { component: PackageComponent }) {
  const fields = component.fields ?? [];
  const actions = component.actions ?? [];
  const metrics = component.metrics ?? [];

  return (
    <Box sx={{ px: 1.5, pb: 1.5, display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <Box>
        <Typography variant="overline" color="text.secondary">
          {t('solutionsLoadFields')}
        </Typography>
        {fields.length === 0 ? (
          <Typography variant="caption" color="text.secondary" component="div">
            {t('solutionsLoadFieldNone')}
          </Typography>
        ) : (
          <DividedList>
            {fields.map((field) => (
              <FieldDetailRow key={field.key} field={field} />
            ))}
          </DividedList>
        )}
      </Box>

      <Box>
        <Typography variant="overline" color="text.secondary">
          {t('solutionsLoadActions')}
        </Typography>
        {actions.length === 0 ? (
          <Typography variant="caption" color="text.secondary" component="div">
            {t('solutionsLoadActionNone')}
          </Typography>
        ) : (
          <DividedList>
            {actions.map((action) => (
              <Box key={action.key} sx={{ py: 0.75 }}>
                <Typography variant="body2" sx={{ fontWeight: 600, fontFamily: 'monospace' }}>
                  {action.key}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {t('solutionsLoadActionKind', { kind: action.kind })}
                </Typography>
              </Box>
            ))}
          </DividedList>
        )}
      </Box>

      <Box>
        <Typography variant="overline" color="text.secondary">
          {t('solutionsLoadMetrics')}
        </Typography>
        {metrics.length === 0 ? (
          <Typography variant="caption" color="text.secondary" component="div">
            {t('solutionsLoadMetricNone')}
          </Typography>
        ) : (
          <DividedList>
            {metrics.map((metric) => (
              <Box key={metric.name} sx={{ py: 0.75 }}>
                <Typography variant="body2" sx={{ fontWeight: 600, fontFamily: 'monospace' }}>
                  {metric.name}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {t('solutionsLoadMetricMeta', { metricType: metric.metricType, unit: metric.unit })}
                </Typography>
              </Box>
            ))}
          </DividedList>
        )}
      </Box>
    </Box>
  );
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
  // Which component rows are expanded — a plain local `Set<string>`, the
  // same ownership `SectionCard`'s own doc calls out ("owned by the
  // caller"): nothing here is instance state, so it never needs to survive
  // a re-selection the way `ComposerInspector`'s does.
  const [expandedTypes, setExpandedTypes] = useState<ReadonlySet<string>>(new Set());

  function toggle(type: string): void {
    setExpandedTypes((prev) => {
      const next = new Set(prev);
      if (next.has(type)) {
        next.delete(type);
      } else {
        next.add(type);
      }
      return next;
    });
  }

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

  const { summary, package: pkg } = result;
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
        <Typography variant="caption" color="text.secondary">
          {t('solutionsLoadComponentDetailsHint')}
        </Typography>
        {summary.components.map((component) => {
          const expanded = expandedTypes.has(component.type);
          // Always present: `summary.components` and `pkg.components` are
          // built from the same validated document (`validate.ts`), one
          // entry each per component type.
          const full = pkg.components.find((candidate) => candidate.type === component.type);
          return (
            <Box
              key={component.type}
              sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1.5, overflow: 'hidden' }}
            >
              <Box
                component="button"
                type="button"
                onClick={() => toggle(component.type)}
                aria-expanded={expanded}
                sx={{
                  width: '100%',
                  display: 'flex',
                  flexWrap: 'wrap',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  gap: 1,
                  border: 'none',
                  background: 'none',
                  cursor: 'pointer',
                  font: 'inherit',
                  textAlign: 'left',
                  color: 'inherit',
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
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <Typography variant="caption" color="text.secondary">
                    {t('solutionsLoadComponentCounts', {
                      fields: component.fieldCount,
                      actions: component.actionCount,
                      metrics: component.metricCount,
                    })}
                  </Typography>
                  <ExpanderIcon
                    fontSize="small"
                    sx={{ transform: expanded ? 'rotate(90deg)' : 'none', flexShrink: 0 }}
                  />
                </Box>
              </Box>

              {expanded && full !== undefined && (
                <Box sx={{ borderTop: '1px solid', borderColor: 'divider' }}>
                  <ComponentDetail component={full} />
                </Box>
              )}
            </Box>
          );
        })}
      </Stack>

      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        <ToneIcon tone="ok" fontSize="small" />
        <Typography variant="caption">{t('solutionsLoadMetadataValidated')}</Typography>
      </Box>
    </Stack>
  );
}
