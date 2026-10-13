/**
 * The "Solution Manager · Solace Console" wordmark (task 106, UI §A.2's
 * drawer "brand block"). No logo asset exists in-tree — reuses
 * `LoginPage.tsx`'s own text-only treatment (`navLabel` +
 * `loginBrandTagline`) rather than inventing a second wordmark.
 *
 * Lives in the permanent drawer at every width, and additionally in the
 * app bar itself once the drawer becomes an overlay (`< lg`) — otherwise
 * a narrow viewport has no persistent "Solution Manager" identity on
 * screen except behind the collapsed drawer.
 */
import { Box, Typography } from '@mui/material';

import { t } from '@shared/i18n';

export interface BrandProps {
  /** `onNav` — light text for the navy drawer. `default` — for the white app bar. */
  tone?: 'onNav' | 'default';
  /**
   * Rail mode (task 111): the wordmark does not fit a 76px rail, so the
   * drawer shows a monogram tile instead. It is decorative — the drawer's
   * `<nav>` is already labelled "Solution Manager" — so it carries the
   * wordmark as a `title`/tooltip rather than as a second landmark label.
   */
  compact?: boolean;
}

export function Brand({ tone = 'onNav', compact = false }: BrandProps) {
  if (compact) {
    return <BrandMark tone={tone} />;
  }

  return (
    <Box data-shell-brand>
      <Typography
        variant="subtitle2"
        component="span"
        sx={(theme) => ({
          display: 'block',
          fontWeight: 700,
          lineHeight: 1.2,
          color: tone === 'onNav' ? theme.palette.nav.text : theme.palette.text.primary,
        })}
      >
        {t('navLabel')}
      </Typography>
      <Typography
        variant="caption"
        component="span"
        sx={(theme) => ({
          display: 'block',
          color: tone === 'onNav' ? theme.palette.nav.muted : theme.palette.text.secondary,
        })}
      >
        {t('loginBrandTagline')}
      </Typography>
    </Box>
  );
}

/** The monogram tile the rail shows in place of the wordmark. */
function BrandMark({ tone }: { tone: 'onNav' | 'default' }) {
  return (
    <Box
      data-shell-brand
      data-compact="true"
      title={t('navLabel')}
      sx={(theme) => ({
        width: 36,
        height: 36,
        mx: 'auto',
        borderRadius: 1.5,
        display: 'grid',
        placeItems: 'center',
        fontWeight: 700,
        fontSize: 14,
        letterSpacing: '0.04em',
        backgroundColor: theme.palette.nav.accent,
        color: tone === 'onNav' ? theme.palette.nav.bg : theme.palette.text.primary,
      })}
    >
      SM
    </Box>
  );
}
