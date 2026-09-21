/**
 * The typing dialog `MetricSelector` opens for a `needs-typing` entry (task
 * 079, Dec `DM-D23` `SM-D30` `UI-D5`, `dynamic-metrics` §5.5/§11.2, UI §A.10
 * type-metric dialog — mirrors `docs/design/frontend/reference/app
 * /explore.jsx`'s `TypeMetricModal`).
 *
 * `repository` is a prop, not `useMetrics()` read internally — the same
 * shape `RollbackDialog`/`RestoreRuntimeDialog` (designer, tasks 059/062)
 * already use for a dialog that owns one mutation, which keeps this
 * component testable with a stub repository and no provider tree.
 *
 * ⚠️ **No suggested type is offered.** The catalog entry carries neither
 * `firstSeen` nor a suggestion field (§16 Q14) — stated in the dialog copy
 * rather than guessing one client-side, which `SM-D30` forbids outright.
 */
import {
  Box,
  FormControl,
  FormControlLabel,
  FormLabel,
  Radio,
  RadioGroup,
  Typography,
} from '@mui/material';
import { useMutation } from '@tanstack/react-query';

import { t } from '@shared/i18n';
import type { MessageKey } from '@shared/i18n';
import type { CatalogEntry, MetricType } from '@shared/testdoubles/data/metrics';
import { Alert, Button, Dialog, TextField } from '@shared/ui';

import type { MetricsRepository } from '../metricsRepository';

const TYPE_OPTION_KEY = {
  counter: 'typeMetricDialogOptionCounter',
  gauge: 'typeMetricDialogOptionGauge',
  histogram: 'typeMetricDialogOptionHistogram',
} as const satisfies Record<MetricType, MessageKey>;

const METRIC_TYPES: readonly MetricType[] = ['counter', 'gauge', 'histogram'];

export interface TypeMetricDialogProps {
  open: boolean;
  metricName: string;
  metricsRepository: Pick<MetricsRepository, 'declareMetricType'>;
  metricType: MetricType | '';
  onMetricTypeChange: (metricType: MetricType) => void;
  unit: string;
  onUnitChange: (unit: string) => void;
  onClose: () => void;
  onTyped: (entry: CatalogEntry) => void;
}

export function TypeMetricDialog({
  open,
  metricName,
  metricsRepository,
  metricType,
  onMetricTypeChange,
  unit,
  onUnitChange,
  onClose,
  onTyped,
}: TypeMetricDialogProps) {
  const declare = useMutation({
    mutationFn: () => metricsRepository.declareMetricType(metricName, metricType as MetricType, unit),
    onSuccess: (entry) => onTyped(entry),
  });

  return (
    <Dialog
      open={open}
      title={t('typeMetricDialogTitle', { metric: metricName })}
      onClose={onClose}
      dirty={declare.isPending}
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={declare.isPending}>
            {t('cancel')}
          </Button>
          <Button
            onClick={() => declare.mutate()}
            disabled={metricType === '' || declare.isPending}
          >
            {declare.isPending ? t('typeMetricDialogPending') : t('typeMetricDialogSaveAction')}
          </Button>
        </>
      }
    >
      <Alert severity="info">{t('typeMetricDialogForbiddenNotice')}</Alert>

      <Box sx={{ mt: 2 }}>
        <FormControl component="fieldset">
          <FormLabel component="legend" sx={{ typography: 'overline', mb: 1 }}>
            {t('typeMetricDialogTypeLabel')}
          </FormLabel>
          <RadioGroup
            row
            value={metricType}
            onChange={(event) => onMetricTypeChange(event.target.value as MetricType)}
          >
            {METRIC_TYPES.map((type) => (
              <FormControlLabel
                key={type}
                value={type}
                control={<Radio />}
                label={t(TYPE_OPTION_KEY[type])}
              />
            ))}
          </RadioGroup>
        </FormControl>
      </Box>

      <Box sx={{ mt: 2 }}>
        <TextField
          label={t('typeMetricDialogUnitLabel')}
          value={unit}
          onChange={(event) => onUnitChange(event.target.value)}
          helperText={t('typeMetricDialogUnitHelp')}
          fullWidth
        />
      </Box>

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
        {t('typeMetricDialogNoSuggestionNote')}
      </Typography>

      {declare.isError && (
        <Box sx={{ mt: 2 }}>
          <Alert severity="err">{t('typeMetricDialogErrorDetail')}</Alert>
        </Box>
      )}
    </Dialog>
  );
}
