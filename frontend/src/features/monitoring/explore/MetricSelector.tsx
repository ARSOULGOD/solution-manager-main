/**
 * Step 1's real control (task 079, Dec `DM-D23` `SM-D30` `UI-D5`,
 * `dynamic-metrics` §5.5/§11.2, UI §A.10 — mirrors `docs/design/frontend
 * /reference/app/explore.jsx`'s `.catalog`/`.cat-item` markup). A native
 * MUI `RadioGroup` rather than the reference's hand-rolled clickable
 * `div`s — the same correction `StrategyPicker` (task 061) already made
 * for the same reason: arrow-key navigation and disabled-skip come from
 * the browser/MUI for free.
 *
 * **Single-select, enforced by construction (`DM-D23`).** `RadioGroup` is
 * exclusive by nature — there is no code path that adds a second selection,
 * so "choosing a different metric replaces the current one" needs no extra
 * guard here. `needs-typing` rows are `disabled` on the `Radio` itself (a
 * real disabled control, not a styled-only one — the same reasoning
 * `InstanceSelector`'s own header comment gives for its cap-blocked rows),
 * so a disabled row cannot be selected even by keyboard.
 *
 * Presentational for the metric *selection*, same split `InstanceSelector`
 * (task 078) establishes — `selected`/`onSelectedChange` are owned by the
 * caller. The type-metric dialog's mutation is this component's own
 * concern, not `ExplorePage`'s: opening it, running `declareMetricType`,
 * invalidating the catalog query so the entry's `state` flips server-side
 * rather than being patched by guesswork, and auto-selecting the
 * newly-typed metric (matching the reference's own
 * `setMetricName(typing.name)` after a successful type) all happen here.
 *
 * **Rows are built from `Radio` directly, not `FormControlLabel`.** A
 * `needs-typing` row needs its own real, independently-clickable "Type…"
 * button alongside the radio — nesting a `<button>` inside
 * `FormControlLabel`'s `<label>` (interactive-in-interactive) is invalid
 * HTML and the kind of thing `jest-axe` exists to catch, so each row is a
 * plain flex row with a bare `Radio` (context-connected to the enclosing
 * `RadioGroup` the same way `FormControlLabel`'s would be) plus sibling
 * label text and, for `needs-typing`, a sibling `Button`.
 */
import { Box, Radio, RadioGroup, Typography } from '@mui/material';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import type { MessageKey } from '@shared/i18n';
import type { CatalogEntry, MetricState, MetricType } from '@shared/testdoubles/data/metrics';
import { Alert, Button, Chip } from '@shared/ui';
import type { ChipTone } from '@shared/ui';

import type { MetricsRepository } from '../metricsRepository';

import { TypeMetricDialog } from './TypeMetricDialog';

const STATE_LABEL_KEY = {
  plottable: 'metricSelectorStatePlottable',
  'needs-typing': 'metricSelectorStateNeedsTyping',
  'type-suspect': 'metricSelectorStateTypeSuspect',
} as const satisfies Record<MetricState, MessageKey>;

// `ok`/`warn` reused from the existing `ChipTone` vocabulary — no invented
// colour, same posture `chartStateTags.ts` (task 074) documents for its
// own state-to-tone mapping.
const STATE_TONE: Record<MetricState, ChipTone> = {
  plottable: 'ok',
  'needs-typing': 'warn',
  'type-suspect': 'warn',
};

const LAYER_LABEL_KEY = {
  'package-baseline': 'metricSelectorLayerPkg',
  'user-extension': 'metricSelectorLayerExt',
} as const satisfies Record<CatalogEntry['layer'], MessageKey>;

export interface MetricSelectorProps {
  catalog: readonly CatalogEntry[];
  selected: string | null;
  onSelectedChange: (name: string) => void;
  metricsRepository: Pick<MetricsRepository, 'declareMetricType'>;
}

export function MetricSelector({
  catalog,
  selected,
  onSelectedChange,
  metricsRepository,
}: MetricSelectorProps) {
  const queryClient = useQueryClient();
  const [typingTarget, setTypingTarget] = useState<CatalogEntry | null>(null);
  const [metricType, setMetricType] = useState<MetricType | ''>('');
  const [unit, setUnit] = useState('');

  const selectedEntry = catalog.find((entry) => entry.name === selected) ?? null;

  function openTypeDialog(entry: CatalogEntry) {
    setTypingTarget(entry);
    setMetricType('');
    setUnit('');
  }

  function closeTypeDialog() {
    setTypingTarget(null);
  }

  return (
    <Box>
      <RadioGroup
        aria-label={t('exploreStepMetricTitle')}
        value={selected ?? ''}
        onChange={(event) => onSelectedChange(event.target.value)}
      >
        {catalog.map((entry) => {
          const needsTyping = entry.state === 'needs-typing';
          return (
            <Box
              key={entry.name}
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                // MUI `RadioGroup`'s root is `flex-direction: column` +
                // `flex-wrap: wrap` with no fixed height — with an
                // indefinite main size, each row ends up on its own "line"
                // and `align-items: stretch` then stretches it to fill
                // THAT line's own content-sized width, not the group's,
                // so a row this wide never gets constrained by its parent
                // at all. An explicit `width: 100%` (resolved directly
                // against the group's own known width, independent of the
                // line/stretch computation) plus `minWidth: 0` is what
                // actually lets the name below truncate instead of
                // pushing the trailing chips off the 340px sidebar.
                width: '100%',
                minWidth: 0,
              }}
            >
              <Radio
                value={entry.name}
                disabled={needsTyping}
                slotProps={{ input: { 'aria-label': entry.name } }}
              />
              {/* `minWidth: 0` lets the name truncate instead of forcing
                  the trailing chip group to wrap — the same "grow, don't
                  wrap" role the reference's own `.cat-n.grow` plays. */}
              <Typography
                component="span"
                variant="body2"
                sx={{
                  fontFamily: 'monospace',
                  fontWeight: 600,
                  flexGrow: 1,
                  minWidth: 0,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
                title={entry.name}
              >
                {entry.name}
              </Typography>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: '6px', flexShrink: 0 }}>
                <Chip label={t(LAYER_LABEL_KEY[entry.layer])} tone="neutral" mono />
                <Chip label={t(STATE_LABEL_KEY[entry.state])} tone={STATE_TONE[entry.state]} />
                {needsTyping && (
                  <Button size="sm" variant="text" onClick={() => openTypeDialog(entry)}>
                    {t('metricSelectorTypeAction')}
                  </Button>
                )}
              </Box>
            </Box>
          );
        })}
      </RadioGroup>

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: '6px' }}>
        {t('metricSelectorSingleSelectNote')}
      </Typography>

      {selectedEntry !== null && selectedEntry.metricType !== null && selectedEntry.unit !== null && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: '4px' }}>
          {t('metricSelectorTypedCaption', { type: selectedEntry.metricType, unit: selectedEntry.unit })}
        </Typography>
      )}

      {selectedEntry !== null && selectedEntry.state === 'type-suspect' && (
        <Box sx={{ mt: '10px' }}>
          <Alert severity="warn" title={t('metricSelectorTypeSuspectTitle')}>
            {selectedEntry.suspectReason}
          </Alert>
        </Box>
      )}

      <TypeMetricDialog
        open={typingTarget !== null}
        metricName={typingTarget?.name ?? ''}
        metricsRepository={metricsRepository}
        metricType={metricType}
        onMetricTypeChange={setMetricType}
        unit={unit}
        onUnitChange={setUnit}
        onClose={closeTypeDialog}
        onTyped={(entry) => {
          void queryClient.invalidateQueries({ queryKey: queryKeys.metrics.catalog() });
          onSelectedChange(entry.name);
          closeTypeDialog();
        }}
      />
    </Box>
  );
}
