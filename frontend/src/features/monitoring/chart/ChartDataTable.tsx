/**
 * FE-075's tabular alternative (`dynamic-metrics` design §18): "a
 * tabular data alternative for every chart, keyboard-reachable, with a
 * text summary". `ChartRenderer` opens this on `Enter` or via its visible
 * "Open data table" button — this component owns none of that trigger
 * logic, only the `open`/`onClose`-driven `Dialog` and its content.
 *
 * One real HTML `<table>` per series rather than one union-timestamp grid
 * across all series: different series can legitimately have points at
 * different instants (a gap is a per-series absence, `dynamic-metrics`
 * §11.2 obligation 1), so a shared-row grid would need a "no data" cell
 * convention of its own — the same zero-fill-adjacent trap this design
 * spends obligation 1 ruling out for the chart itself. A per-series table
 * has no such cell to invent: every row is a real, rendered point.
 *
 * **Task 109.** The table body is `ChartDataTableContent`, exported
 * separately from the `Dialog` wrapper below `sm` needs — below that
 * width the tabular alternative is `ChartRenderer`'s own primary
 * affordance, rendered inline rather than behind a modal (design §11/§18
 * — "a 320px canvas is not readable"). Both call sites render the exact
 * same content function, so a per-series stat or column can never drift
 * between the modal and the inline path.
 */
import { Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

import type { ChartModel } from '@contracts/metrics/types';
import { formatDateTime, formatUnitValue, timeZoneLabel } from '@shared/format';
import { t } from '@shared/i18n';
import { Chip, Dialog } from '@shared/ui';
import { Stack } from '@shared/ui/layout';

import { STATE_TAG } from './chartStateTags';
import { summarizeChart, type SeriesSummary } from './chartSummary';

export interface ChartDataTableProps {
  model: ChartModel;
  open: boolean;
  onClose: () => void;
  /** The widget title — falls back to the metric's own name when the caller has none (matches `ChartRenderer`'s `ariaLabel` fallback). */
  title?: string;
}

/** `min`/`max`/`last` come from `chartSummary.ts`'s `minMaxLast` — always all-null or all-numeric together, never a mix. */
function formatSeriesStat(seriesSummary: SeriesSummary, unit: string | null): string {
  if (seriesSummary.min === null || seriesSummary.max === null || seriesSummary.last === null) {
    return t('chartDataTableSeriesStatEmpty');
  }
  return t('chartDataTableSeriesStat', {
    min: formatUnitValue(seriesSummary.min, unit),
    max: formatUnitValue(seriesSummary.max, unit),
    last: formatUnitValue(seriesSummary.last, unit),
  });
}

/** The summary + per-series tables, with no `Dialog` of its own — see this file's header. */
export function ChartDataTableContent({ model }: { model: ChartModel }) {
  const summary = summarizeChart(model);
  const rangeText =
    summary.rangeFrom !== null && summary.rangeTo !== null
      ? t('chartDataTableSummaryRange', {
          from: formatDateTime(summary.rangeFrom),
          to: formatDateTime(summary.rangeTo),
          zone: timeZoneLabel(),
        })
      : t('chartDataTableSummaryNoRange');

  return (
    <Stack gap={3}>
      <Stack gap={0.5}>
        <Typography variant="body2">{rangeText}</Typography>
        <Typography variant="body2">{t('chartDataTableSeriesCount', { count: summary.seriesCount })}</Typography>
      </Stack>

      {model.series.map((chartSeries, index) => {
        const seriesSummary = summary.series[index];
        const tag = STATE_TAG[chartSeries.state];
        const statText =
          seriesSummary === undefined ? t('chartDataTableSeriesStatEmpty') : formatSeriesStat(seriesSummary, model.metric.unit);

        return (
          <Stack key={`${chartSeries.seriesRef.instanceId}:${chartSeries.seriesRef.placementId ?? index}`} gap={1}>
            <Stack gap={0.5}>
              <Typography
                variant="subtitle2"
                component="h3"
                sx={{ display: 'flex', alignItems: 'center', gap: 1 }}
              >
                {chartSeries.label}
                {tag !== undefined && <Chip label={tag.label} tone={tag.tone} />}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {statText}
              </Typography>
            </Stack>

            {chartSeries.points.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                {t('chartDataTableEmptySeries')}
              </Typography>
            ) : (
              <Table size="small" aria-label={chartSeries.label}>
                <TableHead>
                  <TableRow>
                    <TableCell>{t('chartDataTableColumnTimestamp')}</TableCell>
                    <TableCell>{t('chartDataTableColumnValue')}</TableCell>
                    <TableCell>{t('chartDataTableColumnCoverage')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {chartSeries.points.map((point) => (
                    <TableRow key={point.ts}>
                      <TableCell>{formatDateTime(point.ts)}</TableCell>
                      <TableCell>{formatUnitValue(point.value, model.metric.unit)}</TableCell>
                      <TableCell>
                        {point.lowCoverage
                          ? t('chartDataTableLowCoverage')
                          : t('chartDataTableFullCoverage')}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </Stack>
        );
      })}
    </Stack>
  );
}

export function ChartDataTable({ model, open, onClose, title }: ChartDataTableProps) {
  const dialogTitle = t('chartDataTableTitle', { metric: title ?? model.metric.name });

  // `ChartRenderer` renders this on every keystroke of its cursor (the
  // live-region announcement re-renders the whole tree); skip building
  // every series' table — up to thousands of formatted rows — while the
  // dialog isn't even shown. MUI's own `Dialog`/`Modal` skips mounting
  // closed content into the DOM, but that's downstream of this function
  // already having run, so the guard has to live here.
  if (!open) {
    return (
      <Dialog open={false} title={dialogTitle} onClose={onClose} maxWidth="md">
        {null}
      </Dialog>
    );
  }

  return (
    <Dialog open={open} title={dialogTitle} onClose={onClose} maxWidth="md">
      <ChartDataTableContent model={model} />
    </Dialog>
  );
}
