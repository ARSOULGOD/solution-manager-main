/**
 * The small circled "i" affordance the prototype uses ~40 times to
 * explain a state or flag an invention (design §9, UI §A.1). `Tooltip`
 * (task 014) already makes the trigger keyboard-reachable — this is
 * purely the icon + wiring.
 */
import { ToneIcon } from '../internal/toneIcons';
import { Tooltip } from '../Tooltip';

import type { InfoTipProps } from './InfoTip.types';

export function InfoTip({ tip }: InfoTipProps) {
  return (
    <Tooltip title={tip}>
      <ToneIcon tone="info" fontSize="small" />
    </Tooltip>
  );
}
