/**
 * A principal rendered as what it is — a discriminated `user | group`
 * reference, never a bare id (task 092, `SM-D23`). An IdP-sourced group
 * says so; an assignment therefore survives an IdP migration as a data
 * change. Reused by the by-scope view and the access check (FE-093).
 */
import { Box, Typography } from '@mui/material';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import type { RbacPrincipal } from '@shared/testdoubles/data/rbac';
import { Chip } from '@shared/ui';

export interface PrincipalTagProps {
  principal: RbacPrincipal;
  showSub?: boolean;
}

function kindLabel(principal: RbacPrincipal): string {
  if (principal.kind === 'group') {
    return principal.source === 'idp' ? t('adminPrincipalIdpGroup') : t('adminPrincipalKindGroup');
  }
  return t('adminPrincipalKindUser');
}

export function PrincipalTag({ principal, showSub = true }: PrincipalTagProps): ReactElement {
  return (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 1, minWidth: 0 }}>
      <Box sx={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, flexWrap: 'wrap' }}>
          <Typography component="span" variant="body2" sx={{ fontWeight: 600 }}>
            {principal.name}
          </Typography>
          <Chip
            tone={principal.kind === 'group' ? 'info' : 'neutral'}
            label={kindLabel(principal)}
          />
        </Box>
        {showSub && (
          <Typography component="span" variant="caption" color="text.secondary" noWrap>
            {principal.sub}
          </Typography>
        )}
      </Box>
    </Box>
  );
}
