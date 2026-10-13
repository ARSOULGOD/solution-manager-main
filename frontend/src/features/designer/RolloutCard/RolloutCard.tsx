/**
 * Collapsible rollout card (task 060, Dec `SM-D22` `UI-D9`, Design §10.1,
 * UI §A.8 rollout card; reference `designer_panels.jsx`'s own
 * `RolloutCard`) — pairs `ConfigStepper` with `ReplicaTable` under one
 * disclosure, "Save · Apply · Restart are three distinct things and never
 * collapse" (the reference file's own header comment).
 *
 * A real `<button>` with `aria-expanded`, not MUI `Accordion` — matches
 * `SectionCard`'s own collapsible pattern (task 049) rather than
 * introducing a second disclosure idiom into the same feature folder.
 */
import { Box, SvgIcon, Typography } from '@mui/material';
import type { SvgIconProps } from '@mui/material';
import { useState } from 'react';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Chip, ConfigStepper } from '@shared/ui';

import { ReplicaTable } from '../ReplicaTable';

import type { RolloutCardProps } from './RolloutCard.types';

// Inlined, not `@mui/icons-material` — same ruling as `SectionCard`'s own
// expander icon (that package is not a project dependency).
function ExpanderIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M8 5l8 7-8 7V5z" />
    </SvgIcon>
  );
}

export function RolloutCard({
  replicas,
  reached,
  diverged,
  defaultOpen = false,
}: RolloutCardProps): ReactElement {
  const [open, setOpen] = useState(defaultOpen);

  return (
    <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1, overflow: 'hidden' }}>
      <Box
        component="button"
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          width: '100%',
          p: 1.5,
          border: 'none',
          background: 'none',
          cursor: 'pointer',
          font: 'inherit',
          textAlign: 'left',
          color: 'text.primary',
        }}
      >
        <ExpanderIcon fontSize="small" sx={{ transform: open ? 'rotate(90deg)' : 'none' }} />
        {/* `component="span"`: a toggle button's label, not a document
            heading — same fix, same reason, as `SectionCard` (task 123). */}
        <Typography component="span" variant="subtitle2" sx={{ flexGrow: 1 }}>
          {t('rolloutCardTitle')}
        </Typography>
        {diverged ? (
          <Chip label={t('rolloutCardDivergedChip')} tone="err" dot />
        ) : (
          <Chip label={t('replicaCount', { count: replicas.length })} tone="neutral" dot />
        )}
      </Box>

      {open && (
        <Box sx={{ p: 1.5, pt: 0 }}>
          <Typography
            variant="overline"
            color="text.secondary"
            sx={{ display: 'block', mt: 1.5, mb: 1 }}
          >
            {t('rolloutCardConfigStateHeading')}
          </Typography>
          <ConfigStepper reached={reached} />

          <Typography
            variant="overline"
            color="text.secondary"
            sx={{ display: 'block', mt: 2.5, mb: 1 }}
          >
            {t('rolloutCardPerReplicaHeading')}
          </Typography>
          <ReplicaTable replicas={replicas} />
        </Box>
      )}
    </Box>
  );
}
