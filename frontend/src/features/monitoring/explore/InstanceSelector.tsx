/**
 * Step 2's real control (task 078, Dec `DM-D7` `DM-D23`, `dynamic-metrics`
 * §11.4, Conflict C6, UI §A.10.3 — mirrors `docs/design/frontend/reference
 * /app/explore.jsx`'s `.inst-list`/`.capmeter`/`.finding` markup). This
 * task's own objective: **the cap is enforced before the query, not after
 * it fails.**
 *
 * Presentational and controlled — selection, `seriesBy` and the escape-
 * route actions are all owned by the caller (`ExplorePage`), the same
 * split `ControlStep` (task 077) establishes for every real control that
 * fills a gated step. The projection arithmetic itself lives in
 * `instanceProjection.ts`, not here, so "projection counts series" is
 * testable without rendering anything.
 *
 * **Deliberate correction of the reference.** `explore.jsx`'s `atCap` gate
 * is flat — once the running total reaches the cap, EVERY unselected
 * instance disables, regardless of how many series it would individually
 * add. With instances of differing replica counts that both under- and
 * over-disables: a large instance can still be picked right up to the
 * boundary and then fail at query time, which this task's acceptance box
 * explicitly forbids. `wouldExceedCap` (`instanceProjection.ts`) checks
 * each unselected row against its own contribution instead.
 */
import { Box, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';

import { t } from '@shared/i18n';
import { Alert, Button, Checkbox } from '@shared/ui';

import type { SeriesBy } from '../metricsRepository';

import { projectSeriesCount, seriesCountFor, wouldExceedCap } from './instanceProjection';

export interface InstanceSelectorInstance {
  id: string;
  label: string;
  /** This instance's replica count — what `seriesBy: 'replica'` sums (`DM-D7`). */
  replicaCount: number;
}

export interface InstanceSelectorProps {
  instances: readonly InstanceSelectorInstance[];
  selected: readonly string[];
  onSelectedChange: (ids: readonly string[]) => void;
  seriesBy: SeriesBy;
  /** `capabilities.maxSeriesPerWidget`, fail-closed to 10 (task 068) — never a literal here. */
  cap: number;
  /** True while this step is gate-locked (task 077's `ControlStep` contract: the caller disables its own real controls). */
  disabled?: boolean;
  onNarrowByLabel: () => void;
  onAggregate: () => void;
}

function Meter({ projected, cap }: { projected: number; cap: number }) {
  const theme = useTheme();
  const over = projected > cap;
  const fillPercent = Math.min(100, (projected / cap) * 100);

  return (
    <Box
      aria-hidden
      sx={{
        height: 7,
        borderRadius: 99,
        overflow: 'hidden',
        backgroundColor: theme.palette.action.hover,
      }}
    >
      <Box
        sx={{
          height: '100%',
          width: `${fillPercent}%`,
          backgroundColor: over ? theme.palette.tone.err.main : theme.palette.primary.main,
        }}
      />
    </Box>
  );
}

export function InstanceSelector({
  instances,
  selected,
  onSelectedChange,
  seriesBy,
  cap,
  disabled = false,
  onNarrowByLabel,
  onAggregate,
}: InstanceSelectorProps) {
  const selectedSet = new Set(selected);
  const projected = projectSeriesCount(instances, selected, seriesBy);
  const blockedInstances = instances.filter(
    (instance) =>
      !selectedSet.has(instance.id) && wouldExceedCap(instances, selected, seriesBy, cap, instance),
  );
  const showCapPanel = !disabled && blockedInstances.length > 0;

  function toggle(instanceId: string, checked: boolean) {
    if (checked) {
      onSelectedChange([...selected, instanceId]);
    } else {
      onSelectedChange(selected.filter((id) => id !== instanceId));
    }
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: '8px' }}>
        <Typography variant="body2" data-testid="instance-selector-projection">
          <Box component="strong" sx={{ fontWeight: 700 }}>
            {projected}
          </Box>{' '}
          {t('instanceSelectorProjectionSuffix', { cap })}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {seriesBy === 'replica'
            ? t('instanceSelectorPerReplica')
            : t('instanceSelectorPerInstance')}
        </Typography>
      </Box>

      <Box sx={{ mb: '10px' }}>
        <Meter projected={projected} cap={cap} />
      </Box>

      <Box
        role="group"
        aria-label={t('exploreStepInstancesTitle')}
        // Task 110's Playwright axe pass caught this once the instance
        // list actually grew past `maxHeight` (flaky in the test only
        // because the fixture list sometimes hadn't loaded enough rows
        // yet to overflow): a scrollable region needs to be focusable
        // itself, not merely contain focusable children — Safari can't
        // scroll it by keyboard otherwise (`scrollable-region-focusable`,
        // WCAG 2.1.1/2.1.3).
        tabIndex={0}
        sx={{
          display: 'flex',
          flexDirection: 'column',
          gap: '2px',
          maxHeight: 230,
          overflowY: 'auto',
          border: (theme) => `1px solid ${theme.palette.divider}`,
          borderRadius: '8px',
          p: '6px',
        }}
      >
        {instances.map((instance) => {
          const isSelected = selectedSet.has(instance.id);
          const isBlocked = blockedInstances.some((row) => row.id === instance.id);
          return (
            <Box
              key={instance.id}
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: '10px',
                px: '9px',
                py: '4px',
                borderRadius: '6px',
                opacity: isBlocked ? 0.6 : 1,
              }}
            >
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Checkbox
                  label={instance.label}
                  checked={isSelected}
                  disabled={disabled || isBlocked}
                  onChange={(checked) => toggle(instance.id, checked)}
                />
              </Box>
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ fontFamily: 'monospace', flexShrink: 0 }}
              >
                {t('instanceSelectorRowSeriesCount', {
                  count: seriesCountFor(instance, seriesBy),
                })}
              </Typography>
            </Box>
          );
        })}
      </Box>

      {showCapPanel && (
        <Box sx={{ mt: '10px' }}>
          <Alert severity="info" title={t('instanceSelectorCapReachedTitle')}>
            <Box sx={{ display: 'flex', gap: '8px', mt: '8px', flexWrap: 'wrap' }}>
              <Button size="sm" variant="outlined" onClick={onNarrowByLabel} disabled={disabled}>
                {t('instanceSelectorNarrowByLabel')}
              </Button>
              <Button size="sm" variant="outlined" onClick={onAggregate} disabled={disabled}>
                {t('instanceSelectorAggregateIntoOneLine')}
              </Button>
            </Box>
          </Alert>
        </Box>
      )}
    </Box>
  );
}
