/**
 * Five-rung config-state stepper (task 060, Dec `SM-D22` `UI-D9`, Design
 * §10.1, UI §A.8 config stepper; reference `designer_panels.jsx`'s own
 * `ConfigStepper`). `restart-observed ≠ convergence`: the fifth rung,
 * `effective-verified`, is structurally unreachable through this
 * component's props (`ConfigLadderReachedKey`, `shared/vocab`) — there is
 * no fixture or caller that can mark it done, only ever current or
 * pending, which is what "requires an explicit verified signal" means at
 * the type level rather than as a runtime check that could be skipped.
 *
 * Shared, not Designer-local: task 065's governance configuration-state
 * ladder renders the same five rungs from the same source (its own
 * acceptance line says so explicitly) — `shared/vocab/configLadder.ts`'s
 * step data and this component are that shared source.
 */
import { Box, Typography } from '@mui/material';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';

import { CONFIG_LADDER_STEP_KEYS, configLadderSteps } from '../../vocab';
import { Chip } from '../Chip';
import { InfoTip } from '../InfoTip';
import { ToneIcon } from '../internal/toneIcons';

import type { ConfigStepperProps } from './ConfigStepper.types';

function stateWord(index: number, reachedIndex: number): 'done' | 'current' | 'pending' {
  if (index < reachedIndex) {
    return 'done';
  }
  return index === reachedIndex ? 'current' : 'pending';
}

const STATE_TONE = { done: 'ok', current: 'info', pending: 'neutral' } as const;
const STATE_MESSAGE_KEY = {
  done: 'configStepperStateDone',
  current: 'configStepperStateCurrent',
  pending: 'configStepperStatePending',
} as const;

export function ConfigStepper({ reached }: ConfigStepperProps): ReactElement {
  const reachedIndex = CONFIG_LADDER_STEP_KEYS.indexOf(reached);

  return (
    <Box>
      <Box
        component="ol"
        aria-label={t('configStepperListLabel')}
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          gap: 1,
          m: 0,
          p: 0,
          listStyle: 'none',
        }}
      >
        {configLadderSteps.map((step, index) => {
          // Colour is never the only channel (task 013's own rule):
          // `aria-label` states done/current/pending in words, since a
          // dot's tone alone doesn't reach a screen reader.
          const state = stateWord(index, reachedIndex);
          return (
            <Box
              key={step.key}
              component="li"
              aria-label={t('configStepperStepAriaLabel', {
                label: step.label,
                state: t(STATE_MESSAGE_KEY[state]),
              })}
              sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}
            >
              {index > 0 && (
                <Box aria-hidden sx={{ width: 16, height: '1px', backgroundColor: 'divider' }} />
              )}
              <Chip label={step.label} tone={STATE_TONE[state]} dot />
              <InfoTip tip={step.tip} />
            </Box>
          );
        })}
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 0.75, mt: 1 }}>
        <ToneIcon tone="warn" fontSize="small" />
        <Typography variant="caption" color="text.secondary">
          {t('configStepperIndeterminateNote')}
        </Typography>
      </Box>
    </Box>
  );
}
