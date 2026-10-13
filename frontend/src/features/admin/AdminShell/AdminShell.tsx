/**
 * Admin shell — header, the model/catalog "honesty banner", and the
 * roles/assignments/access/groups/users tabs (task 090, Req `REQ-006`, Dec `D10`
 * 🔒 `DM-D18`, Design §14/§15, UI §A.12). Not in the sidebar nav (design
 * §14: "Do not show Targets/Admin in the shell until those routes exist")
 * — `AccountMenu`'s `DEV`-only preview entry is the only in-app way in
 * today (the standalone Targets preview screen itself is removed —
 * integration plan 013 Task 6 — but Admin follows the same pattern).
 *
 * The `roles` (FE-091 — `RolesView`), `assignments` (FE-092 —
 * `AssignmentsView`), `access` (FE-093 — `AccessCheckView`), `groups`
 * (`GroupsView`) and `users` (FE-094 — `UsersView`) tabs each render their
 * real view; no tab is a placeholder any more.
 *
 * Tab ORDER is Roles → Assignments → Access check → Groups → Users: the
 * model first, then who holds it, then the two kinds of principal that
 * can. `ADMIN_TABS` here and in `app/routes.ts` must stay in step — they
 * are separate for the boundaries reason `AdminShell.types.ts` records.
 */
import { Box, SvgIcon, Typography } from '@mui/material';
import type { SvgIconProps } from '@mui/material';
import { useState } from 'react';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert, Chip, RouteTabs, ToneIcon } from '@shared/ui';
import type { RouteTab } from '@shared/ui';
import { PageWidth, Stack } from '@shared/ui/layout';

import { AccessCheckView } from '../access';
import { AssignmentsView } from '../assignments';
import { GroupsView } from '../groups';
import { RolesView } from '../roles';
import { UsersView } from '../users';

import { ADMIN_TABS, type AdminShellProps, type AdminTab } from './AdminShell.types';

// Same triangle `SectionCard`/`ControlStep` already use for their own
// expand/collapse toggle — rotated via `sx`, never swapped for a second
// icon component.
function ExpanderIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M8 5l8 7-8 7V5z" />
    </SvgIcon>
  );
}

const TAB_LABELS: Record<AdminTab, string> = {
  roles: t('adminTabRoles'),
  assignments: t('adminTabAssignments'),
  access: t('adminTabAccess'),
  groups: t('adminTabGroups'),
  users: t('adminTabUsers'),
};

// Feature code may not import `@app/routes` (module boundaries) — same
// hand-written-template pattern `InstanceHeader`'s own `categoryHref`
// already uses for this exact reason.
function adminTabHref(tab: AdminTab): string {
  return `/admin/${tab}`;
}

export function AdminShell({ tab, repository }: AdminShellProps): ReactElement {
  const [findingsOpen, setFindingsOpen] = useState(false);

  const tabs: RouteTab[] = ADMIN_TABS.map((key) => ({
    to: adminTabHref(key),
    label: TAB_LABELS[key],
  }));

  return (
    <PageWidth size="wide">
      <Stack gap={2}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
          <Typography variant="h5" component="h1">
            {t('adminShellTitle')}
          </Typography>
          <Chip tone="neutral" label={t('adminBadgeLaterIncrement')} />
          <Chip tone="idle" label={t('adminBadgeNotInNav')} />
          <Box sx={{ flex: 1 }} />
          <Box
            component="button"
            type="button"
            onClick={() => setFindingsOpen((open) => !open)}
            aria-expanded={findingsOpen}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 0.75,
              border: 'none',
              background: 'none',
              cursor: 'pointer',
              font: 'inherit',
              color: 'text.primary',
              py: 1,
              px: 1.5,
            }}
          >
            <ToneIcon tone="warn" fontSize="small" />
            <Typography component="span" variant="body2" sx={{ fontWeight: 600 }}>
              {t('adminFindingsToggle')}
            </Typography>
            <ExpanderIcon
              fontSize="small"
              sx={{ transform: findingsOpen ? 'rotate(90deg)' : 'rotate(0deg)' }}
            />
          </Box>
        </Box>
        {findingsOpen && (
          <Alert severity="warn" title={t('adminFindingsBannerTitle')} fullWidth>
            {t('adminFindingsBannerBody')}
          </Alert>
        )}
        <RouteTabs tabs={tabs}>
          <Box sx={{ py: 3 }}>
            {tab === 'roles' ? (
              <RolesView repository={repository} />
            ) : tab === 'assignments' ? (
              <AssignmentsView repository={repository} />
            ) : tab === 'access' ? (
              <AccessCheckView repository={repository} />
            ) : tab === 'groups' ? (
              <GroupsView repository={repository} />
            ) : (
              <UsersView repository={repository} />
            )}
          </Box>
        </RouteTabs>
      </Stack>
    </PageWidth>
  );
}
