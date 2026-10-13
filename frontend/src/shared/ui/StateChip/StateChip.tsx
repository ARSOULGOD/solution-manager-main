/**
 * Renders one vocabulary entry (task 013) as icon + text + tone, plus an
 * `InfoTip` (task 014) when the entry carries a `tip`. Colour is never
 * the only channel: `Chip` already carries icon + text, this only adds
 * the explanation.
 */
import { Box } from '@mui/material';

import { Chip } from '../Chip';
import { InfoTip } from '../InfoTip';

import type { StateChipProps } from './StateChip.types';

export function StateChip({ entry }: StateChipProps) {
  return (
    <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
      <Chip label={entry.label} tone={entry.tone} icon={entry.icon} />
      {entry.tip !== undefined && <InfoTip tip={entry.tip} />}
    </Box>
  );
}
