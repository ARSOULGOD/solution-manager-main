/**
 * The organization(s) the caller can see, in the app bar.
 *
 * **Display only** — never a switcher, never a route segment (`I5`,
 * `FE-D8`, `D9` 🔒, `REQ-003`). Rendered as non-interactive text with a
 * title explaining why.
 *
 * `D20` makes Organization the top isolation and ownership boundary. The
 * names come from `Identity.organizationNames`, supplied by the server, and
 * are never sent back as client authority. The real `Identity` wire
 * response carries the full visible hierarchy (`organizations[]`), not a
 * single id — an unscoped administrator may see more than one top-level
 * organization, so this shows the first name plus a count rather than
 * assume exactly one (`docs/plans/integration/reports/real-e2e-2026-10-08.md`).
 */
import { Box, Typography } from '@mui/material';

import { t } from '@shared/i18n';

export interface OrganizationDisplayProps {
  organizationNames: readonly string[];
}

export function OrganizationDisplay({ organizationNames }: OrganizationDisplayProps) {
  const [first, ...rest] = organizationNames;
  return (
    <Box
      title={t('organizationTooltip')}
      sx={{ display: 'flex', alignItems: 'baseline', gap: 0.75 }}
    >
      <Typography variant="caption" color="text.secondary">
        {t('organizationLabel')}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 500 }}>
        {first ?? t('organizationDisplayNone')}
        {rest.length > 0 ? ` ${t('organizationDisplayMore', { count: rest.length })}` : ''}
      </Typography>
    </Box>
  );
}
