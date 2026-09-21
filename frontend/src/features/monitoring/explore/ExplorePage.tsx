/**
 * Explore's page shell: the five-step gated control column plus the
 * result column (task 077, Dec `DM-D9` `DM-D23`, Design §11,
 * `dynamic-metrics` §9.1/§11.1, UI §A.10 — layout mirrors
 * `docs/design/frontend/reference/app/explore.jsx`'s `.explore` grid,
 * `340px 1fr`).
 *
 * Task 078 (`InstanceSelector`, Dec `DM-D7`, `dynamic-metrics` §11.4) fills
 * step 2 with the real control, and step 3's `seriesBy` toggle alongside
 * it. Task 079 (`MetricSelector`, Dec `DM-D23` `SM-D30` `UI-D5`) fills step
 * 1 — real `metricState` now comes from the catalog entry matching
 * whichever metric is selected, not a hardcoded `null`, so steps 2-4 unlock
 * as soon as a plottable or type-suspect metric is chosen (a `needs-typing`
 * entry cannot itself be selected, per `MetricSelector`'s own disabled
 * rows). Task 080 (`AggregationControl`/`GroupByControl`, Dec `DM-D8`
 * `DM-D22`, `dynamic-metrics` §5.7) fills steps 4 and 5 — real
 * `acrossInstances` now drives step 5's lock, not a hardcoded `'none'`, and
 * `InstanceSelector`'s "Aggregate into one line" escape route is wired for
 * real. Selecting a different metric resets both back to their defaults,
 * since a stale `groupBy` key or aggregation function can be invalid for
 * the newly-selected metric's own type/`labelKeys`. The selection and
 * aggregation state this page owns is `ExploreView`'s local "draft widget
 * config" (§9.2's state table), not committed anywhere until Save (task
 * not yet built) exists to commit it. Task 081 (`TimeRangePicker`,
 * `ResolutionControl`, Dec `DM-D10` `SM-D31`) fills the result column's own
 * toolbar — range and resolution are real state now, not the placeholder
 * "absolute · resolution auto" caption the reference hardcodes.
 *
 * **No mark toggle exists anywhere yet** — no `FE-*` task claims one, so
 * `mark` below is hardcoded to `'line'`, the only mark `DM-D22`'s forcing
 * rule never triggers for. `AggregationControl`'s forcing logic is real
 * and unit-tested against a real `mark` prop; it simply has no live
 * trigger in this running app until a mark control lands. Flagged, not
 * silently decided — same posture this file already uses for the missing
 * `EntityTypeSelector`, below.
 *
 * Routed at `/solutions/:sol/instances/:id/monitoring/explore`
 * (`ROUTE_PATHS.explore`, task 106) via `src/app/routes/ExploreRoute.tsx`,
 * which now also mounts `MetricsProvider` — this page's first use of
 * `useMetrics()`/`useCatalog` (tasks 068/069), so nothing mounted it
 * before. That route is a static sibling of `instanceCategory`, not one of
 * its `designer`/`governance`/`monitoring` categories, so this page does
 * not render inside `InstanceShell`/`InstanceHeader` — no tabs, no
 * instance health chip. Matching that chrome (the reference's own `Wrap`
 * wraps `ExploreScreen` in its `InstanceHeader`) would mean re-fetching
 * the instance and duplicating `InstanceShell`'s query/skeleton/error
 * handling for a component that isn't itself an instance category — out
 * of scope for both this task and 077. Flagged as an open residual, not
 * silently decided.
 *
 * **Entity type: no `EntityTypeSelector` exists or is scheduled** — the
 * design's own component tree (§9.1) lists one, but no `FE-*` task claims
 * it. This page uses the first entity type `useEntityTypes()` resolves
 * (today, fixture-backed, always the one `cache-instance` entry) rather
 * than inventing a picker out of scope. Flagged, not silently decided.
 *
 * Task 083 (draft persistence, Dec `FE-D21` `DM-D9` `DM-D19`) splits this
 * page's own state across two lifetimes instead of one flat `useState`
 * block: instance selection and time range are URL state (`FE-D6`) via
 * `useSearchParams` — reload-safe and shareable, same pattern
 * `InstancesPage.tsx`'s own `?sel=`/`?focus=` already set. The rest of the
 * draft — metric, seriesBy, aggregation, group by, resolution — is too
 * easily kilobytes of query shape for a URL, so it lives in per-tab
 * `sessionStorage` keyed by the URL's own `?draft=<id>` (`exploreDraftStore.ts`).
 * That id is minted once per visit and written back into the URL so a
 * reload restores the SAME draft rather than starting a new one; the
 * sessionStorage entry is discarded when this page unmounts UNLESS the
 * operator saved it as a widget first (`savedRef` below) — task 084 is
 * what makes "unless saved" a real branch rather than always-discard.
 *
 * Task 084 (`SaveWidgetDialog`, widget config schema, Dec `DM-D6` `DM-D12`
 * `DM-D21` `SM-D31`, Conflict C7) adds the "Save as widget" trigger the
 * reference's own toolbar carries. `buildWidgetConfig` assembles a `§7`
 * document from this page's own resolved query/mark/range/resolution — the
 * SAME values `request` below is built from, so the saved widget and the
 * chart on screen can never disagree. Saving posts through
 * `dashboardRepository.saveWidget` (task 033, reconciled to the real
 * schema by this task) against `defaultDashboardId(instanceId)` — an
 * INVENTED, deterministic id, because the contract has no route to look
 * up an instance's dashboard (§16 Q15, stated on the dialog itself).
 */
import { Box, Typography } from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';

import { buildQuery, type WidgetQueryConfig } from '@contracts/metrics/buildQuery';
import type { Mark } from '@contracts/metrics/types';
import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import type { MessageKey } from '@shared/i18n';
import { Button, Tooltip, ToggleGroup } from '@shared/ui';
import { pushToast } from '@shared/ui/Toaster';

import { defaultDashboardId } from '../dashboardRepository';
import { useMetrics } from '../MetricsProvider';
import type { AcrossInstances, QueryRequest, SeriesBy } from '../metricsRepository';
import { MonitoringTabs } from '../MonitoringTabs';
import { useEntityTypes, useMetricCatalog, useMetricInstances } from '../useCatalog';
import { SaveWidgetDialog } from '../widget/SaveWidgetDialog';
import { buildWidgetConfig, type WidgetConfigV1 } from '../widget/widgetConfig';

import { AggregationControl } from './AggregationControl';
import { ControlStep, type ControlStepBaseProps } from './ControlStep';
import {
  discardExploreDraft,
  generateExploreDraftId,
  loadExploreDraft,
  saveExploreDraft,
} from './exploreDraftStore';
import { evaluateExploreGating, type ExploreLockReason, type StepLock } from './exploreGating';
import { ExploreResult } from './ExploreResult';
import { GroupByControl } from './GroupByControl';
import { InstanceSelector } from './InstanceSelector';
import { MetricSelector } from './MetricSelector';
import { ResolutionControl, type Resolution } from './ResolutionControl';
import { isTimeRangePresetKey, resolveTimeRange, type TimeRangeValue } from './timeRangeOptions';
import { TimeRangePicker } from './TimeRangePicker';

/** No mark toggle exists yet (see this file's header comment) — `'line'` is the only mark `DM-D22` never forces. */
const MARK: Mark = 'line';

export interface ExplorePageProps {
  solutionId: string;
  instanceId: string;
}

// `as const satisfies` keeps each value at its own literal key type — a
// plain `Record<ExploreLockReason, MessageKey>` annotation would widen
// every value to the full `MessageKey` union, and `t()` then can't tell
// these are plain strings rather than possibly-plural messages.
const LOCK_REASON_KEY = {
  'metric-not-selected': 'exploreLockReasonMetricNotSelected',
  'metric-needs-typing': 'exploreLockReasonMetricNeedsTyping',
  'aggregation-not-set': 'exploreLockReasonAggregationNotSet',
} as const satisfies Record<ExploreLockReason, MessageKey>;

// Feature code may not import `@app/routes` (module boundaries) — same
// hand-written-template pattern `DashboardView.tsx` uses for this shape.
function monitoringHref(solutionId: string, instanceId: string): string {
  return `/solutions/${solutionId}/instances/${instanceId}/monitoring`;
}

function describeSaveError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return t('errorGeneric');
}

// One place for the "render locked xor unlocked" branch so each of the
// five call sites below stays a flat, readable prop list instead of a
// spread of a discriminated union (which `ControlStepProps` is, on
// purpose — see `ControlStep.tsx`).
function GatedStep({ lock, ...rest }: ControlStepBaseProps & { lock: StepLock }) {
  if (lock.locked) {
    return <ControlStep {...rest} locked lockedReason={t(LOCK_REASON_KEY[lock.reason])} />;
  }
  return <ControlStep {...rest} />;
}

export function ExplorePage({ solutionId, instanceId }: ExplorePageProps) {
  const { capabilities, metricsRepository, dashboardRepository } = useMetrics();
  // No `EntityTypeSelector` exists (see this file's header comment) — the
  // first resolved entity type stands in until one does.
  const { data: entityTypes } = useEntityTypes();
  const entityType = entityTypes?.[0]?.id ?? '';
  const { data: instancePage } = useMetricInstances(entityType);
  const instances = instancePage?.items ?? [];
  // "No hook infers, filters or drops an entry" (`useCatalog.ts`) — every
  // state renders side by side in `MetricSelector`, which does its own
  // per-state rendering (disabled `needs-typing`, warned `type-suspect`).
  const { data: catalogData } = useMetricCatalog();
  const catalog = catalogData ?? [];

  // `ExploreView`'s local draft config (§9.2's state table). Selection and
  // range are URL state; the rest is the sessionStorage document — see
  // this file's header and `exploreDraftStore.ts`.
  const [searchParams, setSearchParams] = useSearchParams();

  // Minted once per visit, lazily, so a re-render never re-mints it. A
  // reload keeps the SAME id (it's already in the URL); a fresh visit
  // (no `?draft=`) starts a new one.
  const [draftId] = useState(() => searchParams.get('draft') ?? generateExploreDraftId());
  const [initialDraft] = useState(() => loadExploreDraft(draftId));

  const selectedInstanceIds = useMemo(() => {
    const raw = searchParams.get('sel');
    return raw === null ? [] : raw.split(',').filter((id) => id !== '');
  }, [searchParams]);

  const timeRange: TimeRangeValue = useMemo(() => {
    const range = searchParams.get('range');
    const from = searchParams.get('from');
    const to = searchParams.get('to');
    if (range === 'custom' && from !== null && to !== null) {
      return { kind: 'custom', from, to };
    }
    if (range !== null && isTimeRangePresetKey(range)) {
      return { kind: 'preset', preset: range };
    }
    return { kind: 'preset', preset: '24h' };
  }, [searchParams]);

  const [selectedMetric, setSelectedMetric] = useState<string | null>(initialDraft?.metric ?? null);
  const [seriesBy, setSeriesBy] = useState<SeriesBy>(initialDraft?.seriesBy ?? 'instance');
  const [acrossInstances, setAcrossInstances] = useState<AcrossInstances>(
    initialDraft?.acrossInstances ?? 'none',
  );
  const [groupBy, setGroupBy] = useState<readonly string[]>(initialDraft?.groupBy ?? []);
  const [resolution, setResolution] = useState<Resolution>(initialDraft?.resolution ?? 'auto');

  // Writes `?draft=<id>` into the URL the first time this page has no id
  // of its own yet — `replace`, so it never adds a history entry a Back
  // press would notice.
  useEffect(() => {
    if (searchParams.get('draft') !== draftId) {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          next.set('draft', draftId);
          return next;
        },
        { replace: true },
      );
    }
  }, [draftId, searchParams, setSearchParams]);

  // Persists the sessionStorage half of the draft on every change.
  useEffect(() => {
    saveExploreDraft(draftId, {
      metric: selectedMetric,
      seriesBy,
      acrossInstances,
      groupBy,
      resolution,
    });
  }, [draftId, selectedMetric, seriesBy, acrossInstances, groupBy, resolution]);

  // `DM-D9` — exploration is discarded on navigate-away UNLESS saved.
  // `savedRef` is set once `saveWidgetMutation` succeeds (below); a ref,
  // not state, because only the unmount cleanup below ever reads it, and
  // it must read the LATEST value at unmount time regardless of when the
  // save happened. A reload never reaches this at all — the browser tears
  // the page down without running React's cleanup.
  const savedRef = useRef(false);
  useEffect(() => {
    return () => {
      if (!savedRef.current) {
        discardExploreDraft(draftId);
      }
    };
  }, [draftId]);

  function handleInstanceSelectionChange(ids: readonly string[]): void {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (ids.length > 0) {
          next.set('sel', ids.join(','));
        } else {
          next.delete('sel');
        }
        return next;
      },
      { replace: true },
    );
  }

  function handleTimeRangeChange(value: TimeRangeValue): void {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value.kind === 'preset') {
          next.set('range', value.preset);
          next.delete('from');
          next.delete('to');
        } else {
          next.set('range', 'custom');
          next.set('from', value.from);
          next.set('to', value.to);
        }
        return next;
      },
      { replace: true },
    );
  }

  const selectedEntry = catalog.find((entry) => entry.name === selectedMetric) ?? null;
  const locks = evaluateExploreGating({
    metricState: selectedEntry?.state ?? null,
    acrossInstances,
  });

  // A stale `groupBy` key or aggregation function can be invalid for a
  // newly-selected metric's own `labelKeys`/`metricType` — reset both
  // whenever the metric selection changes, rather than carrying a silently
  // wrong draft across metrics.
  function handleMetricChange(name: string) {
    setSelectedMetric(name);
    setAcrossInstances('none');
    setGroupBy([]);
  }

  // No `LabelFilter` control exists or is scheduled by any task — this
  // escape route stays a genuine no-op; `InstanceSelector`'s own tests
  // exercise the callback contract directly.
  function handleNarrowByLabel() {}
  // Real as of task 080 — matches the reference's own `setAggInst("avg")`.
  function handleAggregate() {
    setAcrossInstances('avg');
  }

  // Task 082: the real query, built once a metric AND at least one instance
  // are selected — `null` otherwise, which is `ExploreResult`'s own signal
  // for "empty selection" rather than issuing a request that can only 422.
  // The absolute window comes from `resolveTimeRange` (task 081) rather
  // than handing `buildQuery` a `'relative'` range — `TimeRangePicker`
  // already resolved presets through its OWN duration table, and letting
  // `buildQuery`'s separate one resolve the same preset a second time is
  // exactly the kind of two-copies-that-can-drift `chartStateTags.ts`
  // (task 074) warns against elsewhere in this feature. Memoized on
  // `timeRange` alone — `resolveTimeRange` calls `Date.now()` for a preset,
  // and recomputing it on every unrelated re-render (an aggregation toggle,
  // say) would mint a new `to` timestamp each time, which changes the
  // canonical query hash and refetches for no reason.
  const absoluteRange = useMemo(() => resolveTimeRange(timeRange), [timeRange]);
  const widgetQueryConfig: WidgetQueryConfig | null =
    selectedMetric !== null && selectedInstanceIds.length > 0
      ? {
          metric: selectedMetric,
          selector: { entityType, instanceIds: selectedInstanceIds },
          seriesBy,
          aggregation: { acrossReplicas: 'auto', acrossInstances },
          ...(groupBy.length > 0 ? { groupBy } : {}),
        }
      : null;
  const request: QueryRequest | null =
    widgetQueryConfig !== null
      ? buildQuery(
          widgetQueryConfig,
          {
            range: { kind: 'absolute', from: absoluteRange.from, to: absoluteRange.to },
            resolution,
          },
          { features: { forceRaw: capabilities.features.forceRaw } },
        )
      : null;

  // Task 084: the same `widgetQueryConfig`/`MARK`/`timeRange`/`resolution`
  // `request` above is built from — never a second, independently-resolved
  // copy, so the saved widget and the chart on screen cannot disagree.
  // `null` for the same reason `request` is: nothing to save before a
  // metric and an instance are both chosen. `title` starts empty;
  // `SaveWidgetDialog` is the one place that fills it in.
  const widgetDraft =
    widgetQueryConfig !== null
      ? buildWidgetConfig({
          title: '',
          mark: MARK,
          query: widgetQueryConfig,
          range: timeRange,
          resolution,
        })
      : null;

  const [saveDialogOpen, setSaveDialogOpen] = useState(false);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const saveWidgetMutation = useMutation({
    mutationFn: (config: WidgetConfigV1) =>
      dashboardRepository.saveWidget(defaultDashboardId(instanceId), {
        ...config,
        layer: 'shared',
      }),
    onSuccess: ({ widget, correlationId }) => {
      pushToast({
        title: t('exploreSaveWidgetSucceededTitle'),
        detail: t('exploreSaveWidgetSucceededDetail', { title: widget.title }),
        correlationId,
      });
      savedRef.current = true;
      setSaveDialogOpen(false);
      saveWidgetMutation.reset();
      // Task 132 — take the operator to the board so they SEE the widget
      // land. The toast alone left them on Explore, with no way to tell
      // whether anything had actually been added; the fixture happily
      // reported success while discarding the widget, and nothing on
      // screen contradicted it. Invalidate first, so the dashboard query
      // refetches rather than serving a cached document without the new
      // widget in it.
      void queryClient.invalidateQueries({
        queryKey: queryKeys.dashboards.detail(defaultDashboardId(instanceId)),
      });
      void navigate(monitoringHref(solutionId, instanceId));
    },
    // No `onError`: the dialog stays open and renders the failure in
    // place (design §16), same posture `DeclareInstanceDialog` uses.
  });

  return (
    // Task 130 — the Dashboards/Explore bar. It replaces the "← Monitoring"
    // link this page used to carry: the bar navigates both ways, and does
    // so from a fixed place on both screens.
    <MonitoringTabs solutionId={solutionId} instanceId={instanceId}>
      <Box
        sx={(theme) => ({
          display: 'grid',
          gridTemplateColumns: '1fr',
          gap: '18px',
          minWidth: 0,
          mt: '14px',
          [theme.breakpoints.up('lg')]: {
            gridTemplateColumns: '340px 1fr',
            alignItems: 'start',
          },
        })}
      >
        <Box
          sx={(theme) => ({
            minWidth: 0,
            [theme.breakpoints.up('lg')]: {
              borderRight: `1px solid ${theme.palette.divider}`,
              pr: '18px',
            },
          })}
        >
          {/* The "← Monitoring" link that sat here is gone (task 130): the
            sub-tab bar above is a permanent, two-way route between the two
            screens, so a one-way back link is now a second, weaker copy of
            the same affordance. */}
          <Box sx={{ mb: '14px' }}>
            <Typography variant="h6" component="h2">
              {t('exploreTitle')}
            </Typography>
          </Box>

          {/* `FE-D21` — state the sharing limit rather than imply it away
            (R1 finding 3). No "Save as widget" affordance exists yet
            (task 084), so this stays a stated note, not a dangling link. */}
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ display: 'block', mb: '14px' }}
          >
            {t('exploreDraftShareLimitationNote')}
          </Typography>

          <ControlStep
            step={1}
            title={t('exploreStepMetricTitle')}
            subtitle={t('exploreStepMetricSubtitle')}
          >
            <MetricSelector
              catalog={catalog}
              selected={selectedMetric}
              onSelectedChange={handleMetricChange}
              metricsRepository={metricsRepository}
            />
          </ControlStep>

          <GatedStep
            lock={locks.instances}
            step={2}
            title={t('exploreStepInstancesTitle')}
            subtitle={t('exploreStepInstancesSubtitle')}
          >
            <InstanceSelector
              instances={instances}
              selected={selectedInstanceIds}
              onSelectedChange={handleInstanceSelectionChange}
              seriesBy={seriesBy}
              cap={capabilities.maxSeriesPerWidget}
              disabled={locks.instances.locked}
              onNarrowByLabel={handleNarrowByLabel}
              onAggregate={handleAggregate}
            />
          </GatedStep>

          <GatedStep lock={locks.seriesBy} step={3} title={t('exploreStepSeriesByTitle')}>
            <ToggleGroup
              value={seriesBy}
              onChange={setSeriesBy}
              disabled={locks.seriesBy.locked}
              aria-label={t('exploreStepSeriesByTitle')}
              options={[
                { value: 'instance', label: t('exploreSeriesByInstance') },
                { value: 'replica', label: t('exploreSeriesByReplica') },
              ]}
            />
          </GatedStep>

          <GatedStep lock={locks.aggregation} step={4} title={t('exploreStepAggregationTitle')}>
            <AggregationControl
              value={acrossInstances}
              onChange={setAcrossInstances}
              metricType={selectedEntry?.metricType ?? null}
              capabilityAggregations={capabilities.aggregations}
              mark={MARK}
              disabled={locks.aggregation.locked}
            />
          </GatedStep>

          <GatedStep
            lock={locks.groupBy}
            step={5}
            title={t('exploreStepGroupByTitle')}
            subtitle={t('exploreStepGroupBySubtitle')}
          >
            <GroupByControl
              value={groupBy}
              onChange={setGroupBy}
              labelKeys={selectedEntry?.labelKeys ?? []}
              disabled={locks.groupBy.locked}
            />
          </GatedStep>
        </Box>

        <Box sx={{ minWidth: 0 }}>
          <Box
            sx={{
              display: 'flex',
              gap: '14px',
              flexWrap: 'wrap',
              alignItems: 'flex-start',
              mb: '14px',
            }}
          >
            <TimeRangePicker
              value={timeRange}
              onChange={handleTimeRangeChange}
              tiers={capabilities.tiers}
            />
            <ResolutionControl
              value={resolution}
              onChange={setResolution}
              forceRawAdvertised={capabilities.features.forceRaw}
            />
            <Box sx={{ flexGrow: 1 }} />
            {widgetDraft === null ? (
              <Tooltip title={t('exploreSaveWidgetDisabledTip')}>
                <span>
                  <Button variant="outlined" size="sm" disabled>
                    {t('exploreSaveWidgetTrigger')}
                  </Button>
                </span>
              </Tooltip>
            ) : (
              <Button variant="outlined" size="sm" onClick={() => setSaveDialogOpen(true)}>
                {t('exploreSaveWidgetTrigger')}
              </Button>
            )}
          </Box>

          <ExploreResult
            metricsRepository={metricsRepository}
            metricName={selectedMetric}
            hasInstances={selectedInstanceIds.length > 0}
            request={request}
          />
        </Box>

        {/* Mounted only while open, so each opening starts from a fresh
          title — a cancelled draft is never waiting for the operator the
          next time they press Save (same posture `DeclareInstanceDialog`
          sets in `InstancesPage.tsx`). */}
        {saveDialogOpen && widgetDraft !== null && (
          <SaveWidgetDialog
            open
            draft={widgetDraft}
            onClose={() => {
              setSaveDialogOpen(false);
              saveWidgetMutation.reset();
            }}
            onSubmit={(config) => saveWidgetMutation.mutate(config)}
            submitting={saveWidgetMutation.isPending}
            {...(saveWidgetMutation.isError
              ? { errorMessage: describeSaveError(saveWidgetMutation.error) }
              : {})}
          />
        )}
      </Box>
    </MonitoringTabs>
  );
}
