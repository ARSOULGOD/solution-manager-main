/**
 * Restart strategy picker (task 061, Dec `SM-D15` `SM-D22` `FE-D18`
 * (B **blocked**) `FE-D20`, Design §10.1/§16/§25, UI §A.8 strategy picker;
 * reference `designer_panels.jsx`'s own `StrategyPicker`). The design's
 * hardest constraint, implemented honestly: **the walking skeleton runs
 * on `dp-remote`, which has no apply-ordering guarantee** — this
 * component's own job stops at picking a strategy; `RestartConfirm` and
 * `ApplyOutcomePanel` own what happens after.
 *
 * A native MUI `RadioGroup` rather than the reference's hand-rolled
 * `role="radio"` div: arrow-key navigation within the group and
 * disabled-skip come from the browser/MUI for free, which the reference's
 * own `tabIndex`/`onKeyDown` re-implementation does not fully match (no
 * roving tabindex, no arrow-key move between options).
 */
import {
  FormControl,
  FormControlLabel,
  FormLabel,
  Radio,
  RadioGroup,
  Typography,
} from '@mui/material';
import { useId, type ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert, Chip } from '@shared/ui';
import type { ChipTone } from '@shared/ui';

import type { RestartStrategy } from '../restartStrategies';

import { evaluateStrategyAvailability } from './strategyAvailability';
import type { StrategyPickerProps } from './StrategyPicker.types';

interface Badge {
  label: string;
  tone: ChipTone;
}

function badges(strategy: RestartStrategy, disabled: boolean): Badge[] {
  const result: Badge[] = [];
  if (strategy.default === true) {
    result.push({ label: t('strategyPickerDefaultChip'), tone: 'ok' });
  }
  if (strategy.consequence === 'high') {
    result.push({ label: t('strategyPickerHighConsequenceChip'), tone: 'warn' });
  }
  if (strategy.consequence === 'outage') {
    result.push({ label: t('strategyPickerOutageChip'), tone: 'err' });
  }
  if (disabled) {
    result.push({ label: t('strategyPickerUnavailableChip'), tone: 'idle' });
  }
  return result;
}

export function StrategyPicker({
  strategies,
  value,
  onChange,
  requiresUniformConfig,
  healthMappingDeclared,
  healthGateUnsafeReason,
}: StrategyPickerProps): ReactElement {
  const legendId = useId();

  return (
    <FormControl component="fieldset" fullWidth>
      <FormLabel id={legendId} component="legend" sx={{ mb: 1 }}>
        {t('strategyPickerHeading')}
      </FormLabel>

      {requiresUniformConfig && (
        <Alert severity="info" fullWidth>
          {t('strategyPickerUniformBanner')}
        </Alert>
      )}

      <RadioGroup
        aria-labelledby={legendId}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      >
        {strategies.map((strategy) => {
          const availability = evaluateStrategyAvailability(strategy, {
            requiresUniformConfig,
            healthMappingDeclared,
            ...(healthGateUnsafeReason !== undefined ? { healthGateUnsafeReason } : {}),
          });
          const selected = value === strategy.key && !availability.disabled;

          return (
            <FormControlLabel
              key={strategy.key}
              value={strategy.key}
              disabled={availability.disabled}
              control={<Radio />}
              sx={{
                alignItems: 'flex-start',
                m: 0,
                mt: 1,
                p: 1.5,
                border: 1,
                borderRadius: 1,
                borderColor: selected ? 'primary.main' : 'divider',
                ...(selected ? { backgroundColor: 'action.selected' } : {}),
              }}
              label={
                <span style={{ display: 'block' }}>
                  <span style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
                    <Typography component="span" variant="body2" sx={{ fontWeight: 600 }}>
                      {strategy.label}
                    </Typography>
                    {badges(strategy, availability.disabled).map((badge) => (
                      <Chip key={badge.label} label={badge.label} tone={badge.tone} dot />
                    ))}
                  </span>
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                    {strategy.description}
                  </Typography>
                  {availability.disabled && availability.reason !== undefined && (
                    <Typography
                      variant="caption"
                      color="warning.main"
                      sx={{ mt: 0.5, display: 'block' }}
                    >
                      {availability.reason}
                    </Typography>
                  )}
                </span>
              }
            />
          );
        })}
      </RadioGroup>

      <Alert severity="info" fullWidth>
        {t('strategyPickerOrderingNote')}
      </Alert>
    </FormControl>
  );
}
