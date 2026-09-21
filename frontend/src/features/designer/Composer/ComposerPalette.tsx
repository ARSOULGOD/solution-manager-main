/**
 * The composer's left pane (task 063, design §10, UI §A.8.18): the
 * package's own declared component types, never a hard-coded vendor list.
 * Read-only in v1 — no wire endpoint exists to add a component instance to
 * a running solution (`I1`: that would be MI-instance authoring, not
 * deployment configuration), so each item states that plainly rather than
 * inviting a drag that goes nowhere.
 */
import { Box, Typography } from '@mui/material';
import type { ReactElement } from 'react';

import type { ComponentDeclaration } from '@contracts/view-model/types';
import { t } from '@shared/i18n';

import { ComponentRoleIcon } from './composerIcons';
import { composerRoleLabel } from './composerRoleLabel';

export interface ComposerPaletteProps {
  components: readonly ComponentDeclaration[];
}

export function ComposerPalette({ components }: ComposerPaletteProps): ReactElement {
  return (
    <Box
      component="nav"
      aria-label={t('composerPaletteHeading')}
      sx={{ borderRight: 1, borderColor: 'divider', p: 1.5, overflowY: 'auto' }}
    >
      <Typography
        variant="caption"
        sx={{
          display: 'block',
          px: 0.5,
          pb: 1,
          fontWeight: 700,
          textTransform: 'uppercase',
          letterSpacing: '0.07em',
          color: 'text.secondary',
        }}
      >
        {t('composerPaletteHeading')}
      </Typography>

      {components.map((component) => (
        <Box
          key={component.type}
          title={t('composerPaletteItemUnavailable')}
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            p: 1.25,
            mb: 1,
            border: 1,
            borderColor: 'divider',
            borderRadius: 1.5,
          }}
        >
          <Box
            aria-hidden
            sx={{
              width: 30,
              height: 30,
              borderRadius: 1,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              flexShrink: 0,
              color: 'primary.contrastText',
              backgroundColor: component.role === 'sink' ? 'secondary.main' : 'primary.main',
            }}
          >
            <ComponentRoleIcon role={component.role} fontSize="small" />
          </Box>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="caption" sx={{ display: 'block', fontWeight: 700 }}>
              {composerRoleLabel(component.role)}
            </Typography>
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{
                display: 'block',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {component.type}
            </Typography>
          </Box>
        </Box>
      ))}

      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ display: 'block', px: 0.5, pt: 1, lineHeight: 1.5 }}
      >
        {t('composerPaletteCaption')}
      </Typography>
    </Box>
  );
}
