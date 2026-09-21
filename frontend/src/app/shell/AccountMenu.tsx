/**
 * Identity, role chip, sign out (task 036, design §9/§14). Targets and
 * Admin are not yet real nav destinations (they ship in Phase 7) — they
 * appear here only behind `import.meta.env.DEV`, the same static
 * production-vs-dev branch `AuthProvider` (task 030) already uses, so a
 * preview link can never reach a production bundle by accident.
 *
 * `extraEntries` (task 106) lets the collapsed app bar (`< md`) fold the
 * organization display, theme toggle and support content into this menu
 * instead of dropping them — `I5`'s identity display must never
 * disappear, only move.
 *
 * **Rows carry icons and sit between rules**, matching the reference's own
 * account popover (`reference/app/shell.jsx`): identity block · rule ·
 * preview destinations · rule · Sign out. The icons are decorative — each
 * `SvgIcon` here is `aria-hidden`, so `Targets`, `Admin` and `Sign out`
 * keep exactly the accessible names `AppShell.test.tsx` asserts. The row
 * geometry (height, hover, radius) belongs to `shared/ui`'s `Menu`, not to
 * this file.
 */
import { Avatar, Box, Stack, Typography } from '@mui/material';
import { useNavigate } from 'react-router';

import { useAuth } from '@shared/auth/AuthProvider';
import { t } from '@shared/i18n';
import { Chip, IconButton, Menu, type MenuEntry } from '@shared/ui';

import { routes } from '../routes';

import { LogoutIcon, MapPinIcon, ShieldIcon } from './icons';

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const first = parts[0]?.[0] ?? '';
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? '') : '';
  return `${first}${last}`.toUpperCase();
}

export interface AccountMenuProps {
  extraEntries?: readonly MenuEntry[];
}

export function AccountMenu({ extraEntries = [] }: AccountMenuProps) {
  const { identity, logout } = useAuth();
  const navigate = useNavigate();

  if (identity === null) {
    return null;
  }

  const previewEntries: MenuEntry[] = import.meta.env.DEV
    ? [
        { kind: 'divider' },
        { kind: 'caption', label: t('previewTag') },
        {
          label: t('navTargets'),
          icon: <MapPinIcon />,
          onClick: () => {
            void navigate(routes.targets());
          },
        },
        {
          label: t('navAdmin'),
          icon: <ShieldIcon />,
          onClick: () => {
            void navigate(routes.admin());
          },
        },
      ]
    : [];

  return (
    <Menu
      trigger={
        <IconButton aria-label={t('account')}>
          <Avatar sx={{ width: 32, height: 32, fontSize: 13, fontWeight: 600 }}>
            {initials(identity.name)}
          </Avatar>
        </IconButton>
      }
      entries={[
        {
          kind: 'content',
          content: (
            <Stack spacing={1}>
              <Box>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {identity.name}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {identity.email}
                </Typography>
              </Box>
              <Box>
                <Chip label={identity.role} tone="info" />
              </Box>
            </Stack>
          ),
        },
        ...(extraEntries.length > 0 ? [{ kind: 'divider' } as const, ...extraEntries] : []),
        ...previewEntries,
        { kind: 'divider' },
        { label: t('signOut'), icon: <LogoutIcon />, danger: true, onClick: () => void logout() },
      ]}
    />
  );
}
