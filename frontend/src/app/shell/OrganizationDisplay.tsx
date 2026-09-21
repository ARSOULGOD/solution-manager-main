/**
 * The organization the caller is signed in to, in the app bar.
 *
 * **Display only** — never a switcher, never a route segment (`I5`,
 * `FE-D8`, `D9` 🔒, `REQ-003`). Rendered as non-interactive text with a
 * title explaining why.
 *
 * `D20` makes Organization the top isolation and ownership boundary. The
 * string comes from `Identity.organization`,
 * is supplied by the server, and is never sent back as client authority.
 */
import { Box, Typography } from '@mui/material';

import { t } from '@shared/i18n';

export interface OrganizationDisplayProps {
  organization: string;
}

export function OrganizationDisplay({ organization }: OrganizationDisplayProps) {
  return (
    <Box
      title={t('organizationTooltip')}
      sx={{ display: 'flex', alignItems: 'baseline', gap: 0.75 }}
    >
      <Typography variant="caption" color="text.secondary">
        {t('organizationLabel')}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 500 }}>
        {organization}
      </Typography>
    </Box>
  );
}
