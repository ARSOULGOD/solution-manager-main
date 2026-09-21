/**
 * Environment badge (task 041, UI §A.6/§A.13.1). `PROD` is the one
 * consumer of the `purple` tone `ChipProps`/`Chip.types.ts` reserved for
 * it — `DEV`/`TEST` stay neutral/info so PROD is the only env that reads
 * as visually distinct, never decorative variety across all three.
 */
import { Chip } from '../Chip';
import type { ChipTone } from '../Chip';

import type { Env, EnvChipProps } from './EnvChip.types';

const ENV_TONE: Record<Env, ChipTone> = {
  DEV: 'neutral',
  TEST: 'info',
  PROD: 'purple',
};

export function EnvChip({ env }: EnvChipProps) {
  return <Chip label={env} tone={ENV_TONE[env]} />;
}
